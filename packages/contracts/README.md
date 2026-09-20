# Contracts

Canonical wire vocabulary shared by the client and API.

JSON Schema is the language-neutral source for cross-platform enum values. TypeScript exports live
under `src/`, with tests preventing schema drift. Database migration tests also verify that
persisted lifecycle enums stay aligned with the shared contracts.

Current contract groups:

- state kind and persisted state type
- freshness status and overlays
- verification class, result and execution status
- refresh, evidence, settlement, refund and receipt lifecycle status
- backend outbox status and realtime visibility
- payment status
- error categories
- reason codes
- API error codes
