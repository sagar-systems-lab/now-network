# Architecture

NOW Network connects refresh requests for physical-world information with request-bound evidence and settlement.

## Components

| Component | Path | Responsibility |
| --- | --- | --- |
| Android client | `apps/android` | Requester and contributor mobile experience |
| API | `backend` | HTTP endpoints, orchestration and validation entry points |
| Shared contracts | `packages/contracts` | Cross-platform enums, schemas and reason/error codes |
| Domain logic | `packages/domain` | Deterministic state and freshness rules |
| Policy logic | `packages/policy` | Policy evaluation |
| Supabase functions | `supabase/functions` | Serverless API integration |
| Solana program | `programs/now-settlement` | Settlement boundary |
| Tests | `tests` and package-local test directories | Unit, integration and end-to-end coverage |
| Operational scripts | `scripts` | Build, security and release utilities |

## Dependency boundaries

Application and service code may depend on shared contracts and deterministic domain logic.

```text
Android / API / Serverless
          |
          v
   shared contracts
          |
          v
    domain / policy
```

Shared contracts and domain logic should remain independent of Android UI classes, deployment-specific state and privileged secrets.

The Solana program is a settlement authority. It does not establish physical-world truth.

## Build and configuration references

- Toolchains: `toolchains.json`, `.java-version`, `.nvmrc`, `.deno-version`, `rust-toolchain.toml`
- Android dependencies: `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`
- Rust dependencies: `Cargo.lock`
- CI: `.github/workflows/build.yml`
- Environment setup: `docs/ENVIRONMENTS.md`
- Build and test commands: `docs/BUILD.md`
