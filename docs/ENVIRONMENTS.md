# Environments

NOW V1 uses intentionally small environment scope.

## local

Local development and deterministic tests.

## integration

Isolated integration resources for database, storage, realtime, API, and program tests.

## devnet / demo

Solana devnet plus the controlled demo backend/runtime.

## final demo

The qualified release identity used for the submission demo.

## Hosted function configuration

Supabase-hosted Edge Functions provide the project URL, database URL, anon key and service-role key to the function runtime.

`now-api` additionally requires:

- `NOW_SOLANA_RPC_URL`
- `NOW_SOLANA_CLUSTER`
- `NOW_REWARD_MINT`
- `NOW_REFRESH_LIFETIME_SECONDS`
- `NOW_EVIDENCE_LEAD_SECONDS`
- `NOW_EVIDENCE_STORAGE_BUCKET`
- `NOW_READY_TOKEN`

Optional API/runtime tuning:

- `NOW_CLAIM_DURATION_SECONDS`
- `NOW_EVIDENCE_MAX_BYTES`

`now-worker` additionally requires:

- `NOW_SOLANA_RPC_URL`
- `NOW_WORKER_TOKEN`
- `NOW_SETTLEMENT_VERIFIER_KEYPAIR_JSON`

Optional worker metadata/tuning:

- `NOW_WORKER_BATCH_LIMIT`
- `NOW_SETTLEMENT_RECONCILE_DELAY_MS`
- `NOW_WORKER_ID`
- `NOW_BUILD_VERSION`

Both functions disable the platform JWT gate. The API performs session verification for authenticated routes while preserving public health/state reads, and the worker authenticates its own bearer token.

## Hard rules

- No mainnet dependency is required for the hackathon V1.
- No real-money requirement.
- No large paid VM, Kubernetes, Kafka, required Redis, paid AI, or paid map dependency.
- Privileged secrets never live in Git or the Android APK.
