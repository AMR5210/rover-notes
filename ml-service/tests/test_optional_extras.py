"""The service starts without the optional parsing extra.

pdfplumber, trafilatura and the rest are heavy, and only the two upload paths need them.
They were imported at module level, so a base install could not start the service at all —
`make ml` on a fresh clone failed on `ModuleNotFoundError: pdfplumber`, taking embeddings
and reranking down over a dependency neither uses.

What this pins is the shape of the fix: the imports live inside the endpoints, so the
service runs either way and refuses only what it genuinely cannot do. `python-multipart`
is the exception and belongs in the base install — FastAPI needs it to *declare* an
UploadFile endpoint, whatever the handler does, so a lazy import cannot help.
"""

from __future__ import annotations

import ast
from pathlib import Path

MAIN = Path(__file__).resolve().parents[1] / "src" / "ml_service" / "main.py"
PYPROJECT = Path(__file__).resolve().parents[1] / "pyproject.toml"

# Distributions that only the parsing extra installs.
OPTIONAL = {"pdfplumber", "trafilatura", "pytesseract", "PIL", "docx", "pypdf"}


def _module_level_imports(source: str) -> set[str]:
    """Top-level import names only — the ones that run when the module is loaded."""
    tree = ast.parse(source)
    names: set[str] = set()
    for node in tree.body:
        if isinstance(node, ast.Import):
            names.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            names.add(node.module.split(".")[0])
    return names


def test_no_optional_parser_is_imported_at_module_level() -> None:
    imported = _module_level_imports(MAIN.read_text())

    assert not (imported & OPTIONAL), (
        "these are only installed by the parsing extra, so importing them at module "
        "level stops the service starting without it: " + ", ".join(sorted(imported & OPTIONAL))
    )


def test_the_parsing_package_itself_is_not_imported_at_module_level() -> None:
    # ml_service.parsing pulls pdfplumber in transitively, so it is the same problem
    # wearing this project's own name.
    tree = ast.parse(MAIN.read_text())
    for node in tree.body:
        if isinstance(node, ast.ImportFrom) and node.module:
            assert not node.module.startswith("ml_service.parsing"), (
                "ml_service.parsing imports pdfplumber, so importing it at module level "
                "stops the service starting without the parsing extra"
            )


def test_python_multipart_is_a_base_dependency() -> None:
    # FastAPI needs it to declare an UploadFile endpoint at import time, so a lazy import
    # cannot cover it and the base install has to carry it.
    text = PYPROJECT.read_text()
    base = text.split("[project.optional-dependencies]")[0]
    assert "python-multipart" in base, (
        "python-multipart must be a base dependency: without it FastAPI raises while "
        "declaring the upload endpoint, whatever the handler imports"
    )
