# Runbooks

These runbooks cover the minimum operator actions for the hosted NOW Network runtime.

## Start here

- Lifecycle or payment incident: [Incident reconstruction](INCIDENT_RECONSTRUCTION.md)
- Readiness is degraded or the worker is stale: [Runtime degraded](RUNTIME_DEGRADED.md)
- Settlement result is ambiguous or delayed: [Settlement reconciliation](SETTLEMENT_RECONCILIATION.md)
- A privileged credential may be exposed: [Security response](SECURITY_RESPONSE.md)

## Operating rules

- Treat PostgreSQL, the settlement program and finalized receipts as authoritative state.
- Treat realtime delivery as a hint; reconnects are followed by snapshot reconciliation.
- Never blindly resend a transaction whose chain outcome is unknown.
- Never paste privileged secret values into issue trackers, chat, logs or documentation.
- Never re-run one-time protocol initialization during incident recovery.
- Preserve identifiers first: refresh_id, settlement signature, request ID and timestamps.

Lifecycle reconstruction utility: scripts/ops/reconstruct-lifecycle.sh
