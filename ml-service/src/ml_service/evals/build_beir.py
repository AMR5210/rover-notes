"""Builds an eval suite from a BEIR benchmark dataset.

The hand-written corpus in ``evals/corpus`` is 42 documents and 59 chunks. At that size
the query planner does not choose the vector index, chunk boundaries rarely change which
document wins, and most measured differences come back with a confidence interval that
spans zero — the collection cannot resolve them. This adds a second collection large
enough that it can.

BEIR is used rather than a generated corpus for two reasons. Its relevance judgements
were made by people against a task, not by the system being measured, so a result here
is not circular. And its numbers are published for models including the one this project
embeds with, so a score is checkable against something outside this repository rather
than only against its own history.

The data is downloaded on demand rather than committed. Each dataset is pinned by
SHA-256, so a silently changed archive fails the build instead of quietly moving a
baseline.

    uv run python -m ml_service.evals.build_beir scifact

Licensing, recorded because the data is third-party: SciFact's claims and annotations are
CC BY 4.0 and its abstracts ODC-By 1.0 (allenai/scifact, LICENSE.md); BEIR packages them
unchanged. Nothing downloaded here is redistributed by this repository.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import time
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[4]
EVALS_DIR = REPO_ROOT / "evals"
CACHE_DIR = EVALS_DIR / ".cache"

BEIR_BASE = "https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets"

# The archive comes from a university host this project does not control, and a scheduled
# job that fails on one unreachable host reports nothing about retrieval. Measured: the
# run of 2026-08-13 spent 2m23s in a connect that timed out and never reached the gate,
# on a `urlopen` with no timeout and no second attempt. Three attempts with a growing
# pause covers a host that is briefly unreachable; a host that is down stays down, and
# the job should say so rather than keep trying.
DOWNLOAD_ATTEMPTS = 3

# Per attempt, and on the connect as well as the read. Without one, `urlopen` waits on the
# operating system's default, which is where those 2m23s went.
DOWNLOAD_TIMEOUT_SECONDS = 60

# A document identifier becomes a filename and the slug the golden set matches on, so
# anything that could escape the corpus directory or collide after normalisation is
# rejected rather than sanitised. Sanitising two distinct identifiers into one silently
# merges two documents.
SAFE_ID = re.compile(r"^[A-Za-z0-9._-]{1,80}$")


@dataclass(frozen=True)
class Dataset:
    """A BEIR dataset, pinned to the archive that produced the committed baseline."""

    name: str
    sha256: str
    documents: int
    queries: int
    judgements: int

    @property
    def url(self) -> str:
        return f"{BEIR_BASE}/{self.name}.zip"


DATASETS = {
    # 5,183 abstracts, 300 test claims, 339 judgements. Chosen over the larger BEIR sets
    # because it crosses the size where the HNSW index is used — roughly 7,300 chunks at
    # the committed window — while still ingesting in minutes on one machine.
    "scifact": Dataset(
        name="scifact",
        sha256="536e14446a0ba56ed1398ab1055f39fe852686ecad24a6306c80c490fa8e0165",
        documents=5183,
        queries=300,
        judgements=339,
    ),
}


def fetch_archive(url: str, destination: Path) -> None:
    """Downloads to ``destination``, retrying a host that is briefly unreachable.

    A partial file is removed between attempts rather than resumed. The archive is small
    enough that starting again costs little, and appending to a truncated file would
    produce bytes that are neither the download nor a clean failure — the digest check
    would then report an archive that had changed upstream when nothing had.
    """
    for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
        try:
            with (
                urllib.request.urlopen(url, timeout=DOWNLOAD_TIMEOUT_SECONDS) as response,
                destination.open("wb") as out,
            ):
                shutil.copyfileobj(response, out)
            return
        except OSError as cause:
            # URLError and TimeoutError are both OSError, as is a connection dropped
            # mid-body. All three mean the same thing here: try again, or give up.
            destination.unlink(missing_ok=True)
            if attempt == DOWNLOAD_ATTEMPTS:
                raise SystemExit(
                    f"could not download {url} after {DOWNLOAD_ATTEMPTS} attempts: "
                    f"{cause}. The host is not this project's; the suite is rebuilt from "
                    "this archive on every run, so nothing was scored."
                ) from cause
            pause = 2**attempt
            print(f"  {cause} — retrying in {pause}s ({attempt} of {DOWNLOAD_ATTEMPTS})")
            time.sleep(pause)


def download(dataset: Dataset) -> Path:
    """Fetches the archive into the cache and checks it against the pinned digest."""
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    archive = CACHE_DIR / f"{dataset.name}.zip"

    if not archive.exists():
        print(f"  downloading {dataset.url}")
        fetch_archive(dataset.url, archive)

    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != dataset.sha256:
        archive.unlink()
        raise SystemExit(
            f"{dataset.name}.zip has digest {digest}, expected {dataset.sha256}. Either "
            "the archive changed upstream since the baseline was recorded, or the "
            "download did not complete; a score from it would not be comparable either "
            "way. Removed it — run again to re-download, and update the pin deliberately "
            "if the new archive is the one intended."
        )

    extracted = CACHE_DIR / dataset.name
    if not extracted.exists():
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(CACHE_DIR)
    return extracted


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def read_qrels(path: Path) -> dict[str, set[str]]:
    """Reads a BEIR qrels TSV into query id -> relevant document ids.

    Judgements are binary in the datasets used here. A graded scale would need the score
    carried through to nDCG's gain, which the harness does not currently model, so a
    dataset with graded relevance should not be added without that change.
    """
    relevant: dict[str, set[str]] = {}
    for lineno, line in enumerate(path.read_text().splitlines(), start=1):
        if lineno == 1 or not line.strip():
            continue
        query_id, doc_id, score = line.split("\t")
        if int(score) <= 0:
            continue
        relevant.setdefault(query_id, set()).add(doc_id)
    return relevant


def build(dataset: Dataset) -> tuple[Path, Path]:
    source = download(dataset)

    corpus_dir = EVALS_DIR / f"corpus-{dataset.name}"
    golden_dir = EVALS_DIR / f"golden-{dataset.name}"
    for directory in (corpus_dir, golden_dir):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)

    written: set[str] = set()
    for record in read_jsonl(source / "corpus.jsonl"):
        doc_id = record["_id"]
        if not SAFE_ID.match(doc_id):
            raise SystemExit(f"document id {doc_id!r} is not usable as a corpus slug")
        # Title and body are stored as one document, which is how BEIR is scored: the
        # title carries terms the abstract often assumes.
        title = record.get("title", "").strip()
        body = f"# {title}\n\n{record['text'].strip()}\n" if title else record["text"].strip()
        (corpus_dir / f"{doc_id}.md").write_text(body)
        written.add(doc_id)

    qrels = read_qrels(source / "qrels" / "test.tsv")
    texts = {record["_id"]: record["text"] for record in read_jsonl(source / "queries.jsonl")}

    lines: list[str] = []
    judgements = 0
    for query_id in sorted(qrels, key=lambda q: (len(q), q)):
        missing = qrels[query_id] - written
        if missing:
            raise SystemExit(f"query {query_id} is judged against absent documents: {missing}")
        # No `contains` phrase: BEIR judges relevance per document, so any chunk of a
        # judged document counts. This suite therefore measures document retrieval, where
        # the hand-written suite measures whether the retrieved passage itself answers.
        relevant = [{"doc": doc_id} for doc_id in sorted(qrels[query_id])]
        judgements += len(relevant)
        lines.append(
            json.dumps(
                {
                    "id": f"{dataset.name}-{query_id}",
                    "query": texts[query_id],
                    "relevant": relevant,
                }
            )
        )
    (golden_dir / "test.jsonl").write_text("\n".join(lines) + "\n")

    # The pinned counts are what the committed baseline was measured over. A build that
    # produces a different number is scoring a different collection.
    actual = (len(written), len(lines), judgements)
    expected = (dataset.documents, dataset.queries, dataset.judgements)
    if actual != expected:
        raise SystemExit(f"built {actual} (documents, queries, judgements), expected {expected}")

    print(f"  {len(written)} documents -> {corpus_dir.relative_to(REPO_ROOT)}")
    print(f"  {len(lines)} queries, {judgements} judgements -> {golden_dir.relative_to(REPO_ROOT)}")
    return corpus_dir, golden_dir


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build an eval suite from a BEIR dataset")
    parser.add_argument("dataset", choices=sorted(DATASETS), help="which dataset to build")
    args = parser.parse_args(argv)

    build(DATASETS[args.dataset])
    return 0


if __name__ == "__main__":
    sys.exit(main())
