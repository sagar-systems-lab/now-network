# API

The API is written for Deno and exposed through Supabase Edge Functions.

Local checks:

```bash
deno fmt --check
deno lint
deno task check
deno task test
```

Run locally:

```bash
SUPABASE_URL=... \
SUPABASE_ANON_KEY=... \
SUPABASE_DB_URL=... \
deno task serve
```

Keep the database connection string outside Git. Deployed edge/serverless runtime should use the
Supabase transaction pooler.

Current endpoints:

```text
GET  /health
GET  /v1/me
POST /v1/wallet-bindings/challenge
POST /v1/wallet-bindings/verify
```

Authenticated endpoints require a Supabase access token in `Authorization: Bearer <token>`.
Wallet binding uses a short-lived, one-time, domain-separated Ed25519 message challenge.
