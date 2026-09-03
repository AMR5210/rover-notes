# Multi-tenant isolation

The deployment is single-tenant today, and the path to more than one tenant is designed
around one measured behaviour of the vector index.

Every table carries owner_id and every query filters on it. For lexical and relational
queries the planner applies that predicate through a B-tree index as expected. The dense
channel behaves differently: HNSW walks the graph first and applies the filter
afterwards, so a selective owner_id can return fewer rows than the requested limit even
when matching rows exist deeper in the graph.

With one owner the predicate selects nearly the whole corpus and the effect is not
reachable. It becomes reachable as soon as a second tenant is added. The fix is
pgvector's hnsw.iterative_scan, which keeps walking the graph until enough rows survive
the filter; per-tenant partial indexes are the alternative while the tenant count stays
below roughly 50.

Row-level security is deferred. It moves the check into the database at some cost in
plan complexity on the hot path, and it becomes the right choice when tenants are
mutually untrusted rather than merely separate.
