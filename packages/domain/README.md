# Domain

Deterministic domain functions that are safe to exercise without network, storage, RPC, or wallet
dependencies.

The package owns freshness derivation and explicit transition reducers for refresh, claim, evidence,
verification, and product-level payment state. Illegal transitions and failed transition guards
throw typed domain errors rather than mutating state optimistically.

Freshness derives `LIVE`, `AGING`, or `STALE` from authoritative timestamps. Refresh progress and
conflict remain separate overlays rather than replacing freshness.
