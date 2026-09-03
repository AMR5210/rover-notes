"""Scores the generated answer, not just the passages retrieved for it.

Retrieval here is measured against two collections and gated in CI. Generation had no
metric at all: no faithfulness number, no check that a bracketed citation points at
anything, no abstention rate over the queries the corpus cannot answer. This adds those.

Three properties are measured, and they fail in different ways:

**Citation integrity** — checked without a model. A ``[4]`` in an answer that was given
three sources is a fabricated reference, and a citation whose character span falls outside
its document cannot be highlighted. Both are defects regardless of how good the prose is,
and both are decidable from the response alone.

**Faithfulness and citation precision** — judged. The answer is decomposed into claims,
and each claim is checked twice: against every source the model saw, and against the
sources that claim itself cites. The first is faithfulness in the usual sense. The second
is stricter and catches the more common failure, where a true statement is attached to a
source that does not support it — a citation a reader would follow and find nothing.

**Abstention** — judged, over the eight golden queries the corpus genuinely cannot answer.
The correct behaviour is to decline. Answering anyway is the failure — specifically,
asserting something the sources do not support. Declining and then saying what the corpus
*does* cover, to explain the gap, is correct and is not counted against it.

Ragas is named in the roadmap for this and is not used. Its faithfulness metric is the
same decomposition implemented here, and writing the prompts out means the exact question
put to the judge is reviewable in this file and stable across releases of a dependency.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx

from ml_service.evals.dataset import GoldenQuery, load_golden_set
from ml_service.evals.judge import Judge, default_judge, parse_json

REPO_ROOT = Path(__file__).resolve().parents[4]
EVALS_DIR = REPO_ROOT / "evals"
GOLDEN_DIR = EVALS_DIR / "golden"
BASELINE = EVALS_DIR / "baseline-generation.json"
RUNS_DIR = EVALS_DIR / "runs"

# Bracketed references the answer makes: [1], [2, 3], [4][5]. Digits and separators only,
# so a markdown link's [label](url) is not read as a citation.
CITATION = re.compile(r"\[(\d+(?:\s*,\s*\d+)*)\]")

# Sentence boundaries, approximately: a terminator followed by whitespace and a capital or
# a digit. Used only to report how much of an answer carries a citation, so an occasional
# split inside an abbreviation moves the number slightly rather than changing a verdict.
SENTENCE_BREAK = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9])")

CLAIM_PROMPT = """\
You are checking whether an answer is supported by the sources it was shown.

Sources:
{sources}

Question: {question}

Answer: {answer}

Break the answer into its individual factual claims about the subject matter. Ignore \
pleasantries, restatements of the question, and statements about the answer itself.

For each claim report:
  "text"                the claim, quoted or closely paraphrased
  "cited"               the source numbers the answer attaches to this claim, as a list \
of integers; empty if the claim carries no citation
  "supported_by_cited"  true if the cited sources alone state or directly imply the \
claim; false if they do not, or if there are no cited sources
  "supported_by_any"    true if any of the sources above state or directly imply it

A claim is supported only if a source says it. A claim that is true in general but absent \
from the sources is not supported.

Also report "addresses_question": whether the answer as a whole responds to the question \
asked.

Return only JSON, with no commentary:
{{"claims": [{{"text": "...", "cited": [1], "supported_by_cited": true, \
"supported_by_any": true}}], "addresses_question": true}}
"""

ABSTENTION_PROMPT = """\
You are checking whether an answer correctly declined to answer.

The question below cannot be answered from the sources shown. Declining is the correct \
response.

Sources:
{sources}

Question: {question}

Answer: {answer}

Report:
  "abstained"    true if the answer says the sources do not cover this, or otherwise \
declines to answer
  "unsupported"  true if the answer states something about the subject that the sources \
do not support

Saying what the sources *do* cover, in order to explain what is missing, is correct and \
is not unsupported. Only count claims the sources do not back.

