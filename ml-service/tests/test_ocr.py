"""Reading a PDF that carries no text layer.

A scanned document is a sequence of images. Parsing one succeeds, extracts nothing, and
reports no error — so it becomes a document that indexes to nothing and can never be
retrieved, with nothing anywhere to explain why. These cover the fallback that closes
that, and the two things it must not do: run where there is text to read, or take the
whole upload down when it cannot run at all.

The scanned fixtures are built by rendering text to an image, not by stripping a text
layer from a normal PDF. A file that merely hides its text still has one, and would pass
against the behaviour being replaced.
"""

from __future__ import annotations

import pytest

from ml_service.parsing import ocr
from ml_service.parsing.pdf import parse_pdf
from tests.pdf_fixtures import make_pdf, scanned

pytestmark = pytest.mark.skipif(
    not ocr.available(), reason="tesseract is not installed on this host"
)


RUNBOOK = ["Incident Response Runbook", "", "The read path budget is 150 milliseconds."]
ESCALATION = ["Escalation", "", "Page the on-call engineer after ten minutes."]


class TestScannedDocuments:
    def test_text_is_recovered_from_a_page_with_no_text_layer(self) -> None:
        parsed = parse_pdf(scanned([RUNBOOK]))

        assert "Incident Response Runbook" in parsed.text
        assert "150 milliseconds" in parsed.text

    def test_the_page_is_reported_as_recognised_rather_than_read(self) -> None:
        # Recognised text carries errors an extracted layer does not, so a wrong answer
        # traced back to this page should be weighed differently.
        parsed = parse_pdf(scanned([RUNBOOK]))

        assert parsed.ocr_pages == [1]

    def test_page_ranges_still_locate_a_span(self) -> None:
        # The whole point of parsing this way. Text arriving by a different route must
        # not break the mapping a citation is resolved through.
        parsed = parse_pdf(scanned([RUNBOOK, ESCALATION]))

        assert parsed.page_for(parsed.text.index("Escalation")) == 2
        assert parsed.page_for(parsed.text.index("Incident")) == 1

    def test_every_page_of_a_scanned_document_is_read(self) -> None:
        parsed = parse_pdf(scanned([RUNBOOK, ESCALATION]))

        assert parsed.ocr_pages == [1, 2]
        assert parsed.empty_pages == []


class TestWhenOcrShouldNotRun:
    def test_a_page_with_a_text_layer_is_not_recognised(self, monkeypatch) -> None:
        # OCR is slower by two orders of magnitude and less accurate. Running it where
        # there is text to read would cost both for nothing.
        def refuse(page: object) -> str:
            raise AssertionError("a page with a text layer should not be sent to OCR")

        monkeypatch.setattr(ocr, "read_page", refuse)

        parsed = parse_pdf(make_pdf([["The read path budget is 150 milliseconds. " * 3]]))

        assert parsed.ocr_pages == []
        assert "150 milliseconds" in parsed.text

    def test_a_thin_page_is_still_offered_to_ocr(self) -> None:
        # A scanner often stamps a page number or leaves a few characters of noise from a
        # logo. A page holding only that has no usable text layer, and a strict "is it
        # empty" check would skip exactly the pages that need reading.
        assert ocr.needs_ocr("")
        assert ocr.needs_ocr("  \n ")
        assert ocr.needs_ocr("Page 4 of 12")
        assert not ocr.needs_ocr("The read path budget is 150 milliseconds.")


class TestWhenOcrIsUnavailable:
    def test_parsing_still_succeeds_without_an_engine(self, monkeypatch) -> None:
        # Tesseract is a system binary, so a deployment can be missing it. That should
        # cost the scanned pages, not the upload.
        monkeypatch.setattr(ocr, "available", lambda: False)

        parsed = parse_pdf(scanned([RUNBOOK]))

        assert parsed.text.strip() == ""
        assert parsed.ocr_pages == []

    def test_the_document_reports_which_pages_produced_nothing(self, monkeypatch) -> None:
        # The information the empty page always carried, said out loud. A caller can see
        # the document will not be retrievable instead of discovering it from a search
        # that comes back empty.
        monkeypatch.setattr(ocr, "available", lambda: False)

        parsed = parse_pdf(scanned([RUNBOOK, ESCALATION]))

        assert parsed.empty_pages == [1, 2]

    def test_one_unreadable_page_does_not_cost_the_others(self, monkeypatch) -> None:
        # read_page returns empty for a page it cannot handle rather than raising. The
        # document that page belongs to may have many good ones, and losing the upload
        # to it would trade a partial answer for none.
        real = ocr.read_page

        def fail_on_the_first(page: object) -> str:
            return "" if getattr(page, "page_number", 0) == 1 else real(page)

        monkeypatch.setattr(ocr, "read_page", fail_on_the_first)

        parsed = parse_pdf(scanned([RUNBOOK, ESCALATION]))

        assert parsed.empty_pages == [1]
        assert parsed.ocr_pages == [2]
        assert "on-call engineer" in parsed.text


class TestOcrFailureIsContained:
    def test_a_rendering_failure_is_swallowed_by_read_page(self, monkeypatch) -> None:
        # read_page is where the containment lives, so it is tested directly rather than
        # through the parser.
        class Unrenderable:
            page_number = 3

            def to_image(self, resolution: int) -> object:
                raise RuntimeError("could not render")

        assert ocr.read_page(Unrenderable()) == ""
