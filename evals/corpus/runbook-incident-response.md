# Incident response runbook

What to do when the service is degraded, in the order the on-call engineer should do it.

## Severity and paging

Three levels. SEV1 is the API returning errors for more than 2% of requests over a
five-minute window, or answers being served from an empty index; it pages immediately.
SEV2 is degraded quality without failure: reranking timing out, the queue draining
slower than it fills, and pages during working hours only. SEV3 is anything a ticket
can carry.

The alert thresholds live beside the Prometheus rules and are deliberately loose. A
threshold tight enough to catch every real problem also fires on deploys, and an on-call
rotation that learns to ignore its pager is worse than no pager.

## First five minutes

Check the health endpoint before anything else. It reports liveness, readiness, and the
state of each downstream dependency separately, so a single call distinguishes "the
application is down" from "the application is up and Postgres is not".

Then read the request rate and error rate together. A drop in both usually means an
upstream problem (a load balancer, DNS, a certificate) and nothing in this service
will fix it. A flat request rate with rising errors is the service's own fault.

## The four failures seen so far

**Pool exhaustion.** Every request waits on a connection and none is free. Symptom is a
latency cliff rather than errors: p50 climbs from 40 ms to several seconds while the
error rate stays near zero. Confirm with the active and idle gauge; the fix is almost
never a bigger pool, because the pool is sized against what the database can serve.

**Model server out of memory.** The embedding container is killed and restarts. Requests
fail with connection refused for around 40 seconds while the model reloads. Ingestion
retries through the queue and recovers on its own; search does not, and returns lexical
results alone until the container is back.

**Queue backlog.** Writes succeed but do not become searchable. The depth gauge grows
monotonically. Usually a poison message: one document that throws on every attempt and
blocks nothing else, but inflates the depth until it exhausts its retries.

**Index and model disagreement.** The most damaging failure, because nothing errors. A
model change that alters vector dimensions or normalisation makes previously indexed
vectors meaningless while search continues to return results. Quality collapses and
availability looks perfect.

## Rolling back

Application rollback is a task-definition revert and takes about 90 seconds. Schema
rollback is not symmetric: migrations are forward-only, so a rollback of code across a
migration boundary requires the previous code to tolerate the new schema. That is the
constraint behind the expand-and-contract discipline, and it is why a column is never
dropped in the same release that stops writing to it.

## After the incident

Write the timeline before the analysis. The interesting quantity is not what broke but
how long it took to notice, and that is only visible from timestamps recorded while the
memory is fresh. Any incident where detection took longer than repair becomes a
monitoring change rather than a code change.
