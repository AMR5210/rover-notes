# Semantic chunking

The Week 4 alternative to fixed windows splits a document where its meaning shifts rather
than at a character count.

Each sentence is embedded, and the cosine distance between consecutive sentences forms a
curve across the document. A breakpoint is taken wherever that distance exceeds the 95th
percentile for that document, which makes the threshold relative to how varied the
document already is. The resulting pieces are clamped to between 300 and 2,400 characters
so a uniform document does not collapse into one enormous chunk.

The cost is one embedding call per sentence at ingest, roughly 30 times the embedding
work of fixed windows on a typical document. In exchange it drops the fixed overlap
entirely, since a boundary chosen at a topic shift is by construction a poor place for a
fact to straddle.

It is kept only if nDCG@10 improves. The comparison runs both strategies over the same
corpus with every downstream stage held constant, and the measured delta is recorded in
the evaluation history alongside the commit that produced it.