Return only JSON, with no commentary:
{{"abstained": true, "unsupported": false}}
"""


@dataclass(frozen=True)
class Source:
    """A citation from the response, with the text reconstructed from its span."""

    number: int
    title: str
    text: str
    span_valid: bool


def cited_numbers(answer: str) -> list[int]:
    """Every source number the answer references, in order of first appearance."""
    seen: list[int] = []
    for group in CITATION.findall(answer):
        for part in group.split(","):
            number = int(part.strip())
            if number not in seen:
                seen.append(number)
    return seen


def sentences(answer: str) -> list[str]:
    return [s.strip() for s in SENTENCE_BREAK.split(answer.strip()) if s.strip()]


def grounded_fraction(answer: str) -> float:
    """Fraction of the answer's sentences that carry at least one citation."""
    parts = sentences(answer)
    if not parts:
        return 0.0
    return sum(1 for s in parts if CITATION.search(s)) / len(parts)


def resolve_sources(client: httpx.Client, citations: list[dict[str, Any]]) -> list[Source]:
    """Rebuilds each cited passage from its document and character span.

    The response carries spans rather than text, so this both recovers what the judge
    needs and checks the span: an offset pair that does not address real text is a
    citation a reader cannot follow, whatever the answer says.
    """
    resolved: list[Source] = []
    documents: dict[str, str] = {}

    for citation in citations:
        document_id = citation["documentId"]
        if document_id not in documents:
            response = client.get(f"/api/notes/{document_id}")
            response.raise_for_status()
            documents[document_id] = response.json()["content"]

        content = documents[document_id]
        start, end = citation["charStart"], citation["charEnd"]
        valid = 0 <= start < end <= len(content)
        resolved.append(
            Source(
                number=citation["number"],
                title=citation["title"],
                text=content[start:end] if valid else "",
                span_valid=valid,
            )
        )
    return resolved


def render_sources(sources: list[Source]) -> str:
    return "\n\n".join(f'[{s.number}] (from "{s.title}")\n{s.text}' for s in sources)


def ask(client: httpx.Client, question: str, agent: bool = False) -> dict[str, Any]:
    """Asks the question, optionally through the agent loop rather than one retrieval pass.

    The loop can search several times, so it is given a longer allowance than the single
    pass. A timeout here would be scored as a failure of the answer rather than of the
    clock, which is the wrong thing to learn from a run.
    """
    params = {"agent": "true"} if agent else None
    response = client.post("/api/ask", json={"question": question}, params=params, timeout=300.0)
    if response.is_error:
        # An HTTPStatusError rather than a plain one, because a failed query is recorded
        # and the run continues: the caller catches httpx's errors so that one refusal
        # costs its own question rather than every question after it.
        raise httpx.HTTPStatusError(
            f"{response.status_code} from /api/ask: {reason(response)}",
            request=response.request,
            response=response,
        )
    return dict(response.json())


def reason(response: httpx.Response) -> str:
    """What the API said about the refusal, rather than only its status.

    httpx's own message stops at the status line, and a whole run failing the same way
    then reports 172 copies of "Server error" with the cause only in the API's log. The
    body carries a sentence naming it — an exhausted balance, a rate limit, an unavailable
    provider — and that sentence is the whole content of the failure.
    """
    try:
        body = response.json()
    except ValueError:
        return response.text.strip()[:200] or "no detail given"
    if not isinstance(body, dict):
        return str(body)[:200]
    parts = [str(body[key]) for key in ("reason", "detail") if body.get(key)]
    return " — ".join(parts) if parts else str(body)[:200]


def score_answerable(
    client: httpx.Client, judge: Judge, query: GoldenQuery, agent: bool = False
) -> dict[str, Any]:
    answer = ask(client, query.query, agent)
    content = answer["content"]
    sources = resolve_sources(client, answer["citations"])

    referenced = cited_numbers(content)
    offered = {s.number for s in sources}
    invalid = [n for n in referenced if n not in offered]

    # An empty answer is a failure of the system, not a question for the judge — there is
    # nothing in it to decompose. It is scored zero on everything and counted, because
    # sending it to a judge invites a reply about the empty field rather than a verdict.
    if not content.strip():
        return {
            "id": query.id,
            "query": query.query,
            "sources_offered": len(sources),
            "sources_cited": 0,
            "invalid_citations": [],
            "spans_valid": all(s.span_valid for s in sources),
            "grounded_sentences": 0.0,
            "claims": 0,
            "faithfulness": 0.0,
            "citation_precision": 0.0,
            "claim_citation_rate": 0.0,
            "addresses_question": False,
            "empty": True,
        }

    verdict = parse_json(
        judge(
            CLAIM_PROMPT.format(
                sources=render_sources(sources), question=query.query, answer=content
            )
        )
    )
    claims = verdict.get("claims", [])
    with_citations = [c for c in claims if c.get("cited")]

    def fraction(values: list[bool]) -> float:
        return sum(values) / len(values) if values else 0.0

    return {
        "id": query.id,
        "query": query.query,
        "sources_offered": len(sources),
        "sources_cited": len(referenced),
        "invalid_citations": invalid,
        "spans_valid": all(s.span_valid for s in sources),
        "grounded_sentences": round(grounded_fraction(content), 4),
        "claims": len(claims),
        "faithfulness": round(fraction([bool(c.get("supported_by_any")) for c in claims]), 4),
        "citation_precision": round(
            fraction([bool(c.get("supported_by_cited")) for c in with_citations]), 4
        ),
        "claim_citation_rate": round(len(with_citations) / len(claims) if claims else 0.0, 4),
        "addresses_question": bool(verdict.get("addresses_question")),
        "empty": False,
    }


