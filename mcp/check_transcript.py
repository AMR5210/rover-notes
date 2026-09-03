"""Checks a Claude Code transcript for evidence the MCP tools answered the question.

Reads the `stream-json` transcript on stdin and asserts three things: that at least one
rover-notes tool was called, that no tool call came back as an error, and that the final
answer contains the value seeded for this run. The seeded value is the point of the
check — it exists only in the document the harness just wrote, so an answer containing it
came through the tools rather than from the model.
"""

import json
import sys


def main() -> int:
    nonce = sys.argv[1]
    called: list[str] = []
    errors: list[str] = []
    answer = ""

    for line in sys.stdin:
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue

        content = event.get("message", {}).get("content")
        for block in content if isinstance(content, list) else []:
            if block.get("type") == "tool_use" and block["name"].startswith("mcp__rover-notes__"):
                called.append(block["name"])
            if block.get("type") == "tool_result" and block.get("is_error"):
                errors.append(str(block.get("content"))[:200])

        if event.get("type") == "result":
            answer = event.get("result") or ""

    for name in called:
        print(f"called {name}")

    if not called:
        print("FAIL: no rover-notes tool was called", file=sys.stderr)
        return 1
    if errors:
        print(f"FAIL: {len(errors)} tool call(s) returned an error: {errors[0]}", file=sys.stderr)
        return 1
    if nonce not in answer:
        print(f"FAIL: the answer does not contain the seeded value {nonce}", file=sys.stderr)
        print(answer, file=sys.stderr)
        return 1

    print(f"\nPASS: answered {nonce} through {len(called)} tool call(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
