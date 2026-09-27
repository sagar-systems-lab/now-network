#!/usr/bin/env bash
set -Eeuo pipefail

PROGRAM_ID="sE74tJL2pCSWMHhEGvBM5hL2DYmFaUUQCDpC1QkHE3T"
RPC_URL="http://127.0.0.1:8899"
PROGRAM_SO="target/deploy/now_settlement.so"

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
export NOW_LOCALNET_MODE="exercise"

deno run \
  --allow-env=NOW_LOCALNET_RPC_URL,NOW_LOCALNET_REWARD_MINT,NOW_LOCALNET_STATE_FILE,NOW_LOCALNET_MODE \
  --allow-net=127.0.0.1:8899 \
  --allow-read=test-vectors/solana-pda-v1.json \
  --allow-write="$STATE_FILE" \
  scripts/solana/local-validator-client.ts

solana program show "$PROGRAM_ID" --url "$RPC_URL" >/dev/null

kill "$VALIDATOR_PID"
wait "$VALIDATOR_PID" 2>/dev/null || true
VALIDATOR_PID=""
sleep 1

start_validator "$RESTART_LOG"

export NOW_LOCALNET_MODE="verify"
deno run \
  --allow-env=NOW_LOCALNET_RPC_URL,NOW_LOCALNET_REWARD_MINT,NOW_LOCALNET_STATE_FILE,NOW_LOCALNET_MODE \
  --allow-net=127.0.0.1:8899 \
  --allow-read=test-vectors/solana-pda-v1.json,"$STATE_FILE" \
  scripts/solana/local-validator-client.ts

solana program show "$PROGRAM_ID" --url "$RPC_URL" >/dev/null

echo "LOCAL_VALIDATOR_QUALIFICATION=PASS"
