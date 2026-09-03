"""Model access for the generation eval.

Judging generated text needs a model, and this project has two ways to reach one: an API
key, or the Claude Code CLI already installed for the MCP acceptance check. Both are
implemented so the harness can run before a key is configured — an eval that cannot be
executed measures nothing, which is the state the generation metrics were in.

**The judge is a different model from the generator.** A model scoring its own output
rates it higher than an independent judge does, so judging Sonnet's answers with Sonnet
would report a number inflated by an amount this harness cannot see. The generator is set
by ``spring.ai.anthropic.chat.options.model``; the judge defaults to a different one and
the report records both, so a run where they match is visible rather than assumed.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from typing import Any, Protocol

__all__ = ["ApiJudge", "CliJudge", "Judge", "default_judge", "parse_json"]

# Fenced blocks are common in model output even when the prompt asks for bare JSON.
FENCE = re.compile(r"^\s*```(?:json)?\s*|\s*```\s*$", re.MULTILINE)


class Judge(Protocol):
    """Something that answers a prompt with text. Named so the report can record it."""

    name: str

    def __call__(self, prompt: str) -> str: ...


class CliJudge:
    """Judges through the Claude Code CLI, on whatever credentials it already holds.

    Tools are disabled: a judge that could search the corpus would be scoring answers
    against evidence the generator never saw.
    """

    def __init__(self, model: str = "opus", timeout: float = 180.0) -> None:
        self.model = model
        self.timeout = timeout
        self.name = f"claude-cli:{model}"

    def __call__(self, prompt: str) -> str:
        result = subprocess.run(
            [
                "claude",
                "-p",
                prompt,
                "--model",
                self.model,
                "--allowedTools",
                "",
                "--output-format",
                "json",
            ],
            capture_output=True,
            text=True,
            timeout=self.timeout,
            stdin=subprocess.DEVNULL,
            check=True,
        )
        return str(json.loads(result.stdout)["result"])


class ApiJudge:
    """Judges through the Anthropic API. Requires ``ANTHROPIC_API_KEY`` and the SDK."""

    def __init__(self, model: str = "claude-opus-5", max_tokens: int = 4096) -> None:
        import anthropic  # imported here so the module loads without the evals extra

        self.client = anthropic.Anthropic()
        self.model = model
        self.max_tokens = max_tokens
        self.name = f"anthropic-api:{model}"

    def __call__(self, prompt: str) -> str:
        message = self.client.messages.create(
            model=self.model,
            max_tokens=self.max_tokens,
            messages=[{"role": "user", "content": prompt}],
        )
        return "".join(block.text for block in message.content if block.type == "text")


def default_judge(backend: str = "auto", model: str | None = None) -> Judge:
    """Picks a backend, preferring the CLI when one is available.

    ``auto`` exists so a run does not fail for the reason the generation metrics went
    unmeasured in the first place — no key configured.

    It prefers the CLI on cost. Judging costs more than the answers it scores: the judge
    reads the same sources the generator did, plus the answer, and reasons over them, so
    a full run measured at roughly three times the API spend of the run being measured.
    The CLI reaches a model on an existing subscription, which keeps API credit going to
    the system under test rather than to the instrument.

    ``--judge api`` is the reproducible choice, since it pins an exact model ID where the
    CLI takes an alias. The chosen backend is recorded in the report either way, because
    the two are not interchangeable for reproducing a number.
    """
    if backend == "cli" or (backend == "auto" and shutil.which("claude")):
        return CliJudge(model or "opus")
    if backend == "api" or (backend == "auto" and os.environ.get("ANTHROPIC_API_KEY")):
        return ApiJudge(model or "claude-opus-5")
    raise SystemExit("No judge available: set ANTHROPIC_API_KEY, or install the Claude Code CLI.")


def parse_json(text: str) -> dict[str, Any]:
    """Reads the judge's reply as JSON, tolerating what models put around it.

    Two things happen in practice even when the prompt asks for bare JSON: the object
    arrives inside a fenced block, and it arrives with a sentence of commentary after it.
    Both are recovered by taking the first balanced object rather than parsing the whole
    reply, which a full-set run needs — over hundreds of calls, a strict parse fails
    eventually, and it failed here after 130 questions.

    A reply with no object at all is an error rather than a zero. Scoring it as a failed
    answer would blame the system under test for a fault in its judge.
    """
    stripped = FENCE.sub("", text).strip()
    start = stripped.find("{")
    if start == -1:
        raise ValueError(f"judge did not return JSON: {stripped[:200]!r}")

    depth = 0
    in_string = False
    escaped = False
    for index, char in enumerate(stripped[start:], start=start):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                try:
                    parsed = json.loads(stripped[start : index + 1])
                except json.JSONDecodeError as exc:
                    raise ValueError(f"judge did not return JSON: {stripped[:200]!r}") from exc
                if not isinstance(parsed, dict):
                    raise ValueError(f"judge returned {type(parsed).__name__}, expected an object")
                return parsed

    raise ValueError(f"judge returned an unterminated object: {stripped[:200]!r}")
