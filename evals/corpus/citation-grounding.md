# Citation grounding

An answer is accepted only if every claim in it points at a retrieved span.

The synthesis prompt requires each sentence to carry a marker of the form
[doc-slug:start-end], where the range is the character offset span recorded on the
chunk. A post-processing pass parses the markers, checks that each names a chunk that was
actually present in the context window, and rejects any answer holding an unresolvable
reference.

Spans are widened to sentence boundaries before display, so a citation opens at readable
text rather than mid-word. The stored offsets themselves are never rounded, because the
harness compares them against the source document exactly.

When no retrieved chunk supports a claim, the expected behaviour is a refusal that names
what was missing. That path is exercised by the unanswerable queries in the golden set,
where a refusal scores as correct rather than as a miss, and it is the reason
faithfulness is graded separately from answer relevance.
