# Streaming responses

Answers stream to the browser over Server-Sent Events rather than a WebSocket, because
the channel only ever carries server-to-client data.

The endpoint returns text/event-stream and emits four named event types: token for
incremental text, citation for a resolved span, usage for the final counts, and done to
close the stream. A client that drops reconnects with Last-Event-ID, and the server
resumes from the recorded position instead of regenerating the answer from the start.

Each stream holds a request thread for its lifetime, which is affordable because the API
runs on virtual threads: a stream waiting on the model parks and releases its carrier
thread. A 15 second heartbeat comment keeps proxies and load balancers from closing a
connection they read as idle.

Retrieval finishes before the first token is produced, so the citation set is known up
front and each citation is emitted as soon as the text references it. Time to first token
is therefore dominated by the retrieval stage rather than by the model.
