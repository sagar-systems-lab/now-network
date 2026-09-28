#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(git rev-parse --show-toplevel)"
source "$ROOT/scripts/solana/protocol-flow-common.sh"

RPC_URL="http://127.0.0.1:8899"
PROGRAM_SO="target/deploy/now_settlement.so"
readonly SETTLEMENT_REFRESH_ID_HEX="$(printf 'a1%.0s' {1..32})"
readonly REFUND_REFRESH_ID_HEX="$(printf 'b2%.0s' {1..32})"

test -s "$PROGRAM_SO"
command -v solana-test-validator >/dev/null
command -v solana >/dev/null
command -v solana-keygen >/dev/null
command -v spl-token >/dev/null
command -v deno >/dev/null

WORK_DIR="$(mktemp -d)"
LEDGER="$WORK_DIR/ledger"
VALIDATOR_LOG="$WORK_DIR/validator.log"
RESTART_LOG="$WORK_DIR/validator-restart.log"
CLI_PAYER="$WORK_DIR/payer.json"
MINT_KEYPAIR="$WORK_DIR/reward-mint.json"
FLOW_CLAIMANT="$WORK_DIR/flow-claimant.json"
FLOW_ADDRESSES="$WORK_DIR/flow-addresses.json"
STATE_FILE="$WORK_DIR/runtime-state.json"
SOLANA_CONFIG_FILE="$WORK_DIR/solana-cli.yml"
export SOLANA_CONFIG_FILE

VALIDATOR_PID=""

cleanup() {
  if [[ -n "$VALIDATOR_PID" ]] && kill -0 "$VALIDATOR_PID" 2>/dev/null; then
    kill "$VALIDATOR_PID" >/dev/null 2>&1 || true
    wait "$VALIDATOR_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK_DIR"
}

diagnostics() {
  echo "----- local validator log -----"
  tail -n 250 "$VALIDATOR_LOG" 2>/dev/null || true
  echo "----- restarted validator log -----"
  tail -n 250 "$RESTART_LOG" 2>/dev/null || true
}

trap cleanup EXIT
trap diagnostics ERR

wait_for_rpc() {
  for _ in $(seq 1 120); do
    if solana cluster-version --url "$RPC_URL" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  echo "local validator RPC did not become ready" >&2
  return 1
}

start_validator() {
  local log_file="$1"
  shift

  solana-test-validator --ledger "$LEDGER" --rpc-port 8899 "$@" >"$log_file" 2>&1 &
  VALIDATOR_PID="$!"
  wait_for_rpc
}

solana-keygen new --no-bip39-passphrase --silent --force --outfile "$CLI_PAYER"

start_validator "$VALIDATOR_LOG" --reset --bpf-program "$PROGRAM_ID" "$PROGRAM_SO"

solana config set --url "$RPC_URL" --keypair "$CLI_PAYER" >/dev/null
solana airdrop 20 >/dev/null

solana-keygen new --no-bip39-passphrase --silent --force --outfile "$MINT_KEYPAIR"
MINT_ADDRESS="$(solana-keygen pubkey "$MINT_KEYPAIR")"

spl-token create-token --decimals 6 "$MINT_KEYPAIR" >/dev/null
spl-token display "$MINT_ADDRESS" >/dev/null

export NOW_LOCALNET_RPC_URL="$RPC_URL"
export NOW_LOCALNET_REWARD_MINT="$MINT_ADDRESS"
export NOW_LOCALNET_STATE_FILE="$STATE_FILE"
export NOW_LOCALNET_ADMIN_KEYPAIR="$CLI_PAYER"
export NOW_LOCALNET_MODE="exercise"

deno run \
  --allow-env=NOW_LOCALNET_RPC_URL,NOW_LOCALNET_REWARD_MINT,NOW_LOCALNET_STATE_FILE,NOW_LOCALNET_ADMIN_KEYPAIR,NOW_LOCALNET_MODE \
  --allow-net=127.0.0.1:8899 \
  --allow-read=test-vectors/solana-pda-v1.json,"$CLI_PAYER" \
  --allow-write="$STATE_FILE" \
  scripts/solana/local-validator-client.ts

solana program show "$PROGRAM_ID" --url "$RPC_URL" >/dev/null