def score_unanswerable(
    client: httpx.Client, judge: Judge, query: GoldenQuery, agent: bool = False
) -> dict[str, Any]:
    """Scores a question the corpus cannot answer, where declining is correct.

    The judge is shown the sources as well, because the failure worth counting is an
    assertion the sources do not support. An answer that declines and then says what the
    corpus *does* cover, to explain the gap, is behaving correctly and is scored as such —
    without the sources in view, a good abstention is indistinguishable from a fabricated
    answer.
    """
    answer = ask(client, query.query, agent)
    content = answer["content"]
    sources = resolve_sources(client, answer["citations"])
    verdict = parse_json(
        judge(
            ABSTENTION_PROMPT.format(
                sources=render_sources(sources), question=query.query, answer=content
            )
        )
    )
    return {
        "id": query.id,
        "query": query.query,
        "abstained": bool(verdict.get("abstained")),
        "unsupported": bool(verdict.get("unsupported")),
        "sources_offered": len(answer["citations"]),
    }


def checkpoint_key(golden: str, agent: bool) -> dict[str, str]:
    """What a part-finished run has to match before it may be resumed.

    Resuming the wrong run is the failure this guards against, and it is silent: the loop's
    answers restored into a single-pass run would produce a comparison of one path against
    itself, at full price, with nothing about the numbers to show for it.
    """
    return {"golden": golden, "path": "agent" if agent else "single-pass"}


def read_checkpoint(path: Path | None, key: dict[str, str]) -> dict[str, dict[str, Any]]:
    """Scored queries from an earlier attempt, keyed by query id.

    A checkpoint whose header does not match this run is ignored rather than an error: the
    common reason for one to be stale is that the previous run finished, and refusing to
    start would be an odd way to report that.

    Reading stops at the first line that will not parse rather than discarding the file. A
    killed process leaves its last line half written; the lines before it were flushed
    whole and are what the resume is for.
    """
    if path is None or not path.exists():
        return {}

    lines = [line for line in path.read_text().splitlines() if line.strip()]
    if not lines:
        return {}

    try:
        header = json.loads(lines[0])
    except json.JSONDecodeError:
        return {}
    if header != key:
        return {}

    done: dict[str, dict[str, Any]] = {}
    for line in lines[1:]:
        try:
            entry = json.loads(line)
            done[entry["record"]["id"]] = entry
        except (json.JSONDecodeError, KeyError, TypeError):
            break
    return done


