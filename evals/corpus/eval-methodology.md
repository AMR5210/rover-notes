# Evaluation methodology

Retrieval quality is measured before it is improved.

The golden set pairs queries with the documents that genuinely answer them. Relevance is
expressed as a document slug plus an optional substring, never a chunk ID, because chunk
IDs change whenever the chunking strategy changes.

Three ranking metrics are reported. nDCG@10 is the headline number: it discounts each
relevant result by the logarithm of its rank, so ordering matters and not just
membership. Recall@k measures how many known-relevant documents appear at all.
Reciprocal rank measures how far a reader must scroll before the first useful result.

Generation is measured separately for faithfulness and answer relevance, judged by a
model at least as capable as the one being graded.

The golden set includes queries the corpus cannot answer. Declining to answer is correct
behaviour and needs measuring directly, because a system that always answers scores well
on every other metric while hallucinating freely.

Continuous integration fails the build when nDCG@10 regresses by more than three
percent.
