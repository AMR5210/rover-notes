"""The parse endpoint, driven over HTTP.

`test_parsing_pdf.py` covers the extraction itself. These cover what only appears once
it is an endpoint: that an upload is bounded, that a file which is not a PDF is the
caller's error rather than the service's, and that the page ranges survive serialization
— a citation resolves by comparing offsets, so an off-by-one in the response shape breaks
it just as thoroughly as an off-by-one in the parser.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from ml_service.config import settings
from ml_service.main import app
from tests.pdf_fixtures import make_pdf, ruled_table


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def upload(client: TestClient, body: bytes, name: str = "notes.pdf"):
    return client.post("/parse/pdf", files={"file": (name, body, "application/pdf")})


class TestParsing:
    def test_returns_the_text_and_a_range_for_every_page(self, client: TestClient):
        pdf = make_pdf([["Retrieval fuses ranked lists."], ["Reranking is off by default."]])

        response = upload(client, pdf.getvalue())

        assert response.status_code == 200
        body = response.json()
        assert "Retrieval fuses ranked lists." in body["text"]
        assert [page["number"] for page in body["pages"]] == [1, 2]

    def test_page_ranges_address_the_text_that_came_back(self, client: TestClient):
        # The contract the Java side depends on: slicing the returned text by a returned
        # range gives that page. Asserted against the response rather than the parser,
        # because this is where an encoding difference would show up.
        pdf = make_pdf([["alpha one"], ["beta two"], ["gamma three"]])

        body = upload(client, pdf.getvalue()).json()

        text = body["text"]
        first, second, third = body["pages"]
        assert "alpha" in text[first["char_start"] : first["char_end"]]
        assert "beta" in text[second["char_start"] : second["char_end"]]
        assert "gamma" in text[third["char_start"] : third["char_end"]]

    def test_reports_tables_per_page(self, client: TestClient):
        pdf = ruled_table([["path", "budget"], ["read", "150ms"]], page_lines=["Timeouts."])

        body = upload(client, pdf.getvalue()).json()

        assert body["table_count"] == 1
        assert body["pages"][0]["tables"] == 1
        assert "| read | 150ms |" in body["text"]

    def test_a_document_within_the_page_bound_is_not_marked_truncated(self, client: TestClient):
        pdf = make_pdf([["one"], ["two"]])

        assert upload(client, pdf.getvalue()).json()["truncated"] is False


class TestLimits:
    def test_an_upload_over_the_size_bound_is_refused(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ):
        # 413 rather than a parse attempt: the point is to refuse before spending CPU on
        # it, so the bound has to be checked on the bytes rather than on the page count.
        monkeypatch.setattr(settings, "max_upload_bytes", 512)
        pdf = make_pdf([["a page of text"] * 200])

        response = upload(client, pdf.getvalue())

        assert response.status_code == 413

    def test_an_upload_at_the_size_bound_is_accepted(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ):
        # The boundary itself, because reading one byte past the limit to detect an
        # overflow is exactly the kind of check that rejects a file of the legal size.
        pdf = make_pdf([["small"]]).getvalue()
        monkeypatch.setattr(settings, "max_upload_bytes", len(pdf))

        assert upload(client, pdf).status_code == 200

    def test_a_page_count_over_the_bound_is_truncated_and_says_so(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ):
        monkeypatch.setattr(settings, "max_pdf_pages", 2)
        pdf = make_pdf([["one"], ["two"], ["three"], ["four"]])

        body = upload(client, pdf.getvalue()).json()

        assert [page["number"] for page in body["pages"]] == [1, 2]
        assert body["truncated"] is True
        assert "three" not in body["text"]


class TestRejections:
    def test_a_file_that_is_not_a_pdf_is_the_callers_error(self, client: TestClient):
        # 422, not 500: the service did its job and the file was not what it claimed.
        response = upload(client, b"this is plain text, not a PDF")

        assert response.status_code == 422
        assert "could not read as PDF" in response.json()["detail"]

    def test_an_empty_upload_is_refused(self, client: TestClient):
        response = upload(client, b"")

        assert response.status_code == 400

    def test_a_request_with_no_file_is_refused(self, client: TestClient):
        assert client.post("/parse/pdf").status_code == 422


class TestBlocking:
    def test_parsing_does_not_block_the_event_loop(self, client: TestClient):
        # Parsing is CPU-bound and the service is async. Run inline it would stall every
        # other request on the worker for the duration, which for a large PDF is seconds.
        # Asserted structurally: the handler hands the work to a thread.
        import inspect

        from ml_service import main

        source = inspect.getsource(main.parse_pdf_upload)
        assert "asyncio.to_thread" in source


class TestHealthStillWorks:
    def test_the_service_still_answers_health(self, client: TestClient):
        # The parsing import is optional-extra territory; if it were missing the app
        # would fail to import and every endpoint would go with it.
        assert client.get("/health").json() == {"status": "ok"}
