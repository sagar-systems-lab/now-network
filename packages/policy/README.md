# Policy

Pure, versioned policy functions for deterministic decisions and bounded recommendations.

Policy templates are JSON data. The parser rejects unknown critical fields and validates cross-field
requirements before a template can be used. Published template keys are versioned; funded operations
persist their own snapshot and digest.

The package does not read databases, RPC endpoints, wallets, or the system clock. Callers supply all
inputs explicitly. Reward suggestions are non-authoritative, while freshness and conflict decisions
remain deterministic and reason-coded.
