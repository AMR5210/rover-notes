"""Extracts a PDF into indexable text, keeping the page each passage came from.

The rest of the system indexes one flat string per document and cites a passage by its
character span into that string. A PDF has no such string, and the number a reader needs
in order to check a citation is the page — "the table on page 34", not "characters 51,200
to 51,900". So parsing produces both: the flat text everything downstream already expects,
and the character range each page occupies within it. A span resolves to a page by
comparison, with nothing else in the pipeline having to know a PDF was involved.

Tables are extracted separately from prose and rendered as pipe-delimited rows. A table
read as running text loses the alignment between a cell and its column header, which is
the part of a table a question is usually about: "what is the timeout for the read path"
is answerable from a rendered row and not from the same numbers in reading order.

pdfplumber does the layout work. It sits on pdfminer.six and exposes word positions,
which is what makes column detection possible; pypdf is faster but returns text in
content-stream order, which for a two-column page interleaves the columns.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import IO, Any, cast

import pdfplumber

from ml_service.parsing import ocr

log = logging.getLogger(__name__)

# Two or more spaces that pdfminer emits between words it could not decide were adjacent.
# Collapsed because they survive into the chunk text and, through it, into the embedding.
RUNS_OF_SPACES = re.compile(r"[ \t]{2,}")

# Three or more blank lines, which a PDF with generous leading produces in quantity.
RUNS_OF_BLANK_LINES = re.compile(r"\n{3,}")

# A cell that is only whitespace or the artefacts pdfplumber leaves for a merged cell.
EMPTY_CELL = re.compile(r"^[\s​]*$")


@dataclass(frozen=True)
class Page:
    """One page, and where its text sits in the document's flat string."""

    number: int
    char_start: int
    char_end: int
    tables: int
    # True when this page had no text layer and was read by OCR instead. Reported so a
    # wrong answer traced to this page can be weighed differently: recognised text
    # carries errors that an extracted text layer does not.
    ocr: bool = False


@dataclass
class ParsedDocument:
    """A PDF as the indexer wants it: one string, plus what each span came from."""

    text: str
    pages: list[Page] = field(default_factory=list)

    @property
    def table_count(self) -> int:
        return sum(page.tables for page in self.pages)

    @property
    def ocr_pages(self) -> list[int]:
        """The pages that were recognised rather than read, in order."""
        return [page.number for page in self.pages if page.ocr]

    @property
    def empty_pages(self) -> list[int]:
        """Pages that produced no text at all, by either route.

        A document where this covers every page is one that will index to nothing. The
        caller is told rather than left to discover it when a search returns nothing.
        """
        return [page.number for page in self.pages if page.char_end == page.char_start]

    def page_for(self, char_offset: int) -> int | None:
        """The page a character offset falls on, or None if it is outside the document.

        This is what turns a citation span into a number a reader can act on. Ranges do
        not overlap and are in order, so the first containing range is the answer.
        """
        for page in self.pages:
            if page.char_start <= char_offset < page.char_end:
                return page.number
        return None


def parse_pdf(source: str | Path | IO[bytes], *, max_pages: int | None = None) -> ParsedDocument:
    """Reads a PDF into text and page ranges.

    ``max_pages`` bounds the work a single upload can cause. Parsing is CPU-bound and
    happens while a caller waits, so an unbounded page count makes the request time a
    property of the file rather than of the service.
    """
    chunks: list[str] = []
    pages: list[Page] = []
    offset = 0

    # pdfplumber reads any binary file object; its type stub names only the two
    # concrete ones it was written against, so a caller passing BytesIO is fine.
    with pdfplumber.open(cast(Any, source)) as pdf:
        for index, page in enumerate(pdf.pages, start=1):
            if max_pages is not None and index > max_pages:
                log.warning("stopping at page %d of %d", max_pages, len(pdf.pages))
                break

            body, tables = _page_text(page)

            # A scanned page extracts to nothing and reports no error. Left alone it
            # becomes a document that indexes to nothing and can never be retrieved, with
            # nothing anywhere to say why.
            recognised = False
            if ocr.needs_ocr(body):
                from_image = ocr.read_page(page)
                if from_image:
                    body = _clean(from_image)
                    recognised = True
            # A page is always given a range, even an empty one. Dropping it would make
            # the page numbers below it disagree with the numbers printed on the pages,
            # which is the one thing this mapping exists to get right.
            rendered = body + "\n\n" if body else ""
            chunks.append(rendered)
            pages.append(
                Page(
                    number=index,
                    char_start=offset,
                    char_end=offset + len(rendered),
                    tables=tables,
                    ocr=recognised,
                )
            )
            offset += len(rendered)

    parsed = ParsedDocument(text="".join(chunks), pages=pages)
    if parsed.ocr_pages:
        log.info("read %d page(s) by OCR: %s", len(parsed.ocr_pages), parsed.ocr_pages)
    if parsed.pages and len(parsed.empty_pages) == len(parsed.pages):
        log.warning(
            "no text could be extracted from any of the %d page(s); "
            "this document will not be retrievable",
            len(parsed.pages),
        )
    return parsed


def _page_text(page: Any) -> tuple[str, int]:
    """One page's prose followed by its tables, and how many tables there were.

    The prose is read from the page with each table's area removed. A table's cells are
    text like any other, so extracting the page whole and then extracting the tables
    yields both copies: the same values once in reading order, where a number has lost
    the column that gave it meaning, and once as a rendered row. Indexed together they
    embed the same fact twice and put the weaker copy in the corpus alongside the better
    one.

    Tables are appended rather than reinserted where they were found. Their position on
    the page is known but their position in the prose is not, and a table placed after
    the paragraph that introduces it reads correctly far more often than one spliced at
    a guessed offset.
    """
    tables = page.find_tables()

    body = page
    for table in tables:
        # Each crop narrows the page further, so several tables on one page are all
        # removed rather than only the last.
        body = body.outside_bbox(table.bbox)
    prose = _clean(body.extract_text() or "")

    rendered: list[str] = []
    for table in tables:
        markdown = _render_table(table.extract())
        if markdown:
            rendered.append(markdown)

    if not rendered:
        return prose, 0

    parts = [prose] if prose else []
    parts.extend(rendered)
    return "\n\n".join(parts), len(rendered)


def _render_table(rows: list[list[str | None]]) -> str:
    """A table as pipe-delimited rows, with the first row as its header.

    Kept close to markdown because that is what the corpus is already written in, so a
    retrieved table chunk reads the same way as a retrieved passage from a note.
    """
    cleaned = [[_cell(value) for value in row] for row in rows]
    cleaned = [row for row in cleaned if any(cell for cell in row)]
    if len(cleaned) < 2:
        # A single row is not a table; it is a line of text pdfplumber found ruling
        # around. Rendering it with a header separator would assert a structure that is
        # not there.
        return ""

    width = max(len(row) for row in cleaned)
    cleaned = [row + [""] * (width - len(row)) for row in cleaned]

    header, *body = cleaned
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * width]
    lines.extend("| " + " | ".join(row) + " |" for row in body)
    return "\n".join(lines)


def _cell(value: str | None) -> str:
    if value is None or EMPTY_CELL.match(value):
        return ""
    # A newline inside a cell would break the row it belongs to.
    return RUNS_OF_SPACES.sub(" ", value.replace("\n", " ")).strip()


def _clean(text: str) -> str:
    text = RUNS_OF_SPACES.sub(" ", text)
    text = RUNS_OF_BLANK_LINES.sub("\n\n", text)
    return "\n".join(line.rstrip() for line in text.splitlines()).strip()
