#!/usr/bin/env bash
set -Eeuo pipefail

readonly RPC_URL="http://127.0.0.1:8899"
readonly PROGRAM_SO="target/deploy/now_settlement.so"
readonly SETTLEMENT_REFRESH_ID_HEX="$(printf 'a1%.0s' {1..32})"
readonly REFUND_REFRESH_ID_HEX="$(printf 'b2%.0s' {1..32})"

ROOT="$(git rev-parse --show-toplevel)"
source "$ROOT/scripts/solana/protocol-flow-common.sh"

test -s "$PROGRAM_SO"
command -v solana-test-validator >/dev/null
command -v solana >/dev/null
command -v solana-keygen >/dev/null
command -v spl-token >/dev/null
command -v python3 >/dev/null
command -v openssl >/dev/null

WORK_DIR="$(mktemp -d)"
LEDGER="$WORK_DIR/ledger"
VALIDATOR_LOG="$WORK_DIR/validator.log"
PAYER="$WORK_DIR/payer.json"
CLAIMANT="$WORK_DIR/claimant.json"
MINT_KEYPAIR="$WORK_DIR/reward-mint.json"
ADDRESSES="$WORK_DIR/addresses.json"
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
  echo "----- protocol-flow validator log -----"
  tail -n 300 "$VALIDATOR_LOG" 2>/dev/null || true
}

trap cleanup EXIT
trap diagnostics ERR

solana-keygen new   --no-bip39-passphrase   --silent   --force   --outfile "$PAYER"
solana-keygen new   --no-bip39-passphrase   --silent   --force   --outfile "$CLAIMANT"
solana-keygen new   --no-bip39-passphrase   --silent   --force   --outfile "$MINT_KEYPAIR"

solana-test-validator   --ledger "$LEDGER"   --rpc-port 8899   --reset   --bpf-program "$PROGRAM_ID" "$PROGRAM_SO"   >"$VALIDATOR_LOG" 2>&1 &
VALIDATOR_PID="$!"

for _ in $(seq 1 120); do
  if solana cluster-version --url "$RPC_URL" >/dev/null 2>&1; then
    break
  fi
  sleep 0.5
done
solana cluster-version --url "$RPC_URL" >/dev/null

solana config set --url "$RPC_URL" --keypair "$PAYER" >/dev/null
solana airdrop 20 >/dev/null
CLAIMANT_ADDRESS="$(solana-keygen pubkey "$CLAIMANT")"
solana airdrop 2 "$CLAIMANT_ADDRESS" >/dev/null

MINT_ADDRESS="$(solana-keygen pubkey "$MINT_KEYPAIR")"
spl-token create-token --decimals 6 "$MINT_KEYPAIR" >/dev/null

PAYER_ADDRESS="$(solana-keygen pubkey "$PAYER")"
write_flow_addresses   "$PAYER_ADDRESS"   "$CLAIMANT_ADDRESS"   "$MINT_ADDRESS"   "$SETTLEMENT_REFRESH_ID_HEX"   "$REFUND_REFRESH_ID_HEX"   "$ADDRESSES"

PAYER_ATA="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["payerAta"])'     "$ADDRESSES"
)"
CLAIMANT_ATA="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["claimantAta"])'     "$ADDRESSES"
)"
SETTLEMENT_VAULT="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["settlement"]["vault"])'     "$ADDRESSES"
)"
REFUND_VAULT="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["refund"]["vault"])'     "$ADDRESSES"
)"

ensure_token_account "$RPC_URL" "$PAYER" "$PAYER_ADDRESS" "$MINT_ADDRESS" "$PAYER_ATA"
ensure_token_account "$RPC_URL" "$PAYER" "$CLAIMANT_ADDRESS" "$MINT_ADDRESS" "$CLAIMANT_ATA"

SETTLEMENT_OWNER="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["settlement"]["refresh"])'     "$ADDRESSES"
)"
REFUND_OWNER="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["refund"]["refresh"])'     "$ADDRESSES"
)"
ensure_token_account   "$RPC_URL" "$PAYER" "$SETTLEMENT_OWNER" "$MINT_ADDRESS" "$SETTLEMENT_VAULT"
ensure_token_account   "$RPC_URL" "$PAYER" "$REFUND_OWNER" "$MINT_ADDRESS" "$REFUND_VAULT"

spl-token mint "$MINT_ADDRESS" 10 "$PAYER_ATA" >/dev/null

export NOW_FLOW_RPC_URL="$RPC_URL"
export NOW_FLOW_PAYER_KEYPAIR="$PAYER"
export NOW_FLOW_CLAIMANT_KEYPAIR="$CLAIMANT"
export NOW_FLOW_REWARD_MINT="$MINT_ADDRESS"
export NOW_FLOW_SETTLEMENT_REFRESH_ID_HEX="$SETTLEMENT_REFRESH_ID_HEX"
export NOW_FLOW_REFUND_REFRESH_ID_HEX="$REFUND_REFRESH_ID_HEX"
export NOW_FLOW_ADDRESSES_FILE="$ADDRESSES"
export NOW_FLOW_MODE="exercise"

python3 scripts/solana/protocol-flow-client.py

echo "LOCAL_PROTOCOL_FLOW=PASS"
