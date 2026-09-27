#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROGRAM_ID="sE74tJL2pCSWMHhEGvBM5hL2DYmFaUUQCDpC1QkHE3T"
readonly TOKEN_PROGRAM_ID="TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
readonly ASSOCIATED_TOKEN_PROGRAM_ID="ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"

derive_pda() {
  local program_id="$1"
  shift
  solana --output json find-program-derived-address "$program_id" "$@" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["address"])'
}

derive_ata() {
  local owner="$1"
  local mint="$2"
  derive_pda     "$ASSOCIATED_TOKEN_PROGRAM_ID"     "pubkey:$owner"     "pubkey:$TOKEN_PROGRAM_ID"     "pubkey:$mint"
}

write_flow_addresses() {
  local payer="$1"
  local claimant="$2"
  local mint="$3"
  local settlement_id="$4"
  local refund_id="$5"
  local output="$6"

  local config
  local payer_ata
  local claimant_ata
  local settlement_refresh
  local settlement_contribution
  local settlement_vault
  local refund_refresh
  local refund_contribution
  local refund_vault

  config="$(derive_pda "$PROGRAM_ID" string:config)"
  payer_ata="$(derive_ata "$payer" "$mint")"
  claimant_ata="$(derive_ata "$claimant" "$mint")"

  settlement_refresh="$(
    derive_pda "$PROGRAM_ID" string:refresh "hex:$settlement_id"
  )"
  settlement_contribution="$(
    derive_pda       "$PROGRAM_ID"       string:contribution       "pubkey:$settlement_refresh"       "pubkey:$payer"
  )"
  settlement_vault="$(derive_ata "$settlement_refresh" "$mint")"

  refund_refresh="$(
    derive_pda "$PROGRAM_ID" string:refresh "hex:$refund_id"
  )"
  refund_contribution="$(
    derive_pda       "$PROGRAM_ID"       string:contribution       "pubkey:$refund_refresh"       "pubkey:$payer"
  )"
  refund_vault="$(derive_ata "$refund_refresh" "$mint")"

  python3 -     "$output"     "$payer"     "$claimant"     "$mint"     "$config"     "$payer_ata"     "$claimant_ata"     "$settlement_refresh"     "$settlement_contribution"     "$settlement_vault"     "$refund_refresh"     "$refund_contribution"     "$refund_vault" <<'PY'
import json
import sys

(
    output,
    payer,
    claimant,
    mint,
    config,
    payer_ata,
    claimant_ata,
    settlement_refresh,
    settlement_contribution,
    settlement_vault,
    refund_refresh,
    refund_contribution,
    refund_vault,
) = sys.argv[1:]

data = {
    "programId": "sE74tJL2pCSWMHhEGvBM5hL2DYmFaUUQCDpC1QkHE3T",
    "payer": payer,
    "claimant": claimant,
    "rewardMint": mint,
    "config": config,
    "payerAta": payer_ata,
    "claimantAta": claimant_ata,
    "settlement": {
        "refresh": settlement_refresh,
        "contribution": settlement_contribution,
        "vault": settlement_vault,
    },
    "refund": {
        "refresh": refund_refresh,
        "contribution": refund_contribution,
        "vault": refund_vault,
    },
}

with open(output, "w", encoding="utf-8") as handle:
    json.dump(data, handle, indent=2)
    handle.write("\n")
PY
}

ensure_token_account() {
  local rpc_url="$1"
  local payer_keypair="$2"
  local owner="$3"
  local mint="$4"
  local expected="$5"

  if solana account "$expected" --url "$rpc_url" >/dev/null 2>&1; then
    return
  fi

  spl-token \
    --url "$rpc_url" \
    --fee-payer "$payer_keypair" \
    create-account \
    --owner "$owner" \
    "$mint" \
    >/dev/null

  solana account "$expected" --url "$rpc_url" >/dev/null
}
