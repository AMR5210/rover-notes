# The agent loop

The agent answers by calling retrieval tools in a loop rather than by receiving one
pre-assembled context.

On each turn the model may call search, get_document, or expand_entity. Results are
appended to the conversation and the model decides whether to call again. The loop is
capped at 5 iterations and 20,000 tokens of accumulated tool output; reaching either cap
ends the loop and the answer is synthesised from whatever has been gathered.

The loop runs on claude-sonnet-5, the tier where multi-step tool use is reliable enough
to run without a supervising pass. Individual tool results are truncated to 2,000
characters, so one broad search cannot consume the whole budget in a single turn.

Every call is recorded with its arguments and its latency. A loop that hits the iteration
cap is logged as a distinct outcome, because repeated capping usually indicates a
retrieval failure being compensated for with more calls rather than a genuinely
multi-step question.
