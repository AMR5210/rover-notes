# Cost model

Model choice is a cost decision as much as a quality one.

Synthesis runs on Claude Sonnet 5. Cheap classification and contextual chunk annotation
run on Claude Haiku 4.5. The evaluation judge runs on Claude Opus 5, because a judge
must be at least as capable as the model it grades.

Self-hosting the language model was priced and rejected. An A10G instance costs roughly
730 dollars per month, while a typical request of 4000 input and 500 output tokens costs
about 0.0065 dollars against Haiku. Break-even is around 112,000 requests per month, or
3700 per day, which is roughly two orders of magnitude above expected load.

Embedding and reranking have the opposite economics. They are small, run on CPU, and are
called on every chunk at ingest and every query at retrieval, so self-hosting them is
clearly cheaper than per-token pricing.

Prompt caching matters most for contextual retrieval, where a document is re-sent once
per chunk. Cache reads bill at roughly a tenth of input price, turning a 100-chunk
document from 10 million tokens into about 1.1 million.