# Exercise the full settlement/refund transaction path on the validator that
# already passed runtime qualification. Reusing the same validator avoids a
# second back-to-back validator startup while preserving one authoritative
# program/config instance for the protocol flow.
solana-keygen new \
  --no-bip39-passphrase \
  --silent \
  --force \
  --outfile "$FLOW_CLAIMANT"

FLOW_CLAIMANT_ADDRESS="$(solana-keygen pubkey "$FLOW_CLAIMANT")"
solana airdrop 2 "$FLOW_CLAIMANT_ADDRESS" >/dev/null

CLI_PAYER_ADDRESS="$(solana-keygen pubkey "$CLI_PAYER")"
write_flow_addresses \
  "$CLI_PAYER_ADDRESS" \
  "$FLOW_CLAIMANT_ADDRESS" \
  "$MINT_ADDRESS" \
  "$SETTLEMENT_REFRESH_ID_HEX" \
  "$REFUND_REFRESH_ID_HEX" \
  "$FLOW_ADDRESSES"

PAYER_ATA="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["payerAta"])' \
    "$FLOW_ADDRESSES"
)"
CLAIMANT_ATA="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["claimantAta"])' \
    "$FLOW_ADDRESSES"
)"
SETTLEMENT_VAULT="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["settlement"]["vault"])' \
    "$FLOW_ADDRESSES"
)"
REFUND_VAULT="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["refund"]["vault"])' \
    "$FLOW_ADDRESSES"
)"
SETTLEMENT_OWNER="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["settlement"]["refresh"])' \
    "$FLOW_ADDRESSES"
)"
REFUND_OWNER="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["refund"]["refresh"])' \
    "$FLOW_ADDRESSES"
)"

ensure_token_account \
  "$RPC_URL" "$CLI_PAYER" "$CLI_PAYER_ADDRESS" "$MINT_ADDRESS" "$PAYER_ATA"
ensure_token_account \
  "$RPC_URL" "$CLI_PAYER" "$FLOW_CLAIMANT_ADDRESS" "$MINT_ADDRESS" "$CLAIMANT_ATA"
ensure_token_account \
  "$RPC_URL" "$CLI_PAYER" "$SETTLEMENT_OWNER" "$MINT_ADDRESS" "$SETTLEMENT_VAULT"
ensure_token_account \
  "$RPC_URL" "$CLI_PAYER" "$REFUND_OWNER" "$MINT_ADDRESS" "$REFUND_VAULT"

spl-token mint "$MINT_ADDRESS" 10 "$PAYER_ATA" >/dev/null

export NOW_FLOW_RPC_URL="$RPC_URL"
export NOW_FLOW_PAYER_KEYPAIR="$CLI_PAYER"
export NOW_FLOW_CLAIMANT_KEYPAIR="$FLOW_CLAIMANT"
export NOW_FLOW_REWARD_MINT="$MINT_ADDRESS"
export NOW_FLOW_SETTLEMENT_REFRESH_ID_HEX="$SETTLEMENT_REFRESH_ID_HEX"
export NOW_FLOW_REFUND_REFRESH_ID_HEX="$REFUND_REFRESH_ID_HEX"
export NOW_FLOW_ADDRESSES_FILE="$FLOW_ADDRESSES"
export NOW_FLOW_MODE="exercise"

python3 scripts/solana/protocol-flow-client.py
echo "LOCAL_PROTOCOL_FLOW=PASS"

kill "$VALIDATOR_PID"
wait "$VALIDATOR_PID" 2>/dev/null || true
VALIDATOR_PID=""
sleep 1

start_validator "$RESTART_LOG"

export NOW_LOCALNET_MODE="verify"
deno run \
  --allow-env=NOW_LOCALNET_RPC_URL,NOW_LOCALNET_REWARD_MINT,NOW_LOCALNET_STATE_FILE,NOW_LOCALNET_ADMIN_KEYPAIR,NOW_LOCALNET_MODE \
  --allow-net=127.0.0.1:8899 \
  --allow-read=test-vectors/solana-pda-v1.json,"$STATE_FILE" \
  scripts/solana/local-validator-client.ts

solana program show "$PROGRAM_ID" --url "$RPC_URL" >/dev/null

echo "LOCAL_VALIDATOR_QUALIFICATION=PASS"
