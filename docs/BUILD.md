# Build and test

## Android

Run unit tests and compile the debug, instrumented-test and release APKs:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug :app:assembleRelease
```

CI compiles the instrumented-test APK. Emulator or physical-device execution can be run separately when device-level behavior needs to be exercised.

## API and shared domain

```bash
cd backend
deno fmt --check
deno lint
deno task check
deno task test
```

The Deno suite includes shared-contract parity, deterministic domain and policy tests, cross-module integration,
and database migration contract checks.

Run the local server:

```bash
cd backend
deno task serve
```

Health endpoint:

```text
GET /health
```

## Database migrations

Versioned SQL lives in `supabase/migrations`. The migration contract suite checks ordering, lifecycle
enum parity, critical constraints, index coverage, row-level security, and forward-only migration
rules. Full database migration and authorization qualification is run against an isolated Supabase
local stack before the data model is considered release-qualified.

## Solana program

```bash
cargo test --workspace --locked
anchor build
```

Tool versions are recorded in `toolchains.json`.

## Secret scan

```bash
./scripts/security/scan-secrets.sh
```

The scanner checks tracked sensitive file names, the current tree and Git history with a pinned Gitleaks binary.
