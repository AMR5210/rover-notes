"""Downloading the pinned BEIR archive.

The suite is rebuilt from this archive on every scheduled run, so a host that is briefly
unreachable used to mean the whole job reported nothing about retrieval — which is what
happened on 2026-08-13. These cover the retry that replaced that, and the two things it
must not do: retry forever, or re-download something already in the cache.

The digest check itself is covered through :func:`download`, because the interesting case
is what a failed check leaves behind rather than the comparison.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

import pytest

from ml_service.evals import build_beir


class FakeResponse:
    """Enough of an HTTP response for ``shutil.copyfileobj`` and a ``with`` block."""

    def __init__(self, body: bytes) -> None:
        self._body = body
        self._read = False

    def read(self, size: int = -1) -> bytes:
        if self._read:
            return b""
        self._read = True
        return self._body

    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *exc: object) -> None:
        return None


@pytest.fixture
def no_waiting(monkeypatch: pytest.MonkeyPatch) -> list[int]:
    """Records the pauses instead of taking them, so the retries cost no wall time."""
    pauses: list[int] = []
    monkeypatch.setattr(build_beir.time, "sleep", pauses.append)
    return pauses


class TestFetchArchive:
    def test_writes_the_body_to_the_destination(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        monkeypatch.setattr(
            build_beir.urllib.request, "urlopen", lambda *a, **k: FakeResponse(b"archive")
        )

        build_beir.fetch_archive("https://example.invalid/scifact.zip", tmp_path / "a.zip")

        assert (tmp_path / "a.zip").read_bytes() == b"archive"

    def test_asks_for_a_timeout_rather_than_waiting_on_the_default(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        # The whole of the 2026-08-13 failure: a connect with no timeout sat for 2m23s.
        seen: dict[str, Any] = {}

        def urlopen(url: str, timeout: float | None = None) -> FakeResponse:
            seen["timeout"] = timeout
            return FakeResponse(b"archive")

        monkeypatch.setattr(build_beir.urllib.request, "urlopen", urlopen)

        build_beir.fetch_archive("https://example.invalid/scifact.zip", tmp_path / "a.zip")

        assert seen["timeout"] == build_beir.DOWNLOAD_TIMEOUT_SECONDS

    def test_retries_a_host_that_is_briefly_unreachable(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, no_waiting: list[int]
    ) -> None:
        attempts = iter([TimeoutError(110, "Connection timed out"), None])

        def urlopen(url: str, timeout: float | None = None) -> FakeResponse:
            failure = next(attempts)
            if failure is not None:
                raise failure
            return FakeResponse(b"archive")

        monkeypatch.setattr(build_beir.urllib.request, "urlopen", urlopen)

        build_beir.fetch_archive("https://example.invalid/scifact.zip", tmp_path / "a.zip")

        assert (tmp_path / "a.zip").read_bytes() == b"archive"
        assert no_waiting == [2], "one pause, before the second attempt"

    def test_gives_up_rather_than_retrying_a_host_that_is_down(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, no_waiting: list[int]
    ) -> None:
        calls = 0

        def urlopen(url: str, timeout: float | None = None) -> FakeResponse:
            nonlocal calls
            calls += 1
            raise TimeoutError(110, "Connection timed out")

        monkeypatch.setattr(build_beir.urllib.request, "urlopen", urlopen)

        with pytest.raises(SystemExit) as exit_info:
            build_beir.fetch_archive("https://example.invalid/scifact.zip", tmp_path / "a.zip")

        assert calls == build_beir.DOWNLOAD_ATTEMPTS
        # The message names the URL, because the reader has to know the failure is a host
        # this project does not control rather than a retrieval regression.
        assert "example.invalid" in str(exit_info.value)

    def test_leaves_no_partial_file_behind_when_it_gives_up(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, no_waiting: list[int]
    ) -> None:
        # A truncated file that survived would be found by the next run, fail the digest
        # check, and be reported as an archive that changed upstream — which would send
        # somebody to look at BEIR for a fault in the network here.
        def urlopen(url: str, timeout: float | None = None) -> FakeResponse:
            raise ConnectionResetError("dropped mid-body")

        monkeypatch.setattr(build_beir.urllib.request, "urlopen", urlopen)
        archive = tmp_path / "a.zip"
        archive.write_bytes(b"the tail of an earlier attempt")

        with pytest.raises(SystemExit):
            build_beir.fetch_archive("https://example.invalid/scifact.zip", archive)

        assert not archive.exists()


class TestDownload:
    def test_does_not_download_an_archive_already_in_the_cache(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        # What makes the workflow's cache worth having: a hit costs no request at all.
        body = b"a cached archive"
        dataset = build_beir.Dataset(
            name="scifact",
            sha256=hashlib.sha256(body).hexdigest(),
            documents=1,
            queries=1,
            judgements=1,
        )
        monkeypatch.setattr(build_beir, "CACHE_DIR", tmp_path)
        (tmp_path / "scifact.zip").write_bytes(body)
        (tmp_path / "scifact").mkdir()

        def refuse(*args: object, **kwargs: object) -> None:
            raise AssertionError("the cached archive should not be downloaded again")

        monkeypatch.setattr(build_beir, "fetch_archive", refuse)

        assert build_beir.download(dataset) == tmp_path / "scifact"

    def test_removes_an_archive_whose_digest_does_not_match_the_pin(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        dataset = build_beir.Dataset(
            name="scifact",
            sha256="0" * 64,
            documents=1,
            queries=1,
            judgements=1,
        )
        monkeypatch.setattr(build_beir, "CACHE_DIR", tmp_path)
        (tmp_path / "scifact.zip").write_bytes(b"not what was pinned")

        with pytest.raises(SystemExit) as exit_info:
            build_beir.download(dataset)

        # Kept, the file would be found by the next run and fail the same check without
        # ever being re-fetched.
        assert not (tmp_path / "scifact.zip").exists()
        assert "did not complete" in str(exit_info.value)
