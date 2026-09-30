# Runtime degraded

Use this when readiness reports DEGRADED or the background worker appears stale.

## 1. Separate API health from readiness

/health establishes that the API process is reachable.

Authenticated /ready additionally evaluates worker freshness and bounded verification, settlement and outbox backlogs.

A healthy HTTP process can therefore be reachable while readiness is degraded.

## 2. Check the worker heartbeat

Inspect app.runtime_health for:

- worker_id
- last_seen_at
- last_job_at
- build_version
- last_result

A stale heartbeat or FAILURE result is operationally significant.

## 3. Check backlog age, not only backlog count

The runtime readiness policy degrades when pending work becomes older than its configured bound.

Inspect the runtime backlog snapshot for:

- verification backlog
- settlement backlog
- realtime outbox backlog

A short non-zero queue can be normal. An aging queue requires investigation.

## 4. Check scheduler delivery

The hosted worker is expected to be invoked by the scheduled job. Confirm that recent scheduler runs succeeded before changing application code.

If scheduler calls fail, verify that the worker authentication value stored for the scheduler still matches the hosted worker secret. Do not print either value while comparing or rotating it.

## 5. Recovery order

1. restore scheduler-to-worker authentication if broken
2. restore database/API connectivity if unavailable
3. allow the worker to reconcile existing durable work
4. reconstruct one affected refresh to confirm convergence
5. only then consider code or configuration changes

Do not create duplicate settlement work manually and do not reset durable rows simply because the queue is old.
