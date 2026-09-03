"""Generation of the known-item golden slice.

The properties asserted here are the ones that make the slice trustworthy as evidence:
that queries are derived by rule rather than chosen, that a query naming two documents
is discarded rather than labelled against both, and that ordinary prose does not become
an identifier query.
"""

from ml_service.evals.build_known_item import (
    build,
    build_held_out,
    clean,
    looks_like_identifier,
    near_miss,
    unique_identifier,
    unique_identifiers,
    validate,
)


class TestClean:
    def test_strips_trailing_sentence_punctuation(self) -> None:
        # The token pattern admits dots so hnsw.iterative_scan survives whole, which
        # also swallows a sentence-final period.
        assert clean("expand_entity.") == "expand_entity"
        assert clean("hnsw.iterative_scan") == "hnsw.iterative_scan"


class TestLooksLikeIdentifier:
    def test_accepts_tokens_carrying_identifier_marks(self) -> None:
        for token in ("max_connections", "A10G", "hnsw.iterative_scan", "point-in-time"):
            assert looks_like_identifier(token, in_code_span=False), token

    def test_rejects_ordinary_prose(self) -> None:
        # Both of these occur in exactly one corpus document, so uniqueness alone would
        # admit them. Neither is something a reader types to find a document again.
        for token in ("configuration", "construction"):
            assert not looks_like_identifier(token, in_code_span=False), token

    def test_a_code_span_is_enough_on_its_own(self) -> None:
        assert looks_like_identifier("chunks", in_code_span=True)

    def test_leading_capital_alone_is_not_a_mark(self) -> None:
        # Otherwise every sentence-initial word qualifies.
        assert not looks_like_identifier("Configuration", in_code_span=False)
        assert looks_like_identifier("ModularityTests", in_code_span=False)


class TestUniqueIdentifier:
    def test_prefers_an_underscore_token_over_a_hyphenated_one(self) -> None:
        docs = {
            "a": "The `max_connections` setting is point-in-time only.",
            "b": "Unrelated prose about other things entirely.",
        }
        assert unique_identifier("a", docs) == "max_connections"

    def test_ignores_tokens_that_appear_in_another_document(self) -> None:
        docs = {"a": "uses cache_control here", "b": "also uses cache_control there"}
        assert unique_identifier("a", docs) is None

    def test_uniqueness_ignores_case(self) -> None:
        # A token capitalised at the start of a sentence is the same token to a reader.
        docs = {"a": "Retry-After is set.", "b": "we set retry-after on throttle"}
        assert unique_identifier("a", docs) is None


class TestUniqueIdentifiers:
    def test_returns_every_unique_identifier_most_distinctive_first(self) -> None:
        docs = {
            "a": "The `max_connections` setting is point-in-time and RS256 signed.",
            "b": "Unrelated prose entirely.",
        }
        ranked = unique_identifiers("a", docs)

        assert ranked[0] == "max_connections"
        assert set(ranked) == {"max_connections", "point-in-time", "RS256"}

    def test_the_singular_helper_takes_the_head_of_that_list(self) -> None:
        docs = {"a": "`max_connections` and point-in-time", "b": "prose"}

        assert unique_identifier("a", docs) == unique_identifiers("a", docs)[0]


class TestBuildHeldOut:
    def test_takes_every_identifier_the_main_slice_did_not(self) -> None:
        docs = {
            "a": "The `max_connections` setting is point-in-time and RS256 signed.",
            "b": "Unrelated prose entirely.",
        }
        selected = unique_identifier("a", docs)
        held_out, _ = build_held_out(docs)

        queries = {q["query"] for q in held_out}
        assert selected not in queries
        assert queries == {"point-in-time", "RS256"}

    def test_does_not_overlap_the_main_slice(self) -> None:
        # The whole point is a population the main slice did not select; an overlap
        # would make the two suites partly the same measurement.
        docs = {
            "a": "The `max_connections` setting is point-in-time and RS256 signed.",
            "b": "A `visited_set` bounds the walk, with a five-minute budget.",
        }
        main, _ = build(docs)
        held_out, _ = build_held_out(docs)

        main_identifiers = {q["query"] for q in main if str(q["id"]).startswith("ki")}
        assert main_identifiers.isdisjoint({q["query"] for q in held_out})

    def test_drops_an_identifier_whose_text_appears_in_a_second_document(self) -> None:
        # Uniqueness is counted over tokens, so `Retry-After` and `retry-afterwards` are
        # different and both survive that check. But a reader searching "Retry-After"
        # would match the second document too, which is what the ambiguity check catches.
        docs = {
            "a": "`row_lock` guards the claim, and Retry-After bounds the wait.",
            "b": "Clients retry-afterwards when the queue is saturated.",
        }
        held_out, dropped = build_held_out(docs)

        assert "Retry-After" not in {q["query"] for q in held_out}
        assert any("Retry-After" in note for note in dropped)


class TestNearMiss:
    def test_undoubles_a_repeated_letter(self) -> None:
        assert near_miss("connection pooling") == "conection pooling"

    def test_transposes_when_no_letter_is_doubled(self) -> None:
        assert near_miss("cost model") == "cost mdoel"

    def test_returns_nothing_when_no_word_is_long_enough(self) -> None:
        assert near_miss("a b") is None

    def test_is_deterministic(self) -> None:
        assert near_miss("query routing") == near_miss("query routing")


class TestBuild:
    def test_drops_a_title_that_names_a_second_document(self) -> None:
        docs = {
            "semantic-chunking": "# Semantic chunking\n\nSplits on topic shifts.",
            "chunking-strategy": "# Chunking\n\nSemantic chunking replaces fixed windows.",
        }
        queries, dropped = build(docs)

        assert not any(q["query"] == "semantic chunking" for q in queries)
        assert any("semantic chunking" in note for note in dropped)

    def test_keeps_a_misspelling_of_an_ambiguous_title(self) -> None:
        # The title is ambiguous; the misspelling of it is not, and it is the family
        # that most needs the coverage.
        docs = {
            "semantic-chunking": "# Semantic chunking\n\nSplits on topic shifts.",
            "chunking-strategy": "# Chunking\n\nSemantic chunking replaces fixed windows.",
        }
        queries, _ = build(docs)

        assert any(q["id"].startswith("kn") for q in queries)

    def test_families_are_numbered_independently(self) -> None:
        docs = {"outbox-pattern": "# The outbox\n\nUses `event_publication` rows."}
        queries, _ = build(docs)

        ids = [q["id"] for q in queries]
        assert ids == ["ki001", "kt001", "kn001"]

    def test_generated_queries_validate(self) -> None:
        docs = {"outbox-pattern": "# The outbox\n\nUses `event_publication` rows."}
        queries, _ = build(docs)

        assert validate(queries, docs) == []


class TestValidate:
    def test_reports_an_anchor_absent_from_its_document(self) -> None:
        docs = {"a": "# Title\n\nbody text"}
        queries: list[dict[str, object]] = [
            {"id": "kt001", "query": "a", "relevant": [{"doc": "a", "contains": "missing"}]}
        ]

        assert validate(queries, docs) == ["kt001: 'missing' absent from a"]
