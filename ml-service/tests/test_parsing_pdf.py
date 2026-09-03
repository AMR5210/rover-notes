"""Extracting a PDF into text that can still be cited.

The PDFs are built here rather than committed. A fixture file would make these tests a
statement about one document somebody produced once, and the properties being checked —
that page ranges line up with printed page numbers, that a table keeps its columns — are
properties of arbitrary documents.

reportlab is not a dependency of this project, so the writer below emits the PDF byte
format directly. It is the smallest thing that produces a file pdfplumber will read: one
uncompressed content stream per page, text positioned with Tm, no font embedding.
"""

from __future__ import annotations

import io
from itertools import pairwise
from typing import ClassVar

import pytest
from pdfplumber.utils.exceptions import PdfminerException

from ml_service.parsing import parse_pdf
from tests.pdf_fixtures import make_pdf, ruled_table, two_tables_one_page


class TestText:
    def test_reads_the_text_of_every_page(self):
        pdf = make_pdf([["Retrieval fuses ranked lists."], ["Reranking is off by default."]])

        parsed = parse_pdf(pdf)

        assert "Retrieval fuses ranked lists." in parsed.text
        assert "Reranking is off by default." in parsed.text
        assert len(parsed.pages) == 2

    def test_pages_are_numbered_as_they_are_printed(self):
        pdf = make_pdf([["first"], ["second"], ["third"]])

        parsed = parse_pdf(pdf)

        assert [page.number for page in parsed.pages] == [1, 2, 3]

    def test_a_blank_page_still_takes_a_number(self):
        # The whole mapping is worthless if a page without text shifts the ones after it:
        # a citation would name a page whose printed number is one lower.
        pdf = make_pdf([["first"], [], ["third"]])

        parsed = parse_pdf(pdf)

        assert [page.number for page in parsed.pages] == [1, 2, 3]
        assert parsed.page_for(parsed.text.index("third")) == 3

    def test_stops_at_the_page_bound(self):
        # Parsing happens while a caller waits, so the page count must not decide how
        # long the request takes.
        pdf = make_pdf([["one"], ["two"], ["three"], ["four"]])

        parsed = parse_pdf(pdf, max_pages=2)

        assert [page.number for page in parsed.pages] == [1, 2]
        assert "three" not in parsed.text


class TestPageSpans:
    def test_a_character_offset_resolves_to_the_page_it_is_printed_on(self):
        pdf = make_pdf(
            [
                ["The read path has a budget of 150 milliseconds."],
                ["Reranking costs 951 milliseconds at p95."],
                ["The vector index is HNSW over cosine distance."],
            ]
        )

        parsed = parse_pdf(pdf)

        assert parsed.page_for(parsed.text.index("150 milliseconds")) == 1
        assert parsed.page_for(parsed.text.index("951 milliseconds")) == 2
        assert parsed.page_for(parsed.text.index("HNSW")) == 3

    def test_page_ranges_are_contiguous_and_cover_the_text(self):
        # Any gap between two ranges is a span that resolves to no page at all, which
        # from a reader's side is a citation that cannot be checked.
        pdf = make_pdf([["alpha"], ["beta"], ["gamma"]])

        parsed = parse_pdf(pdf)

        assert parsed.pages[0].char_start == 0
        for earlier, later in pairwise(parsed.pages):
            assert earlier.char_end == later.char_start
        assert parsed.pages[-1].char_end == len(parsed.text)

    def test_an_offset_past_the_end_belongs_to_no_page(self):
        pdf = make_pdf([["alpha"]])

        parsed = parse_pdf(pdf)

        assert parsed.page_for(len(parsed.text) + 10) is None


