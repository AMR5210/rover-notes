# Embedding and reranking models

Both models are self-hosted and deliberately small.

Embeddings use BAAI/bge-small-en-v1.5, which produces 384 dimensions from roughly 33
million parameters. The dimension must match the vector(384) column in the schema;
changing the model means adding a column and backfilling.

Reranking uses cross-encoder/ms-marco-MiniLM-L-6-v2, around 22 million parameters. Model
size drives the latency budget directly: MiniLM scores 40 query-document pairs in
roughly 50 to 150 milliseconds on CPU, where a 570 million parameter reranker such as
bge-reranker-v2-m3 would take one to three seconds. Reranking is therefore applied to
the top 40 candidates rather than the top 100.

The embedding model is a bi-encoder: query and document are encoded separately and never
interact. A cross-encoder scores the pair jointly, which is far more accurate and far
too slow to run across the whole corpus. That asymmetry is why retrieval has two stages,
cheap recall followed by expensive precision.

Both run through Hugging Face Text Embeddings Inference, which provides dynamic batching
and warm model loading over HTTP.
