# Security response

Use this when a privileged runtime credential, signing key or unexpected client access may be exposed.

## Classify the value first

Public mobile configuration is not a privileged secret. Examples include the Supabase publishable key, project URL, devnet RPC URL, program ID and reward mint.

Privileged values include:

- service-role/database credentials
- worker bearer token
- readiness token
- settlement verifier private key material

Do not rotate public identifiers simply because they are visible in an APK.

## Immediate containment

1. stop copying or displaying the suspected value
2. preserve the time, affected service and deployment/build identity
3. determine whether the value is only local, hosted, or also used by scheduler/runtime integration
4. rotate the smallest affected credential set
5. update every legitimate consumer of that credential
6. verify health/readiness after rotation
7. reconstruct affected financial operations during the exposure window

For the worker token, the hosted worker and scheduler/Vault copy must remain synchronized.

A settlement verifier-key incident is more sensitive than a bearer-token incident because it affects financial authorization. Do not replace or reinitialize protocol state ad hoc. Contain settlement activity and perform an explicit verifier-rotation procedure.

## Client-access suspicion

Direct client roles are not expected to read internal app.* authority tables. The public realtime surface is read-only and sanitized.

If unexpected access is suspected:

- reproduce using the same client role
- inspect grants and RLS policy behavior
- preserve the exact relation, role and request time
- do not broaden RLS policies to make diagnostics easier

## Evidence handling

Never attach:

- private key JSON
- service-role keys
- database passwords
- worker/readiness token values
- raw evidence objects

Use identifiers, digests, timestamps and sanitized reconstruction output instead.
