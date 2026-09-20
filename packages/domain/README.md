# Domain

Small, deterministic domain functions that are safe to exercise without network or storage
dependencies.

The freshness function derives `LIVE`, `AGING`, or `STALE` from authoritative timestamps. Refresh
progress and conflict remain separate overlays rather than replacing freshness.
