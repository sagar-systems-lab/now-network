# Environments

NOW V1 uses intentionally small environment scope.

## local

Local development and deterministic tests.

## integration

Isolated integration resources for database, storage, realtime, API, and program tests.

## devnet / demo

Solana devnet plus the controlled demo backend/runtime.

## final demo

The qualified release identity used for the submission demo.

## Hard rules

- No mainnet dependency is required for the hackathon V1.
- No real-money requirement.
- No large paid VM, Kubernetes, Kafka, required Redis, paid AI, or paid map dependency.
- Privileged secrets never live in Git or the Android APK.
