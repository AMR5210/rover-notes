# Rate limiting

Limits are enforced per authenticated subject rather than per IP address, because the API
is called by agents sitting behind shared egress.

The implementation is a token bucket held in Postgres and refilled lazily on read. The
query bucket allows 60 requests per minute with a burst of 20. Ingestion has its own
bucket at 10 documents per minute, since a single document costs an embedding pass over
every one of its chunks. Exceeding a bucket returns 429 with a Retry-After header
carrying the seconds until the next token.

Buckets live in the database rather than in memory because the API runs more than one
task, and an in-process limiter would multiply the effective limit by the task count. The
bucket row is updated in a single statement, so no lock is held across the request it is
guarding.

Model spend has a separate ceiling, checked before a synthesis call rather than after. A
subject over its daily token budget still receives retrieved passages, and only the
generated answer is withheld.