def write_checkpoint(path: Path, key: dict[str, str], entries: list[dict[str, Any]]) -> None:
    """Starts the file from the entries this run will actually reuse.

    Called once, before the first query is asked, and it rewrites rather than appends. That
    is what makes a second resume work: a truncated tail left in place would still be
    unparseable next time, and everything appended after it would be read as coming after a
    break and skipped.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w") as f:
        f.write(json.dumps(key) + "\n")
        for entry in entries:
            f.write(json.dumps(entry) + "\n")


def evaluate(
    client: httpx.Client,
    judge: Judge,
    queries: list[GoldenQuery],
    limit: int | None = None,
    agent: bool = False,
    checkpoint: Path | None = None,
    golden: str = "",
) -> dict[str, Any]:
    answerable = [q for q in queries if not q.unanswerable][:limit]
    unanswerable = [q for q in queries if q.unanswerable][:limit]

    # A run is over a hundred model calls on each side, and one unusable reply should cost
    # the query rather than the run. The first full run failed on question 130 because a
    # judge appended a sentence after its JSON, discarding every result before it. Failures
    # are collected and reported so a run with several is visibly not a clean one.
    failures: list[dict[str, str]] = []

    # Every scored query is committed before the next one is asked. A run is minutes of
    # model calls and the first attempt at one died partway through with nothing to show
    # for the questions it had already paid for; written as it goes, a killed run resumes
    # from where it stopped and only pays for what is left.
    key = checkpoint_key(golden, agent)
    done = read_checkpoint(checkpoint, key)
    if checkpoint is not None:
        write_checkpoint(checkpoint, key, list(done.values()))
    if done:
        print(f"  resuming: {len(done)} query(s) already scored in {checkpoint}")

    def collect(score: Any, items: list[GoldenQuery], kind: str) -> list[dict[str, Any]]:
        results = []
        for query in items:
            if query.id in done and done[query.id]["kind"] == kind:
                results.append(done[query.id]["record"])
                continue
            try:
                record = score(client, judge, query, agent)
            except (ValueError, httpx.HTTPError) as exc:
                failures.append({"id": query.id, "error": f"{type(exc).__name__}: {exc}"[:300]})
                continue
            results.append(record)
            if checkpoint is not None:
                with checkpoint.open("a") as f:
                    f.write(json.dumps({"kind": kind, "record": record}) + "\n")
        return results

    scored = collect(score_answerable, answerable, "answerable")
    declined = collect(score_unanswerable, unanswerable, "unanswerable")

    def mean(values: list[float]) -> float:
        return round(statistics.fmean(values), 4) if values else 0.0

    return {
        "timestamp": datetime.now(UTC).isoformat(),
        "judge": judge.name,
        # Recorded on the run rather than left to the filename, so a comparison cannot
        # accidentally put the loop's numbers against the loop's numbers.
        "path": "agent" if agent else "single-pass",
        "queries_answerable": len(scored),
        "queries_unanswerable": len(declined),
        "failures": failures,
        "metrics": {
            # Judged.
            "faithfulness": mean([s["faithfulness"] for s in scored]),
            "citation_precision": mean([s["citation_precision"] for s in scored]),
            "claim_citation_rate": mean([s["claim_citation_rate"] for s in scored]),
            "addresses_question": mean([float(s["addresses_question"]) for s in scored]),
            "abstention_rate": mean([float(d["abstained"]) for d in declined]),
            "unsupported_when_unanswerable": mean([float(d["unsupported"]) for d in declined]),
            # Structural, decided without a model.
            "citations_in_range": mean([float(not s["invalid_citations"]) for s in scored]),
            "spans_valid": mean([float(s["spans_valid"]) for s in scored]),
            "grounded_sentences": mean([s["grounded_sentences"] for s in scored]),
            "sources_cited": mean([float(s["sources_cited"]) for s in scored]),
            "empty_answers": mean([float(s.get("empty", False)) for s in scored]),
        },
        "per_query": scored,
        "per_unanswerable": declined,
    }


def print_report(report: dict[str, Any]) -> None:
    m = report["metrics"]
    print()
    print(
        f"  Generation evaluation [judge: {report['judge']}] — "
        f"{report['queries_answerable']} answerable, "
        f"{report['queries_unanswerable']} unanswerable"
    )
    print("  " + "-" * 52)
    print("  judged")
    for name in (
        "faithfulness",
        "citation_precision",
        "claim_citation_rate",
        "addresses_question",
    ):
        print(f"    {name:<26s} {m[name]:.4f}")
    print(f"    {'abstention_rate':<26s} {m['abstention_rate']:.4f}")
    print(f"    {'unsupported_when_unanswerable':<30s} {m['unsupported_when_unanswerable']:.4f}")
    print("  structural")
    for name in (
        "citations_in_range",
        "spans_valid",
        "grounded_sentences",
        "sources_cited",
        "empty_answers",
    ):
        print(f"    {name:<26s} {m[name]:.4f}")
    print("  " + "-" * 52)

    unfaithful = sorted(report["per_query"], key=lambda q: q["faithfulness"])[:5]
    if unfaithful and unfaithful[0]["faithfulness"] < 1.0:
        print("\n  Least faithful answers:")
        for q in unfaithful:
            if q["faithfulness"] < 1.0:
                print(f"    {q['faithfulness']:.3f}  {q['id']}  {q['query'][:56]}")

    if report.get("failures"):
        print(f"\n  {len(report['failures'])} query(s) could not be scored:")
        for failure in report["failures"][:5]:
            print(f"    {failure['id']}  {failure['error'][:120]}")

    answered = [d for d in report["per_unanswerable"] if not d["abstained"]]
    if answered:
        print("\n  Answered a question the corpus cannot answer:")
        for d in answered:
            print(f"    {d['id']}  {d['query'][:60]}")
    print()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generation evaluation over the live API")
    parser.add_argument("--api", default="http://localhost:8080", help="base URL of the API")
    parser.add_argument("--golden", type=Path, default=GOLDEN_DIR, help="golden set directory")
    parser.add_argument("--baseline", type=Path, default=BASELINE, help="baseline file")
    parser.add_argument(
        "--judge",
        choices=("auto", "api", "cli"),
        default="auto",
        help="how to reach the judge model (default: API when a key is set, else the CLI)",
    )
    parser.add_argument(
        "--judge-model",
        help="judge model; must differ from the generator to avoid self-preference",
    )
    parser.add_argument(
        "--only",
        help="score just these query ids, comma-separated (M030,M032). What --limit "
        "cannot express: it takes the first N, so reaching a question late in the file "
        "means paying for everything before it.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        help="score only the first N of each kind, for a cheaper smoke run",
    )
    parser.add_argument(
        "--agent",
        action="store_true",
        help="answer through the agent loop instead of a single retrieval pass",
    )
    parser.add_argument(
        "--out",
        type=Path,
        help="where to write the run, instead of a timestamped name in evals/runs",
    )
    parser.add_argument(
        "--checkpoint",
        type=Path,
        help="scored queries are appended here and reused on a rerun; defaults to "
        "<out>.partial when --out is given",
    )
    parser.add_argument(
        "--no-checkpoint",
        action="store_true",
        help="score every query again even if a part-finished run is present",
    )
    parser.add_argument("--write-baseline", action="store_true", help="record this run")
    args = parser.parse_args(argv)

    queries = load_golden_set(args.golden)
    if not queries:
        print(f"No golden queries found in {args.golden}", file=sys.stderr)
        return 1

    if args.only:
        wanted = [q.strip() for q in args.only.split(",") if q.strip()]
        by_id = {q.id: q for q in queries}
        # An id that does not exist is refused rather than skipped. The purpose of this
        # option is to spend money on a named handful of questions, and silently scoring
        # three of the four asked for produces a run that looks complete and answers a
        # different question than the one intended.
        missing = [q for q in wanted if q not in by_id]
        if missing:
            print(f"no such query id in {args.golden.name}: {', '.join(missing)}", file=sys.stderr)
            return 1
        queries = [by_id[q] for q in wanted]

    judge = default_judge(args.judge, args.judge_model)

    # Beside the run rather than in a fixed place, so two paths over the same set do not
    # share one, which is the resume that would silently compare a path against itself.
    checkpoint = args.checkpoint
    if checkpoint is None and args.out is not None:
        checkpoint = args.out.with_suffix(args.out.suffix + ".partial")
    if args.no_checkpoint:
        checkpoint = None

    with httpx.Client(base_url=args.api) as client:
        report = evaluate(
            client, judge, queries, args.limit, args.agent, checkpoint, args.golden.name
        )

    report["golden"] = args.golden.name
    print_report(report)

    # A timestamped name by default, so ordinary runs accumulate rather than overwrite.
    # `--out` exists because the two halves of a path comparison differ only by their
    # timestamp otherwise, and a script that has to pick them apart by modification time
    # is one restart away from comparing a run against itself.
    run_path = args.out or (
        RUNS_DIR / f"{datetime.now(UTC):%Y%m%dT%H%M%SZ}-generation-{args.golden.name}.json"
    )
    run_path.parent.mkdir(parents=True, exist_ok=True)
    run_path.write_text(json.dumps(report, indent=2) + "\n")
    print(f"  run written to {run_path}")

    # The finished report supersedes the part-finished one; leaving it would offer a
    # resume to a run that has nothing left to do.
    if checkpoint is not None and checkpoint.exists() and not report.get("failures"):
        checkpoint.unlink()

    if args.write_baseline:
        args.baseline.write_text(json.dumps(report, indent=2) + "\n")
        print(f"  baseline written to {args.baseline}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
