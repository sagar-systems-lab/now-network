# Incident reconstruction

Use this when a requester, contributor or operator reports a lifecycle discrepancy.

## Required identifier

Prefer a refresh_id. If only the Solana settlement signature is known, the reconstruction tool resolves the owning refresh first.

## Run

Against a local/integration database:

    DB_CONTAINER=<postgres-container> \
      scripts/ops/reconstruct-lifecycle.sh --refresh-id <uuid>

Against an operator database connection:

    DATABASE_URL=<secure-postgres-connection> \
      scripts/ops/reconstruct-lifecycle.sh --refresh-id <uuid>

Reverse lookup from a settlement signature:

    DATABASE_URL=<secure-postgres-connection> \
      scripts/ops/reconstruct-lifecycle.sh \
        --settlement-signature <solana-signature>

Do not place the database URL in shell history, tickets or screenshots.

## Report sections

The output is a sanitized JSON report containing:

- refresh lifecycle state and revision
- funding contributions
- claims
- evidence identities and statuses
- verification results
- settlement attempts and chain signature
- final receipt
- related domain-event sequence
- outbox publication state
- realtime publication state

Wallet addresses, evidence answers, raw media data and privileged credentials are deliberately omitted.

## Reading the result

A normal completed path should converge on:

1. refresh reaches an available/claimed evidence path
2. evidence reaches VERIFIED
3. verification result reaches VERIFIED
4. settlement reaches FINALIZED
5. receipt reaches FINAL
6. receipt settlement signature matches the settlement operation
7. related outbox publication reaches PUBLISHED

A missing realtime row is not sufficient to declare authoritative state missing. Check the database lifecycle and outbox first.

## Preserve before changing anything

Record:

- refresh_id
- settlement signature if present
- settlement operation_id
- latest statuses and revisions
- worker heartbeat time
- relevant request ID from structured HTTP logs
- UTC timestamps

Do not mutate database rows to make a report look consistent. Fix the underlying recovery path or let the existing reconciliation mechanism converge.
