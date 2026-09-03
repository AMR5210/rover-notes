"""Reranking by late interaction.

Two things are worth pinning and they are different in kind. The scoring is arithmetic
and is checked directly, because the way a MaxSim implementation goes wrong is quietly:
padding counted as a weak match, or a mask applied to the wrong axis, both of which
produce a working reranker that ranks slightly incorrectly and no error anywhere.

The rest is the contract around it — that the strategy is selectable per request, that
the default did not move, and that a service installed without the extra says which
install it needs rather than failing at import.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from ml_service.main import app

RERANKING = Path(__file__).resolve().parents[1] / "src" / "ml_service" / "reranking"

pylate = pytest.importorskip("pylate", reason="needs the late-interaction extra")
torch = pytest.importorskip("torch", reason="needs the late-interaction extra")


def _module_level_imports(source: str) -> set[str]:
    tree = ast.parse(source)
    names: set[str] = set()
    for node in tree.body:
        if isinstance(node, ast.Import):
            names.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            names.add(node.module.split(".")[0])
    return names


def test_torch_is_not_imported_when_the_module_loads() -> None:
    # The same rule the parsing extra follows. torch at module level would mean a base
    # install could not start the service, taking embeddings down with it.
    source = (RERANKING / "late_interaction.py").read_text()

    assert not ({"torch", "pylate", "numpy"} & _module_level_imports(source)), (
        "these come from the late-interaction extra and must be imported inside the "
        "functions that use them"
    )


def test_pylates_own_mask_does_not_remove_padding_from_the_max() -> None:
    """Why this module scores MaxSim itself instead of calling `colbert_scores`.

    `documents_mask` multiplies the similarities by the mask, so a padded position
    becomes exactly 0 — and 0 beats any genuinely negative similarity when the maximum
    is taken. Passing the mask therefore changes nothing for the case it looks like it
    handles. Verified against pylate 1.6; if a later version masks to negative infinity
    this test fails and the local implementation can be dropped.
    """
    from pylate import scores as pylate_scores

    query = torch.tensor([[[0.0, 1.0]]])
    documents = torch.tensor([[[0.0, -1.0], [0.0, 0.0]]])  # one real token at -1, one pad
    mask = torch.tensor([[1.0, 0.0]])

    with_mask = pylate_scores.colbert_scores(query, documents, documents_mask=mask).item()
    without = pylate_scores.colbert_scores(query, documents).item()

    assert with_mask == without == 0.0, "the mask makes no difference to a padded zero row"
    assert with_mask != -1.0, "the true score is the only real token's similarity"


def test_a_short_passage_does_not_gain_from_being_padded() -> None:
    """The bias the local masking exists to remove.

    Two passages, the second much longer, both scored against a query whose tokens match
    the short one badly. Padded to equal length without a proper mask, the short one's
    negative similarities are floored at zero and it outranks a passage that genuinely
    matches better.
    """
    from ml_service.reranking import rerank_late

    short = "Espresso."
    answering = (
        "PARROTVALVE is the sentinel load the recovery routine inserts when two depots "
        "each hold the other's only free vehicle, and it carries no freight."
    )

    ranked = dict(
        rerank_late(
            "what is PARROTVALVE?",
            [short, answering],
            model_name="mixedbread-ai/mxbai-edge-colbert-v0-32m",
        )
    )

    assert ranked[1] > ranked[0], "the passage that answers the question must win"


def test_ranks_the_passage_that_answers_the_question_first() -> None:
    from ml_service.reranking import rerank_late

    ranked = rerank_late(
        "what is PARROTVALVE?",
        [
            "The planning window is four hours and moves in fifteen-minute steps.",
            "PARROTVALVE is the sentinel load inserted when two depots deadlock.",
            "Espresso is brewed by forcing hot water through ground coffee.",
        ],
        model_name="mixedbread-ai/mxbai-edge-colbert-v0-32m",
    )

    assert ranked[0][0] == 1, "the answering passage ranks first"
    assert len(ranked) == 3, "every candidate is scored, not only the winner"


def test_an_empty_candidate_list_is_not_an_error() -> None:
    from ml_service.reranking import rerank_late

    # Retrieval returning nothing is an ordinary outcome, and the reranker is called
    # with whatever it returned.
    assert rerank_late("anything", [], model_name="unused") == []


def test_the_strategy_is_chosen_per_request_and_reported_back() -> None:
    client = TestClient(app)
    body = {
        "query": "what is PARROTVALVE?",
        "documents": [
            "The planning window is four hours.",
            "PARROTVALVE is the sentinel load inserted when two depots deadlock.",
        ],
        "top_k": 2,
        "strategy": "late-interaction",
    }

    response = client.post("/rerank", json=body)

    assert response.status_code == 200
    payload = response.json()
    assert payload["strategy"] == "late-interaction"
    assert payload["results"][0]["index"] == 1


def test_the_default_strategy_is_still_the_cross_encoder() -> None:
    # The comparison is the point of having two, and a default that moved silently
    # would make every earlier measurement mean something different.
    from ml_service.main import RerankRequest

    assert RerankRequest(query="q", documents=["d"]).strategy == "cross-encoder"


def test_an_unknown_strategy_is_refused() -> None:
    client = TestClient(app)

    response = client.post(
        "/rerank",
        json={"query": "q", "documents": ["d"], "strategy": "reranker-9000"},
    )

    assert response.status_code == 422


def test_the_model_is_warmed_at_startup_not_on_the_first_search() -> None:
    """Why the lifespan loads the weights.

    Deferred to the first request, the load lands inside somebody's search. That is not
    hypothetical here: a SciFact evaluation run failed outright because the first query's
    load pushed the request past the client's timeout, and what reached the API was an
    unreadable body rather than anything naming a model load.

    Checked by reading the module rather than by starting the service, because asserting
    on the timing of a background task is the kind of test that passes on a fast machine
    and fails on a loaded one.
    """
    source = (Path(__file__).resolve().parents[1] / "src" / "ml_service" / "main.py").read_text()
    tree = ast.parse(source)

    lifespan = next(
        node
        for node in tree.body
        if isinstance(node, ast.AsyncFunctionDef) and node.name == "lifespan"
    )
    calls = [
        node.func.attr
        for node in ast.walk(lifespan)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
    ]

    assert "create_task" in calls, "the warm-up must start with the service"
    # Scheduled rather than awaited: the service has to answer health checks and every
    # other endpoint while several hundred megabytes of weights load.
    assert not any(
        isinstance(node, ast.Await)
        and isinstance(node.value, ast.Call)
        and getattr(node.value.func, "id", "") == "_warm_late_interaction"
        for node in ast.walk(lifespan)
    ), "awaiting the warm-up would block startup"


def test_a_failed_warm_up_does_not_stop_the_service() -> None:
    # Reranking by late interaction is not the default. A service that refused to start
    # because an optional model could not be fetched would take embeddings down with it,
    # which is the failure the parsing extra already taught this codebase once.
    source = (Path(__file__).resolve().parents[1] / "src" / "ml_service" / "main.py").read_text()
    tree = ast.parse(source)

    warm = next(
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.AsyncFunctionDef) and node.name == "_warm_late_interaction"
    )
    handled = {
        handler.type.id
        for handler in ast.walk(warm)
        if isinstance(handler, ast.ExceptHandler) and isinstance(handler.type, ast.Name)
    }

    assert "ImportError" in handled, "an install without the extra is a supported deployment"
    assert "Exception" in handled, "a model that will not load must not decide whether we serve"
