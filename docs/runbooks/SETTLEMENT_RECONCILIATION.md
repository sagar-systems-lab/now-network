# Settlement reconciliation

Use this when a settlement broadcast times out, returns an ambiguous result, or remains pending.

## Safety rule

A lost RPC response does not prove that the transaction was not submitted.

The worker persists the signed transaction identity before broadcast. When the result is ambiguous, the operation enters reconciliation rather than creating a new logical payment.

## Start from the persisted signature

If a chain signature exists:

1. reconstruct the lifecycle from that signature
2. inspect the settlement status, attempt count and last error
3. query Solana for that exact signature
4. compare the observed chain commitment with the persisted operation
5. let the worker advance or reconcile the same logical operation

Do not build and send a different settlement transaction while the previous signature remains unresolved.

## Expected states

- SUBMITTING: a signed attempt has been reserved
- SUBMITTED: broadcast was accepted
- VERIFYING: chain outcome is still being established
- CONFIRMED: observed but not yet finalized
- FINALIZED: terminal successful settlement
- NOT_SETTLED: prior attempt was proven absent/expired and is safe for controlled retry
- FAILED: authority or correctness conflict requires investigation

## Worker restart

A worker restart must reuse the persisted signed attempt when one exists. It must not manufacture a second logical settlement merely because the previous process died.

## Final proof

The final receipt must bind to:

- the same refresh
- the verification result
- the settlement operation hash
- the finalized settlement signature
- finalized chain commitment

If these disagree, stop automated recovery and investigate authority consistency before any new submission.
