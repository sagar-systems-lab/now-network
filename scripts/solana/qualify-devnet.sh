#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROGRAM_ID="sE74tJL2pCSWMHhEGvBM5hL2DYmFaUUQCDpC1QkHE3T"
readonly UPGRADEABLE_LOADER_ID="BPFLoaderUpgradeab1e11111111111111111111111"
readonly DEFAULT_RPC_URL="https://api.devnet.solana.com"

ROOT="$(git rev-parse --show-toplevel)"
PROGRAM_SO="$ROOT/target/deploy/now_settlement.so"
MODE="${NOW_DEVNET_MODE:-static}"
RPC_URL="${NOW_DEVNET_RPC_URL:-$DEFAULT_RPC_URL}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() {
  printf 'DEVNET_QUALIFICATION_FAIL=%s\n' "$1" >&2
  exit 1
}

pass() {
  printf 'DEVNET_%s=PASS\n' "$1"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "MISSING_COMMAND_$1"
}

validate_repository_identity() {
  test -s "$PROGRAM_SO" || fail "PROGRAM_BINARY_MISSING"

  if ! python3 - "$ROOT" "$PROGRAM_ID" <<'PY'
import json
import pathlib
import re
import sys
import tomllib

root = pathlib.Path(sys.argv[1])
expected = sys.argv[2]

with (root / "Anchor.toml").open("rb") as handle:
    anchor = tomllib.load(handle)

localnet = anchor.get("programs", {}).get("localnet", {}).get("now_settlement")
devnet = anchor.get("programs", {}).get("devnet", {}).get("now_settlement")

source = (root / "programs/now-settlement/src/lib.rs").read_text()
match = re.search(r'declare_id!\("([^"]+)"\)', source)
declared = match.group(1) if match else None

vectors = json.loads((root / "test-vectors/solana-pda-v1.json").read_text())
vector_id = vectors.get("program_id")

actual = {
    "anchor_localnet": localnet,
    "anchor_devnet": devnet,
    "declare_id": declared,
    "vector": vector_id,
}
bad = {name: value for name, value in actual.items() if value != expected}
if bad:
    print(f"program identity drift: {bad}", file=sys.stderr)
    raise SystemExit(1)
PY
  then
    fail "PROGRAM_IDENTITY_DRIFT"
  fi

  tr -d '\r' < "$ROOT/.gitignore" | grep -Fxq '*-keypair.json' ||
    fail "PROGRAM_KEYPAIR_IGNORE_MISSING"
  grep -Fq '*-keypair.json' "$ROOT/scripts/security/scan-secrets.sh" ||
    fail "PROGRAM_KEYPAIR_SECRET_SCAN_MISSING"

  if git -C "$ROOT" ls-files -z -- '*-keypair.json' | grep -q .; then
    fail "TRACKED_PROGRAM_KEYPAIR"
  fi

  pass "STATIC_IDENTITY"
}

