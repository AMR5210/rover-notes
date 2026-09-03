# Security model

What the service trusts, what it verifies, and what it deliberately does not do.

## Identity

Tokens are issued elsewhere and verified here against the issuer's published signing keys,
fetched on first use and cached. The service has no user table, no password storage, and
no session state. That is a deliberate reduction in scope: credential handling is the part
of an application most likely to be got wrong, and the cheapest way to get it right is not
to do it.

Key rotation is handled by refetching on an unknown key identifier rather than on a timer.
A timer is either too slow, leaving a window where valid tokens are rejected, or fast
enough to hammer the issuer for no reason.

## Scoping every query

Every row that belongs to a user carries an owner column, and every statement that reads
those rows filters on it. The filter is in the query rather than in a service layer above
it, because a service layer is one refactor away from being bypassed and a query is not.

Enforcing the same rule inside the database would be stronger still, and is not done
today. The reason is that it moves the identity into the connection, which conflicts
with a pooled connection shared by concurrent requests; making that safe means setting and
resetting a session variable per request, and a missed reset is a cross-tenant read. The
tradeoff is revisited when there is more than one tenant to isolate.

## Secrets

Nothing secret is in the repository, and nothing secret is in an image. Configuration
arrives as environment variables at task start, sourced from a managed store. The
development profile has defaults for everything except the model API key precisely so that
a missing secret fails at startup rather than at first use.

## What is logged

Request paths, status codes, timings, and identifiers. Never a query string, never a
document body, never an answer. The temptation to log queries for debugging is strong and
the cost is a second copy of the user's private notes in a system with different retention
and different access control.

Token counts and costs are recorded per request, which is a spend measurement rather than
a content one, and they are attributed to an owner so a runaway cost has a subject.

## Prompt injection

A retrieved passage is untrusted input that reaches a language model, which is the same
shape as a stored cross-site scripting problem. The mitigation is not clever prompting; it
is that the model has no tools that act on the world in the synthesis path. A model that
can only write text can only write bad text.

The agent path is different, because there the model calls tools. Every tool there is
scoped to the same owner as the request that started the loop, and none of them writes.

## What this does not defend against

A compromised token grants full access to that owner's notes for its lifetime, which is an
hour. Shortening it trades availability for exposure and the current value is a guess
rather than a measurement. Nothing here defends against a malicious operator with database
access; the threat model assumes the infrastructure is trusted and the users are not.
