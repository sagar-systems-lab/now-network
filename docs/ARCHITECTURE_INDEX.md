# Architecture Index

This file is the repository-level map of NOW Network V1. It describes code ownership and dependency direction without duplicating internal implementation authority.

## Product loop

`ASK / REFRESH → PROVE → KNOW → PAY`

## Runtime surfaces

| Surface | Repository path | Responsibility |
| --- | --- | --- |
| Android client | `apps/android` | Native mobile shell, requester/contributor interaction, local device integration |
| Backend control plane | `backend` | HTTP API, orchestration, validation entry points, health/runtime services |
| Solana settlement | `programs/now-settlement` | Minimal on-chain financial settlement boundary |
| Shared contracts | `packages/contracts` | Stable cross-surface enums, result categories, reason/error registries and schemas |
| Domain logic | `packages/domain` | Deterministic product/domain rules that should not depend on UI or deployment runtime |
| Policy logic | `packages/policy` | Policy evaluation surface; implementation expands in later phases |
| Supabase functions | `supabase/functions` | Serverless integration/deployment entry points |
| Cross-surface tests | `tests` | Integration and end-to-end test ownership as later phases add vertical behavior |
| Operational scripts | `scripts` | Security, build, qualification and release utilities |
| Engineering docs | `docs` | Build, environment and repository architecture documentation |

## Dependency direction

The intended dependency direction is:

```text
Android / Backend / Serverless
        ↓
shared contracts
        ↓
domain + policy
```

The Solana program remains a separate financial authority boundary and must not become the source of physical-world truth.

Runtime/UI code may consume shared contracts and deterministic domain/policy outputs. Shared contracts and domain logic must not depend on Android UI classes, deployment-specific state, or privileged secrets.

## Phase 0 boundaries

Phase 0 establishes only the foundation required to compile, test and extend the system safely:

- repository and environment skeletons,
- pinned toolchains and dependency inputs,
- Android debug/release and instrumented-test build surfaces,
- backend health/typecheck/test surface,
- Solana program build/test surface,
- shared contract and error/reason-code registries,
- deterministic freshness primitive,
- CI and secret scanning.

Maps, production evidence capture, adaptive behavior, complex settlement and polished product UI are intentionally outside the Phase 0 implementation boundary.

## Source-of-truth files

- Toolchains: `toolchains.json`, `.java-version`, `.nvmrc`, `.deno-version`, `rust-toolchain.toml`
- Android dependencies: `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`
- Rust dependencies: `Cargo.lock`
- CI: `.github/workflows/build.yml`
- Environment rules: `docs/ENVIRONMENTS.md`
- Build commands: `docs/BUILD.md`
- Shared contract registry: `packages/contracts/src`
- Domain foundation: `packages/domain/src`
