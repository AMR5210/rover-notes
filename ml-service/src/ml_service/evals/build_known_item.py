"""Generates the known-item golden slice from the corpus.

Known-item queries are derived from the corpus by rule rather than written by hand. A
hand-written set is chosen by someone who knows which channel is likely to win, and the
first version of this slice was: 24 queries selected by judgement, of which only five
were imperfect, leaving any conclusion resting on those five. Deriving every query from
a stated rule removes that selection step — coverage is whatever the corpus contains,
and a query that turns out to be easy stays in.

Three families, one query per document per family where the rule can produce one:

``identifier``
    A token that occurs, case-insensitively, in exactly one document and carries a mark
    of being an identifier rather than prose: an underscore, a digit, an internal dot or
    hyphen, internal capitalisation, or a surrounding markdown code span. The shape test
    is what keeps ordinary words out — ``configuration`` and ``construction`` each appear
    in exactly one document, and neither is something a reader looks up verbatim.
    Requiring a code span alone was tried first and yielded two queries across the whole
    corpus, which is too few to measure anything. Ties break toward a code span, then an
    underscore, then a digit, then the longest token.

``title``
    The document's slug with separators replaced by spaces — the title as a reader
    would type it.

``near-miss``
    The title with a deterministic single-character edit: the first doubled letter is
    reduced to one, or failing that two adjacent letters of the longest word are
    transposed. This is the misspelling case, where lexical matching has no lexeme in
    common with the target and only a fuzzier match can recover the document.

A query that matches a second document as a phrase is dropped rather than labelled
against both. A lookup naming two documents is not a known-item lookup, and the
alternative — marking every document containing the phrase relevant — would turn the
task into something else. Dropping costs coverage and keeps the definition intact; the
generator reports how many it discarded.

Every surviving query is validated before it is written: its anchor phrase must occur in
the document it names.

A second suite is written alongside it. ``evals/golden-known-item/`` keeps one identifier
per document — the highest-ranked — so its identifier family is the most distinctive
tokens in the corpus, and a result measured only there could hold only for tokens like
those. ``evals/golden-known-item-heldout/`` is every other unique identifier, selected by
nothing beyond having lost a tie, and exists to test whether such a result generalises.

    uv run python -m ml_service.evals.build_known_item          # write both suites
    uv run python -m ml_service.evals.build_known_item --check  # verify they are current
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

from ml_service.evals.dataset import corpus_documents, normalise

REPO_ROOT = Path(__file__).resolve().parents[4]
CORPUS_DIR = REPO_ROOT / "evals" / "corpus"
SLICE_PATH = REPO_ROOT / "evals" / "golden-known-item" / "seed.jsonl"
HELD_OUT_PATH = REPO_ROOT / "evals" / "golden-known-item-heldout" / "seed.jsonl"

CODE_SPAN = re.compile(r"`([^`\n]+)`")
TOKEN = re.compile(r"[A-Za-z_][A-Za-z0-9_.\-]{3,}")

# Tokens that are ordinary prose rather than something a reader would look up verbatim.
# Kept deliberately short: the uniqueness requirement already removes most prose, and a
# long list here would become the hand-selection this module exists to avoid.
STOPWORDS = frozenset(
    {"that", "this", "which", "these", "those", "there", "their", "where", "when", "with"}
)


def clean(token: str) -> str:
    """Drops trailing punctuation the token pattern absorbs from prose.

    ``TOKEN`` admits dots and hyphens so that ``hnsw.iterative_scan`` survives intact,
    which also means a sentence-final period lands inside the match. Stripping it keeps
    ``expand_entity.`` from becoming a query no reader would type.
    """
    return token.rstrip(".-_")


def document_tokens(text: str) -> set[str]:
    tokens = {clean(m.group(0)) for m in TOKEN.finditer(text)}
    return {t for t in tokens if len(t) >= 4 and t.lower() not in STOPWORDS}


def code_span_tokens(text: str) -> set[str]:
    tokens: set[str] = set()
    for span in CODE_SPAN.finditer(text):
        tokens.update(clean(m.group(0)) for m in TOKEN.finditer(span.group(1)))
    return {t for t in tokens if len(t) >= 4}


def looks_like_identifier(token: str, in_code_span: bool) -> bool:
    """Whether a token is shaped like something a reader would type verbatim."""
    if in_code_span or "_" in token or any(c.isdigit() for c in token):
        return True
    inner = token[1:-1]
    if "." in inner or "-" in inner:
        return True
    return any(c.isupper() for c in inner)


def identifier_rank(token: str, in_code_span: bool) -> tuple[int, int, int, int]:
    """Sort key, highest first: code span, underscore, digit, length."""
    return (
        1 if in_code_span else 0,
        1 if "_" in token else 0,
        1 if any(c.isdigit() for c in token) else 0,
        len(token),
    )


def unique_identifiers(slug: str, docs: dict[str, str]) -> list[str]:
    """Every identifier-shaped token appearing in ``slug`` and in no other document.

    Uniqueness is counted over documents rather than occurrences, and case-insensitively:
    ``Configuration`` at the start of a sentence and ``configuration`` mid-sentence are
    the same token to a reader looking it up.

    Returned most identifier-like first, so the head of the list is what the main slice
    takes and the tail is the held-out population.
    """
    seen: Counter[str] = Counter()
    for text in docs.values():
        seen.update({t.lower() for t in document_tokens(text)})

    code = code_span_tokens(docs[slug])
    unique = [
        t
        for t in document_tokens(docs[slug])
        if seen[t.lower()] == 1 and looks_like_identifier(t, t in code)
    ]
    return sorted(unique, key=lambda t: (identifier_rank(t, t in code), t), reverse=True)


def unique_identifier(slug: str, docs: dict[str, str]) -> str | None:
    """The single most identifier-like token unique to ``slug``."""
    ranked = unique_identifiers(slug, docs)
    return ranked[0] if ranked else None


def title_query(slug: str) -> str:
    return slug.replace("-", " ").replace("_", " ")


def near_miss(title: str) -> str | None:
    """One deterministic character edit: undouble a letter, else transpose a pair."""
    for i in range(len(title) - 1):
        if title[i] == title[i + 1] and title[i].isalpha():
            return title[:i] + title[i + 1 :]

    words = sorted(title.split(), key=lambda w: (-len(w), w))
    if not words or len(words[0]) < 4:
        return None
    word = words[0]
    swapped = word[0] + word[2] + word[1] + word[3:]
    if swapped == word:
        return None
    return title.replace(word, swapped, 1)


def heading(text: str) -> str | None:
    """The document's first markdown heading, used as the title family's anchor."""
    for line in text.splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return None


def build(docs: dict[str, str]) -> tuple[list[dict[str, object]], list[str]]:
    """Returns the slice and a note for every candidate dropped as ambiguous."""
    normalised = {slug: normalise(text) for slug, text in docs.items()}
    candidates: list[tuple[str, str, str, str]] = []

    for slug in sorted(docs):
        token = unique_identifier(slug, docs)
        if token:
            candidates.append(("identifier", token, slug, token))

        anchor = heading(docs[slug])
        if not anchor:
            continue
        title = title_query(slug)
        candidates.append(("title", title, slug, anchor))
        misspelled = near_miss(title)
        if misspelled:
            candidates.append(("near-miss", misspelled, slug, anchor))

    queries: list[dict[str, object]] = []
    dropped: list[str] = []
    counters = {"i": 0, "t": 0, "n": 0}

    for family, query, slug, anchor in candidates:
        also = [s for s, t in normalised.items() if s != slug and normalise(query) in t]
        if also:
            dropped.append(f"{family} {query!r} ({slug}) also names {', '.join(sorted(also))}")
            continue
        prefix = family[0]
        counters[prefix] += 1
        queries.append(
            {
                "id": f"k{prefix}{counters[prefix]:03d}",
                "query": query,
                "relevant": [{"doc": slug, "contains": anchor}],
            }
        )

    return queries, dropped


def build_held_out(docs: dict[str, str]) -> tuple[list[dict[str, object]], list[str]]:
    """Every corpus-unique identifier the main slice did not take.

    The main slice keeps one identifier per document — the highest-ranked, which favours
    underscores, digits and code spans. That makes its identifier family the most
    distinctive tokens in the corpus, and a gain measured only there could be a gain that
    holds only for maximally distinctive tokens. The rest of the unique identifiers are a
    held-out population of the same kind, selected by nothing beyond having lost a tie,
    and they are noticeably plainer: ``fixed-size`` and ``pre-assembled`` sit alongside
    ``RS256`` and ``JWKS``.

    This suite answers one question and no others: does the routing gain survive on
    identifiers the generator did not choose.
    """
    normalised = {slug: normalise(text) for slug, text in docs.items()}
    queries: list[dict[str, object]] = []
    dropped: list[str] = []
    index = 0

    for slug in sorted(docs):
        for token in unique_identifiers(slug, docs)[1:]:
            also = [s for s, t in normalised.items() if s != slug and normalise(token) in t]
            if also:
                dropped.append(f"identifier {token!r} ({slug}) also names {', '.join(also)}")
                continue
            index += 1
            queries.append(
                {
                    "id": f"kh{index:03d}",
                    "query": token,
                    "relevant": [{"doc": slug, "contains": token}],
                }
            )

    return queries, dropped


def validate(queries: list[dict[str, object]], docs: dict[str, str]) -> list[str]:
    """Checks every anchor phrase is present in the document its rule names."""
    problems: list[str] = []
    normalised = {slug: normalise(text) for slug, text in docs.items()}

    for entry in queries:
        rules = entry["relevant"]
        assert isinstance(rules, list)
        for rule in rules:
            if rule["doc"] not in docs:
                problems.append(f"{entry['id']}: no document {rule['doc']!r}")
            elif normalise(rule["contains"]) not in normalised[rule["doc"]]:
                problems.append(f"{entry['id']}: {rule['contains']!r} absent from {rule['doc']}")

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate the known-item golden slice")
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the committed slice matches what the corpus generates",
    )
    args = parser.parse_args(argv)

    docs = corpus_documents(CORPUS_DIR)
    suites = [(SLICE_PATH, *build(docs)), (HELD_OUT_PATH, *build_held_out(docs))]

    for path, queries, _ in suites:
        problems = validate(queries, docs)
        if problems:
            print(f"{path.parent.name} does not validate:", *problems, sep="\n  ", file=sys.stderr)
            return 1

    for path, queries, dropped in suites:
        rendered = "".join(json.dumps(q) + "\n" for q in queries)

        if args.check:
            if (path.read_text() if path.exists() else "") != rendered:
                print(
                    f"{path} is out of date — regenerate it with "
                    "`uv run python -m ml_service.evals.build_known_item`.",
                    file=sys.stderr,
                )
                return 1
            print(f"  {path.parent.name}/{path.name} is current ({len(queries)} queries)")
            continue

        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(rendered)
        families = Counter(str(q["id"])[1] for q in queries)
        breakdown = ", ".join(
            f"{name} {families[prefix]}"
            for prefix, name in (
                ("i", "identifier"),
                ("t", "title"),
                ("n", "near-miss"),
                ("h", "held-out identifier"),
            )
            if families[prefix]
        )
        print(f"  wrote {len(queries)} queries to {path} ({breakdown})")
        for note in dropped:
            print(f"    dropped as ambiguous: {note}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
