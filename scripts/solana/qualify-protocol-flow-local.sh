#!/usr/bin/env bash
set -Eeuo pipefail

readonly RPC_PORT="18899"
readonly RPC_URL="http://127.0.0.1:${RPC_PORT}"
readonly FAUCET_PORT="19900"
readonly GOSSIP_PORT="18001"
readonly DYNAMIC_PORT_RANGE="18002-18040"
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
  echo "----- protocol-flow validator process -----"
  if [[ -n "$VALIDATOR_PID" ]]; then
    ps -o pid=,ppid=,stat=,etime=,cmd= -p "$VALIDATOR_PID" 2>/dev/null || true
  fi
  echo "----- protocol-flow validator ports -----"
  ss -ltnp 2>/dev/null | grep -E ':(18899|18900|19900|18001|1800[2-9]|180[1-3][0-9]|18040)\\b' || true
  echo "----- protocol-flow validator log -----"
  tail -n 300 "$VALIDATOR_LOG" 2>/dev/null || true
}

wait_for_rpc() {
  local stable_probes=0

  for _ in $(seq 1 240); do
    if ! kill -0 "$VALIDATOR_PID" 2>/dev/null; then
      echo "protocol-flow validator exited before RPC stabilized" >&2
      wait "$VALIDATOR_PID" 2>/dev/null || true
      return 1
    fi

    if solana cluster-version --url "$RPC_URL" >/dev/null 2>&1; then
      stable_probes=$((stable_probes + 1))
      if [[ "$stable_probes" -ge 3 ]]; then
        return 0
      fi
    else
      stable_probes=0
    fi

    sleep 0.25
  done

  echo "protocol-flow validator RPC did not stabilize" >&2
  return 1
}

trap cleanup EXIT
trap diagnostics ERR

solana-keygen new   --no-bip39-passphrase   --silent   --force   --outfile "$PAYER"
solana-keygen new   --no-bip39-passphrase   --silent   --force   --outfile "$CLAIMANT"
solana-keygen new   --no-bip39-passphrase   --silent   --force   --outfile "$MINT_KEYPAIR"

solana-test-validator \
  --ledger "$LEDGER" \
  --bind-address 127.0.0.1 \
  --rpc-port "$RPC_PORT" \
  --faucet-port "$FAUCET_PORT" \
  --gossip-port "$GOSSIP_PORT" \
  --dynamic-port-range "$DYNAMIC_PORT_RANGE" \
  --reset \
  --bpf-program "$PROGRAM_ID" "$PROGRAM_SO" \
  >"$VALIDATOR_LOG" 2>&1 &
VALIDATOR_PID="$!"

wait_for_rpc

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