external_keypair() {
  local raw_path="$1"
  local label="$2"

  [[ -n "$raw_path" ]] || fail "${label}_KEYPAIR_MISSING"
  [[ -f "$raw_path" ]] || fail "${label}_KEYPAIR_NOT_FOUND"

  local resolved
  resolved="$(realpath "$raw_path")"

  case "$resolved" in
    "$ROOT"/*)
      fail "${label}_KEYPAIR_INSIDE_REPOSITORY"
      ;;
  esac

  printf '%s\n' "$resolved"
}

validate_program_keypair() {
  require_command solana-keygen

  local keypair
  keypair="$(external_keypair "${NOW_DEVNET_PROGRAM_KEYPAIR:-}" "PROGRAM")"

  local actual
  actual="$(solana-keygen pubkey "$keypair")"
  [[ "$actual" == "$PROGRAM_ID" ]] || fail "PROGRAM_IDENTITY_MISMATCH"

  pass "PROGRAM_KEYPAIR_IDENTITY"
}

validate_rpc_scope() {
  require_command solana

  solana cluster-version --url "$RPC_URL" >/dev/null ||
    fail "RPC_UNREACHABLE"

  case "$RPC_URL" in
    devnet|https://api.devnet.solana.com|https://api.devnet.solana.com/)
      ;;
    *)
      local expected_genesis="${NOW_DEVNET_EXPECTED_GENESIS_HASH:-}"
      [[ -n "$expected_genesis" ]] ||
        fail "CUSTOM_RPC_REQUIRES_EXPECTED_GENESIS_HASH"

      local actual_genesis
      actual_genesis="$(solana genesis-hash --url "$RPC_URL")" ||
        fail "GENESIS_HASH_READ_FAILED"
      [[ "$actual_genesis" == "$expected_genesis" ]] ||
        fail "GENESIS_HASH_MISMATCH"
      ;;
  esac

  pass "RPC_SCOPE"
}

authority_pubkey_for_verify() {
  if [[ -n "${NOW_DEVNET_UPGRADE_AUTHORITY_KEYPAIR:-}" ]]; then
    require_command solana-keygen
    local authority_keypair
    authority_keypair="$(
      external_keypair "$NOW_DEVNET_UPGRADE_AUTHORITY_KEYPAIR" "UPGRADE_AUTHORITY"
    )"
    solana-keygen pubkey "$authority_keypair"
    return
  fi

  [[ -n "${NOW_DEVNET_EXPECTED_UPGRADE_AUTHORITY:-}" ]] ||
    fail "EXPECTED_UPGRADE_AUTHORITY_MISSING"
  printf '%s\n' "$NOW_DEVNET_EXPECTED_UPGRADE_AUTHORITY"
}

readonly_solana_keypair_args() {
  if [[ -n "${NOW_DEVNET_PAYER_KEYPAIR:-}" ]]; then
    printf '%s\n' "--keypair" "$(external_keypair "$NOW_DEVNET_PAYER_KEYPAIR" "PAYER")"
    return
  fi

  if [[ -n "${NOW_DEVNET_UPGRADE_AUTHORITY_KEYPAIR:-}" ]]; then
    printf '%s\n' "--keypair" "$(external_keypair "$NOW_DEVNET_UPGRADE_AUTHORITY_KEYPAIR" "UPGRADE_AUTHORITY")"
  fi
}

read_program_json() {
  local -a keypair_args=()
  mapfile -t keypair_args < <(readonly_solana_keypair_args)
  solana --url "$RPC_URL" "${keypair_args[@]}" --output json program show "$PROGRAM_ID"
}

verify_program_metadata() {
  local expected_authority="$1"
  local program_json="$2"

  if ! PROGRAM_JSON="$program_json" \
    EXPECTED_PROGRAM_ID="$PROGRAM_ID" \
    EXPECTED_OWNER="$UPGRADEABLE_LOADER_ID" \
    EXPECTED_AUTHORITY="$expected_authority" \
    python3 - <<'PY'
import json
import os
import sys

data = json.loads(os.environ["PROGRAM_JSON"])
expected_program = os.environ["EXPECTED_PROGRAM_ID"]
expected_owner = os.environ["EXPECTED_OWNER"]
expected_authority = os.environ["EXPECTED_AUTHORITY"]

checks = {
    "programId": (data.get("programId"), expected_program),
    "owner": (data.get("owner"), expected_owner),
    "authority": (data.get("authority"), expected_authority),
}

bad = {
    name: {"actual": actual, "expected": expected}
    for name, (actual, expected) in checks.items()
    if actual != expected
}

if not data.get("programdataAddress"):
    bad["programdataAddress"] = {"actual": data.get("programdataAddress"), "expected": "nonempty"}
if int(data.get("lastDeploySlot", 0)) <= 0:
    bad["lastDeploySlot"] = {"actual": data.get("lastDeploySlot"), "expected": ">0"}
if int(data.get("dataLen", 0)) <= 0:
    bad["dataLen"] = {"actual": data.get("dataLen"), "expected": ">0"}

if bad:
    print(f"program metadata mismatch: {bad}", file=sys.stderr)
    raise SystemExit(1)
PY
  then
    fail "PROGRAM_METADATA_MISMATCH"
  fi

  pass "PROGRAM_METADATA"
}

dumped_binary_matches() {
  local dump_path="$TMP_DIR/onchain-program.so"
  rm -f "$dump_path"

  local -a keypair_args=()
  mapfile -t keypair_args < <(readonly_solana_keypair_args)
  solana --url "$RPC_URL" "${keypair_args[@]}" program dump "$PROGRAM_ID" "$dump_path" >/dev/null ||
    return 1
  test -s "$dump_path" || return 1

  python3 - "$PROGRAM_SO" "$dump_path" <<'PY'
from pathlib import Path
import hashlib
import sys

local = Path(sys.argv[1]).read_bytes()
remote = Path(sys.argv[2]).read_bytes()

if len(remote) < len(local):
    raise SystemExit(1)
if remote[:len(local)] != local:
    raise SystemExit(1)
if any(remote[len(local):]):
    raise SystemExit(1)

print(f"DEVNET_LOCAL_PROGRAM_SHA256={hashlib.sha256(local).hexdigest()}")
print(f"DEVNET_ONCHAIN_PROGRAM_BYTES={len(remote)}")
print(f"DEVNET_LOCAL_PROGRAM_BYTES={len(local)}")
PY
}

verify_deployment() {
  local expected_authority="$1"
  local program_json

  program_json="$(read_program_json)" || fail "PROGRAM_NOT_DEPLOYED"
  verify_program_metadata "$expected_authority" "$program_json"

  dumped_binary_matches || fail "DEPLOYED_BINARY_MISMATCH"
  pass "DEPLOYED_BINARY"
  pass "DEPLOYMENT"
}

deploy_or_upgrade() {
  require_command solana
  require_command solana-keygen

  local payer
  payer="$(external_keypair "${NOW_DEVNET_PAYER_KEYPAIR:-}" "PAYER")"
  local authority
  authority="$(
    external_keypair "${NOW_DEVNET_UPGRADE_AUTHORITY_KEYPAIR:-}" "UPGRADE_AUTHORITY"
  )"
  local authority_pubkey
  authority_pubkey="$(solana-keygen pubkey "$authority")"

  local payer_pubkey
  payer_pubkey="$(solana-keygen pubkey "$payer")"
  printf 'DEVNET_PAYER=%s\n' "$payer_pubkey"
  printf 'DEVNET_UPGRADE_AUTHORITY=%s\n' "$authority_pubkey"
  solana balance "$payer_pubkey" --url "$RPC_URL"

  local program_argument
  local existing_json=""

  if existing_json="$(read_program_json 2>/dev/null)"; then
    verify_program_metadata "$authority_pubkey" "$existing_json"

    if dumped_binary_matches; then
      pass "DEPLOYMENT_ALREADY_CURRENT"
      return
    fi

    program_argument="$PROGRAM_ID"
  else
    local program_keypair
    program_keypair="$(
      external_keypair "${NOW_DEVNET_PROGRAM_KEYPAIR:-}" "PROGRAM"
    )"
    local program_pubkey
    program_pubkey="$(solana-keygen pubkey "$program_keypair")"
    [[ "$program_pubkey" == "$PROGRAM_ID" ]] ||
      fail "PROGRAM_IDENTITY_MISMATCH"
    program_argument="$program_keypair"
  fi

  solana \
    --url "$RPC_URL" \
    --keypair "$payer" \
    program deploy "$PROGRAM_SO" \
    --fee-payer "$payer" \
    --program-id "$program_argument" \
    --upgrade-authority "$authority" \
    --use-rpc

  verify_deployment "$authority_pubkey"
  pass "DEPLOY_OR_UPGRADE"
}

validate_repository_identity

case "$MODE" in
  static)
    ;;
  identity)
    validate_program_keypair
    ;;
  verify)
    validate_rpc_scope
    verify_deployment "$(authority_pubkey_for_verify)"
    ;;
  deploy)
    validate_rpc_scope
    deploy_or_upgrade
    ;;
  *)
    fail "UNSUPPORTED_MODE"
    ;;
esac
