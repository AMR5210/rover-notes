# Chunking strategy

Documents are split into overlapping windows before embedding.

The baseline uses fixed-size character windows of 1600 characters, roughly 400 tokens at
four characters per token for English prose, with 200 characters of overlap. Windows are
snapped to word boundaries so no token is split across a chunk edge.

Overlap exists because a fact split across a boundary is retrievable from neither side.
Carrying the tail of each window into the next keeps boundary-spanning sentences intact
in at least one chunk.

Every chunk records the character offsets of its span in the source document. This is
what lets an answer cite the exact passage it drew from rather than the document as a
whole.

Semantic chunking replaces fixed-size windows in Week 4, splitting on topic shifts
rather than character counts. Whether it is kept depends on the measured change to
nDCG@10.
