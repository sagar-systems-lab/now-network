#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(git rev-parse --show-toplevel)"
source "$ROOT/scripts/solana/protocol-flow-common.sh"

RPC_URL="$(printenv NOW_DEVNET_RPC_URL || true)"
PAYER="$(printenv NOW_DEVNET_PAYER_KEYPAIR || true)"
REWARD_MINT="$(printenv NOW_DEVNET_REWARD_MINT || true)"
[[ -n "$RPC_URL" ]] || RPC_URL="https://api.devnet.solana.com"

[[ -n "$PAYER" && -f "$PAYER" ]] || {
  echo "DEVNET_FLOW_FAIL=PAYER_KEYPAIR_REQUIRED" >&2
  exit 1
}
[[ -n "$REWARD_MINT" ]] || {
  echo "DEVNET_FLOW_FAIL=REWARD_MINT_REQUIRED" >&2
  exit 1
}

PAYER="$(realpath "$PAYER")"
case "$PAYER" in
  "$ROOT"/*)
    echo "DEVNET_FLOW_FAIL=PAYER_KEYPAIR_INSIDE_REPOSITORY" >&2
    exit 1
    ;;
esac

command -v solana >/dev/null
command -v solana-keygen >/dev/null
command -v spl-token >/dev/null
command -v python3 >/dev/null
command -v openssl >/dev/null

NOW_DEVNET_MODE=verify "$ROOT/scripts/solana/qualify-devnet.sh"

WORK_DIR="$(mktemp -d)"
CLAIMANT="$WORK_DIR/claimant.json"
ADDRESSES="$WORK_DIR/addresses.json"
SOLANA_CONFIG_FILE="$WORK_DIR/solana-cli.yml"
export SOLANA_CONFIG_FILE
trap 'rm -rf "$WORK_DIR"' EXIT

solana-keygen new   --no-bip39-passphrase   --silent   --force   --outfile "$CLAIMANT"

PAYER_ADDRESS="$(solana-keygen pubkey "$PAYER")"
CLAIMANT_ADDRESS="$(solana-keygen pubkey "$CLAIMANT")"

solana config set --url "$RPC_URL" --keypair "$PAYER" >/dev/null
solana transfer   "$CLAIMANT_ADDRESS"   0.25   --allow-unfunded-recipient   --fee-payer "$PAYER"   --keypair "$PAYER"   --url "$RPC_URL"   >/dev/null

SETTLEMENT_REFRESH_ID_HEX="$(
  python3 -c 'import secrets; print(secrets.token_hex(32))'
)"
REFUND_REFRESH_ID_HEX="$(
  python3 -c 'import secrets; print(secrets.token_hex(32))'
)"

write_flow_addresses   "$PAYER_ADDRESS"   "$CLAIMANT_ADDRESS"   "$REWARD_MINT"   "$SETTLEMENT_REFRESH_ID_HEX"   "$REFUND_REFRESH_ID_HEX"   "$ADDRESSES"

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
SETTLEMENT_OWNER="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["settlement"]["refresh"])'     "$ADDRESSES"
)"
REFUND_OWNER="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["refund"]["refresh"])'     "$ADDRESSES"
)"

ensure_token_account "$RPC_URL" "$PAYER" "$PAYER_ADDRESS" "$REWARD_MINT" "$PAYER_ATA"
ensure_token_account "$RPC_URL" "$PAYER" "$CLAIMANT_ADDRESS" "$REWARD_MINT" "$CLAIMANT_ATA"
ensure_token_account   "$RPC_URL" "$PAYER" "$SETTLEMENT_OWNER" "$REWARD_MINT" "$SETTLEMENT_VAULT"
ensure_token_account   "$RPC_URL" "$PAYER" "$REFUND_OWNER" "$REWARD_MINT" "$REFUND_VAULT"

spl-token   --url "$RPC_URL"   --fee-payer "$PAYER"   mint   "$REWARD_MINT"   10   "$PAYER_ATA"   >/dev/null

export NOW_FLOW_RPC_URL="$RPC_URL"
export NOW_FLOW_PAYER_KEYPAIR="$PAYER"
export NOW_FLOW_CLAIMANT_KEYPAIR="$CLAIMANT"
export NOW_FLOW_REWARD_MINT="$REWARD_MINT"
export NOW_FLOW_SETTLEMENT_REFRESH_ID_HEX="$SETTLEMENT_REFRESH_ID_HEX"
export NOW_FLOW_REFUND_REFRESH_ID_HEX="$REFUND_REFRESH_ID_HEX"
export NOW_FLOW_ADDRESSES_FILE="$ADDRESSES"
export NOW_FLOW_MODE="exercise"

python3 "$ROOT/scripts/solana/protocol-flow-client.py"

echo "DEVNET_PROTOCOL_FLOW=PASS"
echo "DEVNET_FLOW_PAYER=$PAYER_ADDRESS"
echo "DEVNET_FLOW_REWARD_MINT=$REWARD_MINT"
