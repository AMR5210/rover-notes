-- Rover Notes 2.0 — request rate limits
--
-- Until now nothing bounded how often a caller could ask. The spend cap in llm_usage
-- bounds what a request costs in model tokens, which leaves every request that spends no
-- tokens — search, registration, a password reset — unbounded, and leaves even a capped
-- owner free to spend their allowance as fast as the instance will serve it.
--
-- One row per bucket and subject, holding a token count and the moment it was last
-- brought up to date. Refill is computed on read from the elapsed time rather than by a
-- scheduled job, so an idle bucket costs nothing and no timer has to run.
--
-- In the database rather than in memory. The application is one deployable but may run as
-- several tasks, and an in-process limiter multiplies the effective limit by the task
-- count — a limit that quietly weakens as a service is scaled out is worse than none,
-- because nothing about it looks wrong. The cost is one statement per request against a
-- table that stays small; the check is a single upsert holding no lock across the request
-- it guards.

create table rate_limits (
    -- Which limit this row counts against: 'api', 'ingest', or 'auth'. A string rather
    -- than an enum so adding a bucket is configuration and code, not a migration.
    bucket      text        not null,
    -- Whom it counts. An account id for an authenticated caller, and a client address for
    -- the endpoints that answer someone who has no account yet. Text for that reason: the
    -- two are not the same kind of value and forcing them into one type would mean
    -- inventing an account for an address.
    subject     text        not null,
    -- Fractional on purpose. An integer count would discard the part of a token earned
    -- between two requests, so a caller arriving faster than one token per second would
    -- be refilled at nothing and held below the configured rate indefinitely.
    tokens      numeric     not null,
    refilled_at timestamptz not null,

    primary key (bucket, subject)
);

-- Buckets that have refilled to capacity carry no information and can be deleted; this
-- index is what makes finding them cheap. No sweeper runs yet — at one row per active
-- subject per bucket the table is bounded by the number of callers, not by traffic — but
-- the unauthenticated bucket is keyed on client address, which is unbounded in principle.
create index rate_limits_refilled_at_idx on rate_limits (refilled_at);
