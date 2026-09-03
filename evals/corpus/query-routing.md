# Query routing

Not every query needs the full pipeline, so an inbound query is classified before
retrieval runs.

Classification uses a small model with a fixed three-way label set: known-item,
semantic, and conversational. A known-item query (an identifier, a filename, an error
class) weights the lexical channel and skips query expansion. A semantic query runs both
channels at full candidate depth. A conversational turn with no retrievable content is
answered without touching the index at all.

The classifier adds roughly 200 ms to a request, which is why its verdict is cached by
normalised query string for one hour. A cache miss that also times out falls back to the
semantic path, the more expensive but never-wrong default.

The chosen route is recorded per request so the eval harness can report metrics per
route rather than only in aggregate. Known-item queries carry most of the reciprocal
rank loss in the current baseline, and separating them keeps that visible instead of
averaged away.
