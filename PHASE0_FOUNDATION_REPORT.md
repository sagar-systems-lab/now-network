# NOW Network — Phase 0 Foundation Report

**Status:** PASS  
**Phase:** Phase 0 — Repository / Contract / Environment Foundation  
**Qualified source commit:** `f99768da46c94ccfdf05f6def2c8730f60928dbb`  
**Qualified source tree:** `8b1e0c1ae12e99bfd577a391b3cc868e59228e8d`  
**Qualification workflow:** GitHub Actions `build` run #26 (`35508917027`)  
**Qualification result:** SUCCESS  
**Qualification completed:** 2026-09-20T11:55:34Z  
**Expected rollback tag:** `p0-foundation-pass`

## Closure statement

Phase 0 is qualified as a foundation-only release point. The repository can compile, test and extend the Android, backend and Solana surfaces without implementing later product behavior. Toolchains and dependency inputs are pinned, shared contracts are explicit, secret scanning is enforced, and the required repository documentation/test harnesses are present.

The evidence commit that contains this report is intentionally separate from the qualified source commit above. It adds evidence only; it does not mutate the qualified implementation. The rollback tag is created only after the evidence commit itself passes the same CI workflow.

## Gate matrix

| Gate | Result | Evidence |
| --- | --- | --- |
| P0-GATE-001 repo clean | PASS | Qualified Git tree recorded; generated build output is ignored; locked dependency files are checked for mutation in CI |
| P0-GATE-002 Android debug compile | PASS | Android job, run #26 |
| P0-GATE-003 Android release compile | PASS | Android job, run #26 |
| P0-GATE-004 backend compile | PASS | Deno typecheck/lint/test job, run #26 |
| P0-GATE-005 program build | PASS | Cargo workspace tests + Anchor build, run #26 |
| P0-GATE-006 unit harness PASS | PASS | Android JVM, Deno contract/domain/backend, and Rust program tests, run #26 |
| P0-GATE-007 secret scan PASS | PASS | Pinned Gitleaks current-tree + full-history scan, run #26 |
| P0-GATE-008 CI skeleton PASS | PASS | Android, API, Program and Security jobs all successful in run #26 |

## Required Phase 0 surfaces

Repository skeleton:
- `apps/android`
- `backend`
- `programs/now-settlement`
- `packages/contracts`
- `packages/domain`
- `packages/policy`
- `docs`
- `tests`
- `scripts`

Environment templates:
- `.env.example`
- `apps/android/local.properties.example`

Documentation:
- `docs/BUILD.md`
- `docs/ENVIRONMENTS.md`
- `docs/ARCHITECTURE_INDEX.md`

Test harnesses:
- Android JVM test source set
- Android instrumented test source set and debug test APK compilation
- Deno backend/contract/domain tests
- Rust program tests
- CI execution across Android, API, Program and Security jobs

## Instrumented harness boundary

Phase 0 requires the Android instrumented harness to be set up. The harness is wired with a test instrumentation runner and the `androidTest` source set is compiled through `:app:assembleDebugAndroidTest`.

Phase 0 does **not** claim real-device/emulator execution. Device execution, signed APK qualification and full end-to-end mobile qualification remain later release gates.

## Shared contract foundation

The repository freezes shared registries for state kinds, freshness states, refresh overlays, verification classes/outcomes, settlement outcomes, error categories, verification reason codes, and API error codes. The deterministic freshness primitive has explicit boundary tests for LIVE, AGING and STALE.

## Toolchain freeze

Authoritative versions are recorded in `toolchains.json` and companion pin files. Current Phase 0 values include JDK 17.0.20.1+1, Gradle 9.6.0, Android Gradle Plugin 9.4.0, Kotlin 2.2.10, compileSdk/targetSdk 36, Deno 2.9.7, Node 24.21.0, npm 11.19.0, Supabase CLI 2.117.0, PostgreSQL target 17.11, Rust 1.98.1, Solana/Agave 4.1.2 and Anchor 1.2.0.

## Secret and supply-chain hygiene

- Real environment secrets are excluded from Git.
- Android local SDK configuration is template-only.
- Keystores, private keys, PEM material and wallet keypair files are rejected by tracked-path checks.
- Gitleaks 8.30.1 is downloaded by a pinned URL and verified SHA-256 before execution.
- Secret scanning covers both reachable Git history and the current directory.
- Android dependency verification metadata is checked for mutation.
- Cargo dependencies are locked and Cargo.lock mutation is checked after Anchor build.
- GitHub Actions dependencies are commit-pinned.

## Remediation history

The first closure candidate, workflow run #25, correctly failed while compiling the new Android instrumented source set because the initial test used legacy `android.test.*` APIs unavailable on the current compile surface.

The fix did not weaken the gate or add a floating dependency. The harness was rewritten to use the already locked JUnit artifact and to validate that the frozen core contract is reachable from the instrumented source set. Workflow run #26 then passed all four jobs.

## Integrity model

The qualified source commit and Git tree above identify the complete source snapshot. `PHASE0_MANIFEST.sha256` additionally records SHA-256 digests for the Phase 0 control plane: CI, toolchains, dependency locks, test harness, shared contracts, program entry point, secret scanner and foundation documentation.

## Phase boundary

This closure does not claim implementation of maps, production evidence capture, adaptive policy, complex settlement, polished product UI, signed-release qualification, devnet E2E, or final demo behavior. Those remain later-phase work.

## Handoff

Phase 0 is eligible for the rollback tag `p0-foundation-pass` only after the evidence commit containing this report, `PHASE0_BUILD_METADATA.json`, and `PHASE0_MANIFEST.sha256` passes CI unchanged.

The next implementation phase is `PHASE 1 — DATA / DOMAIN / POLICY CORE`.
