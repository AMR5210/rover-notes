# Connection pooling

The pool is sized against the database's capacity, not against the number of concurrent
requests.

HikariCP runs with maximumPoolSize = 20 against a Postgres instance whose max_connections
is 100, which leaves headroom for the ingestion workers, migrations, and an interactive
session. Connection lifetime is capped at 30 minutes and idle timeout at 10 minutes, so a
connection carrying accumulated backend state is recycled rather than held indefinitely.

Virtual threads make pool sizing more important rather than less. Thousands of virtual
threads can queue on 20 connections without exhausting memory, which turns connection
starvation into latency instead of an immediate error. leakDetectionThreshold is set to 5
seconds so a connection held across a slow model call is reported rather than quietly
draining the pool.

No JDBC call is issued from inside a synchronized block, because a virtual thread that
blocks there pins its carrier and the pool's effective concurrency collapses to the
carrier count.
