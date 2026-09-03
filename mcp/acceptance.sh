#!/usr/bin/env bash
# Drives the MCP server from a real Claude Code client and checks the answer came
# through the tools. See mcp/README.md for what this establishes.
set -euo pipefail

API="${API_URL:-http://localhost:8080}"
CONFIG="$(dirname "$0")/claude-code.json"

command -v claude >/dev/null || { echo "claude CLI not found"; exit 1; }
curl -fsS -m 5 "$API/actuator/health" >/dev/null || { echo "API not reachable at $API"; exit 1; }

# Generated per run, so a correct answer cannot come from the model's own knowledge or
# from a document left behind by an earlier run. Kept to three digits deliberately: an
# arbitrarily large value reads as implausible for the quantity it describes, and a model
# that doubts the document reports the number with a caveat or a thousands separator —
# which measures its credulity rather than whether retrieval worked. The document is
# titled and worded as the operational note it imitates for the same reason.
NONCE="$((100 + RANDOM % 900))"
FACT="The drain script waits ${NONCE} seconds between nodes."

DOC_ID="$(curl -fsS -X POST "$API/api/notes" -H 'content-type: application/json' \
  -d "{\"title\":\"glacier-drain-procedure\",\"content\":\"# Glacier drain procedure\\n\\nThe staging cluster is codenamed Glacier. ${FACT} The wait exists so in-flight rerank requests finish rather than being cut off mid-batch.\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"

# The probe document must not outlive the check: the eval harness scores this same
# corpus and asserts its document count.
cleanup() { curl -fsS -X DELETE "$API/api/notes/$DOC_ID" -o /dev/null || true; }
trap cleanup EXIT

# Indexing is asynchronous; the write returns before the text is searchable.
for _ in $(seq 60); do
    [ "$(curl -fsS "$API/actuator/metrics/rover.ingestion.backlog" \
        | python3 -c 'import json,sys; print(int(json.load(sys.stdin)["measurements"][0]["value"]))')" = "0" ] && break
    sleep 1
done

claude -p "How many seconds does the Glacier drain script wait between nodes, and why? Use the rover-notes tools, and cite the document title." \
    --mcp-config "$CONFIG" \
    --allowedTools "mcp__rover-notes__search,mcp__rover-notes__get_document,mcp__rover-notes__list_documents" \
    --output-format stream-json --verbose < /dev/null \
    | python3 "$(dirname "$0")/check_transcript.py" "$NONCE"
