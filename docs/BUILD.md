# Build

## Android

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug :app:assembleRelease
```

The Phase 0 instrumented harness is compiled into the debug Android test APK during CI. Device/emulator execution is a later qualification concern; Phase 0 proves that the instrumented test surface is wired and buildable.

## API

```bash
cd backend
deno fmt --check
deno lint
deno task check
deno task test
```

Run the local server:

```bash
cd backend
deno task serve
```

Health endpoint:

```text
GET /health
```

## Solana program

```bash
cargo test --workspace
anchor build
```

Release builds use the versions recorded in `toolchains.json`.

## Secret scan

```bash
./scripts/security/scan-secrets.sh
```

The scan checks tracked sensitive file names plus the current tree and full Git history with a pinned
Gitleaks binary.