class TestTables:
    """A table read as running text loses which column a number is in."""

    GRID: ClassVar[list[list[str]]] = [
        ["path", "budget", "measured"],
        ["read", "150ms", "92ms"],
        ["ingest", "30s", "4.1s"],
    ]

    def test_a_ruled_table_is_found_and_rendered_with_its_columns(self):
        parsed = parse_pdf(ruled_table(self.GRID))

        assert parsed.table_count == 1
        assert "| path | budget | measured |" in parsed.text
        assert "| read | 150ms | 92ms |" in parsed.text
        assert "| ingest | 30s | 4.1s |" in parsed.text

    def test_a_value_stays_on_the_row_that_labels_it(self):
        # The property a question depends on: "what is the budget for the read path" is
        # answerable only if 150ms is still attached to read rather than to ingest.
        parsed = parse_pdf(ruled_table(self.GRID))

        row = next(line for line in parsed.text.splitlines() if line.startswith("| read "))
        assert "150ms" in row
        assert "30s" not in row

    def test_a_table_is_reported_on_the_page_it_appears_on(self):
        # A citation into a table has to name the page the table is printed on, which is
        # the whole reason the count is held per page rather than per document.
        blank = make_pdf([["A page with no table on it."]])
        first = parse_pdf(blank)
        assert first.table_count == 0

        parsed = parse_pdf(ruled_table(self.GRID, page_lines=["Timeouts by path."]))
        assert [page.tables for page in parsed.pages] == [1]

    def test_a_table_is_not_indexed_twice(self):
        # extract_text() reads a table's cells as ordinary text, so extracting the page
        # whole and then extracting its tables yields both copies. Indexed together they
        # embed the same fact twice, and the reading-order copy has lost the column that
        # gave each number its meaning. Found by running the endpoint and reading what
        # came back, not by a test — hence this one.
        parsed = parse_pdf(ruled_table(self.GRID, page_lines=["Timeouts by path."]))

        assert parsed.text.count("150ms") == 1
        assert parsed.text.count("ingest") == 1

    def test_several_tables_on_one_page_are_all_removed_from_the_prose(self):
        # Each crop narrows the page further; cropping only the last table would leave
        # every earlier one duplicated.
        pdf = two_tables_one_page()

        parsed = parse_pdf(pdf)

        assert parsed.pages[0].tables == 2
        assert parsed.text.count("150ms") == 1
        assert parsed.text.count("42MB") == 1

    def test_the_prose_on_the_page_survives_alongside_the_table(self):
        parsed = parse_pdf(ruled_table(self.GRID, page_lines=["Timeouts by path."]))

        assert "Timeouts by path." in parsed.text
        assert "| read | 150ms | 92ms |" in parsed.text

    def test_a_table_span_resolves_to_its_page(self):
        parsed = parse_pdf(ruled_table(self.GRID, page_lines=["Timeouts by path."]))

        assert parsed.page_for(parsed.text.index("150ms")) == 1


class TestRendering:
    def test_renders_rows_with_a_header_separator(self):
        from ml_service.parsing.pdf import _render_table

        rendered = _render_table([["path", "budget"], ["read", "150ms"], ["ingest", "30s"]])

        assert rendered.splitlines()[0] == "| path | budget |"
        assert rendered.splitlines()[1] == "|---|---|"
        assert "| read | 150ms |" in rendered

    def test_a_single_row_is_not_rendered_as_a_table(self):
        # Asserting a header structure that is not there would tell the embedding the
        # first row labels the ones below it, when there are none.
        from ml_service.parsing.pdf import _render_table

        assert _render_table([["just a line of text"]]) == ""

    def test_a_newline_inside_a_cell_does_not_break_its_row(self):
        from ml_service.parsing.pdf import _render_table

        rendered = _render_table([["path", "budget"], ["read\npath", "150ms"]])

        assert "| read path | 150ms |" in rendered
        assert len(rendered.splitlines()) == 3

    def test_short_rows_are_padded_to_the_table_width(self):
        from ml_service.parsing.pdf import _render_table

        rendered = _render_table([["a", "b", "c"], ["1"]])

        assert "| 1 |  |  |" in rendered


class TestMalformedInput:
    def test_a_file_that_is_not_a_pdf_is_refused(self):
        # Named rather than blind: a parser that returned an empty document for a file it
        # could not read would hand the indexer a document with no text and no error, and
        # `pytest.raises(Exception)` would pass on the TypeError that is not the point.
        with pytest.raises(PdfminerException):
            parse_pdf(io.BytesIO(b"this is not a PDF at all"))

    def test_an_empty_file_is_refused_rather_than_parsed_as_empty(self):
        with pytest.raises(PdfminerException):
            parse_pdf(io.BytesIO(b""))
