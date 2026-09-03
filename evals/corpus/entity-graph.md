# The entity graph

Retrieved passages are linked through the entities they mention, so a result set can be
expanded along relationships the text states explicitly.

Extraction runs at ingest against the contextualised chunk and is constrained to four
types: person, project, technology, and decision. A constrained type set is what keeps
the graph joinable, since free-form extraction produces near-duplicate labels that never
match across documents.

Storage is three ordinary tables: entity, mention, and edge. A mention carries the chunk
identifier and the character span, so an entity always resolves back to the passage that
introduced it. Names are normalised by lowercasing and trimming punctuation, and
remaining near-duplicates are merged when their embeddings sit within 0.05 cosine
distance of each other.

An edge records a predicate string and the chunk that supports it. An edge with no
supporting mention is never written, which keeps the graph auditable against the corpus
rather than against the model that read it.
