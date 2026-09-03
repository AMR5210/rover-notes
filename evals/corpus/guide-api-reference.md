# API reference

Every endpoint the service exposes, with the parameters that matter and the errors they
return.

## Notes

`POST /api/notes` creates a document from a title and a body and returns its identifier
immediately. Indexing happens afterwards, so a document is retrievable a short time after
the call returns rather than at the moment it does.

`PUT /api/notes/{id}` replaces the body and triggers reindexing. Chunks belonging to the
previous version are removed in the same transaction that writes the new ones, so a
search never sees a mixture of two versions.

`DELETE /api/notes/{id}` removes the document and its chunks. Deletion is immediate
rather than soft, because a knowledge base that quietly keeps what a user deleted is a
liability rather than a feature.

## Search

`GET /api/search` takes a query string and returns ranked passages, each with the
character offsets of its span in the source document so a caller can highlight or cite
the exact text. The result limit is capped server-side; a caller asking for the whole
corpus gets the cap rather than an error.

Three optional parameters exist so that a single running instance can be scored one stage
at a time: one selects the retrieval channel, one forces the cross-encoder on or off, and
one enables or suppresses the stage-zero classifier. Left unset, each serves the
configured default, which is what ordinary traffic receives. Naming a channel explicitly
suppresses the classifier, since an explicit channel is an instruction rather than a
preference.

## Answers

`POST /api/ask` takes a question, retrieves passages, and streams a synthesised answer
with numbered references back to the passages it used. The transport is the one described in the
streaming design note; a client needs only an event-source reader to consume it.

The stream carries three event types: tokens as they are generated, a reference block
once retrieval has settled, and a terminal event carrying token counts for the call. A
client that ignores everything except the token events still renders a correct answer.

## Errors and status codes

A malformed query parameter returns 400 with a message naming the parameter and the
values it accepts. An unknown document returns 404. A request that exceeds the per-subject
limit returns 429 with a retry hint in seconds rather than an absolute timestamp, since
client clocks cannot be relied upon.

Downstream failures are deliberately not surfaced as 5xx where a degraded answer is
possible. If the cross-encoder is unavailable the fused ranking is served instead, and if
the embedding server is unavailable the lexical channel answers alone. Both are worse
answers than the intended one and both are better than an error page.

## Authentication

Every endpoint except the health probes requires a bearer token, validated against the
issuer's published keys. The service validates tokens and does not mint them; identity is
somebody else's problem, deliberately.
