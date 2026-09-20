#!/usr/bin/env bash
set -Eeuo pipefail

readonly GITLEAKS_VERSION="8.30.1"
readonly GITLEAKS_SHA256="551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"
readonly GITLEAKS_URL="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"

ROOT="$(git rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cd "$ROOT"

check_sensitive_paths() {
  local bad=0
  while IFS= read -r -d '' path; do
    case "$path" in
      .env.example|apps/android/local.properties.example)
        ;;
      .env|.env.*|*/.env|*/.env.*|local.properties|*/local.properties|*.jks|*.keystore|*.p12|*.pfx|*.pem|*.key|*-keypair.json)
        printf 'sensitive file is tracked: %s\n' "$path" >&2
        bad=1
        ;;
    esac
  done < <(git ls-files -z)

  if [[ "$bad" -ne 0 ]]; then
    return 1
  fi
}

install_gitleaks() {
  curl --fail --silent --show-error --location \
    --output "$TMP/gitleaks.tar.gz" \
    "$GITLEAKS_URL"

  printf '%s  %s\n' "$GITLEAKS_SHA256" "$TMP/gitleaks.tar.gz" | sha256sum -c -

  tar -xzf "$TMP/gitleaks.tar.gz" -C "$TMP" gitleaks
  chmod 0755 "$TMP/gitleaks"
}

check_sensitive_paths
install_gitleaks

"$TMP/gitleaks" git --redact --no-banner --verbose .
"$TMP/gitleaks" dir --redact --no-banner --verbose .

printf 'secret scan: clean\n'
