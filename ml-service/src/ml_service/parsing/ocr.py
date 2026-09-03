"""Reads the text off a page that carries none.

A PDF produced by a scanner is a sequence of images. `extract_text` returns an empty
string for such a page, and nothing about that is an error: the file is valid, the parse
succeeded, and the document is simply empty. Ingested, it becomes a document that indexes
to nothing and can never be retrieved — visible in a list, unreachable by search, with no
failure anywhere to explain why.

OCR is the fallback for exactly that case. It is not a better parser: it is slower by two
orders of magnitude and its output carries recognition errors that an extracted text layer
does not. So it runs only where there is nothing to extract, and the pages where it ran
are reported, because a passage that was recognised rather than read deserves to be
treated differently by whoever is looking at a wrong answer.

Tesseract is a system binary rather than a Python package. Where it is absent this module
reports that and the parser records the page as unreadable, which is the same information
the empty page carried before, said out loud.
"""

from __future__ import annotations

import logging
from functools import cache
from typing import Any

log = logging.getLogger(__name__)

# Below this many characters, a page is treated as having no text layer worth keeping.
# Not zero: a scanned page often carries a stray header, a page number stamped by the
# scanner, or a few characters of noise from an embedded logo, and a page holding only
# those is one OCR should still be given.
MIN_TEXT_LAYER_CHARS = 32

# What the page is rendered at before recognition. Tesseract is documented as wanting
# around 300 DPI for body text; below roughly 200 its accuracy falls off sharply, and
# above 300 the image grows quadratically for very little gain.
RENDER_DPI = 300


@cache
def available() -> bool:
    """Whether an OCR engine can actually be reached.

    Cached, because the answer cannot change while the process runs and the check costs a
    subprocess. Asked once per document rather than once per page.
    """
    try:
        import pytesseract

        pytesseract.get_tesseract_version()
        return True
    except Exception as exc:  # pragma: no cover - depends on the host
        log.warning("OCR is unavailable (%s); scanned pages will be reported as unreadable", exc)
        return False


def needs_ocr(text: str) -> bool:
    """Whether this page's extracted text is too thin to be the page's actual content."""
    return len(text.strip()) < MIN_TEXT_LAYER_CHARS


def read_page(page: Any) -> str:
    """Recognises the text on a page image, or returns empty if it cannot.

    Failures are swallowed deliberately. A page that will not render or will not
    recognise is one page of a document that may have many, and losing the whole upload
    to it would trade a partial answer for none.
    """
    if not available():
        return ""

    try:
        import pytesseract

        image = page.to_image(resolution=RENDER_DPI).original
        return str(pytesseract.image_to_string(image)).strip()
    except Exception as exc:  # pragma: no cover - depends on the page
        log.warning("could not read page %s by OCR: %s", getattr(page, "page_number", "?"), exc)
        return ""
