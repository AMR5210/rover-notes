"""Builds the PDFs the parsing tests read.

Fixtures are generated rather than committed: the properties under test — that page
ranges line up with printed page numbers, that a table keeps its columns — are properties
of arbitrary documents, and a checked-in file would make them a statement about one
document somebody produced once.

reportlab is not a dependency here, so this emits the PDF byte format directly. It is the
smallest thing pdfplumber will read: one uncompressed content stream per page, text
positioned with Tm, no font embedding, and stroked line segments for table rules.
"""

from __future__ import annotations

import io


def make_pdf(
    pages: list[list[str]],
    rules: list[list[tuple[float, float, float, float]]] | None = None,
    cells: list[list[tuple[float, float, str]]] | None = None,
) -> io.BytesIO:
    """A PDF with the given lines on each page, in Helvetica at a fixed leading.

    ``rules`` draws stroked line segments per page, which is what pdfplumber's default
    table strategy detects a table from. Without them a grid of words is just text that
    happens to line up, and a test asserting table extraction against one passes whether
    or not any table was found.

    ``cells`` places text at an explicit position rather than in the flowed column, which
    is what puts a word inside a ruled box. Helvetica is proportional, so padding a line
    with spaces does not put it where the grid is.
    """
    objects: list[bytes] = []

    def add(body: bytes) -> int:
        objects.append(body)
        return len(objects)

    font = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

    page_ids: list[int] = []
    content_ids: list[int] = []
    for index, lines in enumerate(pages):
        drawn: list[bytes] = []
        for x1, y1, x2, y2 in rules[index] if rules and index < len(rules) else []:
            drawn.append(f"{x1} {y1} m {x2} {y2} l S".encode())
        drawn.extend([b"BT", b"/F1 11 Tf", b"1 0 0 1 72 720 Tm", b"13 TL"])
        for line in lines:
            escaped = line.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")
            drawn.append(b"(" + escaped.encode("latin-1", "replace") + b") Tj T*")
        drawn.append(b"ET")
        for x, y, text in cells[index] if cells and index < len(cells) else []:
            escaped = text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")
            drawn.extend(
                [
                    b"BT",
                    b"/F1 9 Tf",
                    f"1 0 0 1 {x} {y} Tm".encode(),
                    b"(" + escaped.encode("latin-1", "replace") + b") Tj",
                    b"ET",
                ]
            )
        stream = b"\n".join(drawn)
        content_ids.append(
            add(
                b"<< /Length "
                + str(len(stream)).encode()
                + b" >>\nstream\n"
                + stream
                + b"\nendstream"
            )
        )
        page_ids.append(0)  # filled once the pages node has an id

    pages_id = len(objects) + len(pages) + 1
    for index, content in enumerate(content_ids):
        page_ids[index] = add(
            b"<< /Type /Page /Parent "
            + str(pages_id).encode()
            + b" /MediaBox [0 0 612 792]"
            + b" /Resources << /Font << /F1 "
            + str(font).encode()
            + b" 0 R >> >>"
            + b" /Contents "
            + str(content).encode()
            + b" 0 R >>"
        )

    kids = b" ".join(str(i).encode() + b" 0 R" for i in page_ids)
    add(b"<< /Type /Pages /Kids [" + kids + b"] /Count " + str(len(pages)).encode() + b" >>")
    catalog = add(b"<< /Type /Catalog /Pages " + str(pages_id).encode() + b" 0 R >>")

    out = io.BytesIO()
    out.write(b"%PDF-1.4\n")
    offsets = [0]
    for number, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(str(number).encode() + b" 0 obj\n" + body + b"\nendobj\n")

    start_xref = out.tell()
    out.write(b"xref\n0 " + str(len(objects) + 1).encode() + b"\n")
    out.write(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        out.write(f"{offset:010d} 00000 n \n".encode())
    out.write(
        b"trailer\n<< /Size "
        + str(len(objects) + 1).encode()
        + b" /Root "
        + str(catalog).encode()
        + b" 0 R >>\n"
    )
    out.write(b"startxref\n" + str(start_xref).encode() + b"\n%%EOF\n")
    out.seek(0)
    return out


def ruled_table(grid: list[list[str]], page_lines: list[str] | None = None) -> io.BytesIO:
    """A PDF holding a real ruled table, optionally under a paragraph of prose.

    The rules are what pdfplumber detects; the cell text is positioned to land inside
    them. Both are necessary — an earlier version of these tests aligned words with
    spaces and no rules, pdfplumber found no table at all, and the assertions passed
    against plain text that merely happened to contain the right words.
    """
    x0, y_top, width, height = 72, 640, 120, 22
    rows, cols = len(grid), max(len(row) for row in grid)
    rules = [
        (x0 + c * width, y_top, x0 + c * width, y_top - rows * height) for c in range(cols + 1)
    ] + [(x0, y_top - r * height, x0 + cols * width, y_top - r * height) for r in range(rows + 1)]
    cells = [
        (x0 + c * width + 5, y_top - r * height - 15, value)
        for r, row in enumerate(grid)
        for c, value in enumerate(row)
    ]
    return make_pdf([page_lines or []], rules=[rules], cells=[cells])


def two_tables_one_page() -> io.BytesIO:
    """Two ruled tables on one page, for the case where cropping must repeat."""
    width, height = 120, 22

    def grid(x0: float, y_top: float, rows: list[list[str]]):
        cols = max(len(row) for row in rows)
        rules = [
            (x0 + c * width, y_top, x0 + c * width, y_top - len(rows) * height)
            for c in range(cols + 1)
        ] + [
            (x0, y_top - r * height, x0 + cols * width, y_top - r * height)
            for r in range(len(rows) + 1)
        ]
        cells = [
            (x0 + c * width + 5, y_top - r * height - 15, value)
            for r, row in enumerate(rows)
            for c, value in enumerate(row)
        ]
        return rules, cells

    top_rules, top_cells = grid(72, 640, [["path", "budget"], ["read", "150ms"]])
    bottom_rules, bottom_cells = grid(72, 500, [["store", "size"], ["index", "42MB"]])
    return make_pdf(
        [["Two tables."]],
        rules=[top_rules + bottom_rules],
        cells=[top_cells + bottom_cells],
    )


def scanned(pages: list[list[str]], dpi: float = 150.0) -> io.BytesIO:
    """A PDF with no text layer at all: each page is an image of its lines.

    What a scanner produces, and the case `extract_text` returns an empty string for
    without reporting anything wrong. Built by rendering text to an image rather than by
    stripping a text layer, because a PDF that merely hides its text still has one and
    would not exercise the fallback.
    """
    from PIL import Image, ImageDraw, ImageFont

    def render(lines: list[str]) -> Image.Image:
        image = Image.new("RGB", (1240, 1754), "white")
        draw = ImageDraw.Draw(image)
        try:
            font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 42)
        except OSError:  # pragma: no cover - depends on the host's fonts
            font = ImageFont.load_default()
        y = 120
        for line in lines:
            draw.text((100, y), line, fill="black", font=font)
            y += 70
        return image

    images = [render(lines) for lines in pages]
    buffer = io.BytesIO()
    images[0].save(
        buffer,
        format="PDF",
        save_all=True,
        append_images=images[1:],
        resolution=dpi,
    )
    buffer.seek(0)
    return buffer
