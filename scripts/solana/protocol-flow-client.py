#!/usr/bin/env python3
import base64
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import time
import urllib.request

PROGRAM_ID = "6HnAnrNjHWzyJ6RSDZtQ9mPWGwYehSmw1H8T2RKBwWwA"
TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"
BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
BASE58_INDEX = {c: i for i, c in enumerate(BASE58)}
PKCS8_ED25519_PREFIX = bytes.fromhex("302e020100300506032b657004220420")
TOKEN_DECIMALS = 6
SETTLEMENT_AMOUNT = 1_000_000
REFUND_AMOUNT = 500_000
CLAIM_DURATION_SECONDS = 120


def required_env(name):
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"missing environment variable {name}")
    return value


RPC_URL = required_env("NOW_FLOW_RPC_URL")
PAYER_KEYPAIR = required_env("NOW_FLOW_PAYER_KEYPAIR")
CLAIMANT_KEYPAIR = required_env("NOW_FLOW_CLAIMANT_KEYPAIR")
REWARD_MINT = required_env("NOW_FLOW_REWARD_MINT")
ADDRESSES_FILE = required_env("NOW_FLOW_ADDRESSES_FILE")
MODE = os.environ.get("NOW_FLOW_MODE", "exercise")
SETTLEMENT_REFRESH_ID = bytes.fromhex(
    required_env("NOW_FLOW_SETTLEMENT_REFRESH_ID_HEX")
)
REFUND_REFRESH_ID = bytes.fromhex(
    required_env("NOW_FLOW_REFUND_REFRESH_ID_HEX")
)


def b58decode(value):
    number = 0
    for char in value:
        number = number * 58 + BASE58_INDEX[char]
    body = number.to_bytes((number.bit_length() + 7) // 8, "big") if number else b""
    return b"\0" * (len(value) - len(value.lstrip("1"))) + body


def b58encode(data):
    zeros = len(data) - len(data.lstrip(b"\0"))
    number = int.from_bytes(data, "big")
    out = ""
    while number:
        number, remainder = divmod(number, 58)
        out = BASE58[remainder] + out
    return "1" * zeros + out


def shortvec(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            byte |= 0x80
        out.append(byte)
        if not value:
            return bytes(out)


def discriminator(name):
    return hashlib.sha256(f"global:{name}".encode()).digest()[:8]


def rpc_raw(method, params=None):
    payload = json.dumps({
        "jsonrpc": "2.0",
        "id": 1,
        "method": method,
        "params": params or [],
    }).encode()
    request = urllib.request.Request(
        RPC_URL,
        data=payload,
        headers={"content-type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def rpc(method, params=None):
    response = rpc_raw(method, params)
    if response.get("error"):
        raise RuntimeError(f"{method}: {response['error']}")
    if "result" not in response:
        raise RuntimeError(f"{method}: missing result")
    return response["result"]


def account_info(address):
    return rpc("getAccountInfo", [
        address,
        {"encoding": "base64", "commitment": "confirmed"},
    ])["value"]


def account_data(info):
    if info["data"][1] != "base64":
        raise RuntimeError("expected base64 account data")
    return base64.b64decode(info["data"][0])


def token_balance(address):
    value = rpc("getTokenAccountBalance", [
        address,
        {"commitment": "confirmed"},
    ])["value"]
    if value["decimals"] != TOKEN_DECIMALS:
        raise RuntimeError("token decimals mismatch")
    return int(value["amount"])


def load_keypair(path):
    raw = json.loads(Path(path).read_text())
    if not isinstance(raw, list) or len(raw) != 64:
        raise RuntimeError(f"invalid Solana keypair: {path}")
    if any(not isinstance(value, int) or value < 0 or value > 255 for value in raw):
        raise RuntimeError(f"invalid Solana keypair byte: {path}")
    key = bytes(raw)
    return {"seed": key[:32], "public": key[32:], "address": b58encode(key[32:])}


def sign_message(message, seed):
    with tempfile.TemporaryDirectory() as tmp:
        key_path = Path(tmp) / "key.der"
        msg_path = Path(tmp) / "message.bin"
        sig_path = Path(tmp) / "signature.bin"
        key_path.write_bytes(PKCS8_ED25519_PREFIX + seed)
        msg_path.write_bytes(message)
        subprocess.run([
            "openssl", "pkeyutl", "-sign", "-rawin",
            "-inkey", str(key_path), "-keyform", "DER",
            "-in", str(msg_path), "-out", str(sig_path),
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        signature = sig_path.read_bytes()
    if len(signature) != 64:
        raise RuntimeError("Ed25519 signature must be 64 bytes")
    return signature


def compile_message(signer, metas, instruction_data, blockhash):
    merged = {signer: {"signer": True, "writable": True}}
    for address, is_signer, is_writable in metas:
        flags = merged.setdefault(
            address, {"signer": False, "writable": False}
        )
        flags["signer"] = flags["signer"] or is_signer
        flags["writable"] = flags["writable"] or is_writable
    merged.setdefault(PROGRAM_ID, {"signer": False, "writable": False})

    extra_signers = [
        address
        for address, flags in merged.items()
        if flags["signer"] and address != signer
    ]
    if extra_signers:
        raise RuntimeError(f"unsupported additional signers: {extra_signers}")

    writable = [
        address
        for address, flags in merged.items()
        if address != signer and not flags["signer"] and flags["writable"]
    ]
    readonly = [
        address
        for address, flags in merged.items()
        if address != signer and not flags["signer"] and not flags["writable"]
    ]
    keys = [signer, *writable, *readonly]
    index = {address: position for position, address in enumerate(keys)}
    instruction_indices = bytes(index[address] for address, _, _ in metas)

    return b"".join([
        bytes([1, 0, len(readonly)]),
        shortvec(len(keys)),
        b"".join(b58decode(address) for address in keys),
        blockhash,
        shortvec(1),
        bytes([index[PROGRAM_ID]]),
        shortvec(len(instruction_indices)),
        instruction_indices,
        shortvec(len(instruction_data)),
        instruction_data,
    ])


def signed_transaction(keypair, metas, instruction_data):
    blockhash = b58decode(rpc("getLatestBlockhash", [
        {"commitment": "confirmed"},
    ])["value"]["blockhash"])
    if len(blockhash) != 32:
        raise RuntimeError("invalid blockhash")
    message = compile_message(
        keypair["address"], metas, instruction_data, blockhash
    )
    signature = sign_message(message, keypair["seed"])
    return shortvec(1) + signature + message


def wait_signature(signature):
    for _ in range(100):
        value = rpc("getSignatureStatuses", [
            [signature],
            {"searchTransactionHistory": True},
        ])["value"][0]
        if value and value.get("confirmationStatus") in ("confirmed", "finalized"):
            return value
        time.sleep(0.25)
    raise RuntimeError(f"transaction confirmation timeout: {signature}")


def submit(label, keypair, metas, instruction_data):
    transaction = signed_transaction(keypair, metas, instruction_data)
    response = rpc_raw("sendTransaction", [
        base64.b64encode(transaction).decode(),
        {
            "encoding": "base64",
            "skipPreflight": False,
            "preflightCommitment": "confirmed",
        },
    ])
    if response.get("error") or not response.get("result"):
        raise RuntimeError(f"{label} failed: {response.get('error')}")
    signature = response["result"]
    status = wait_signature(signature)
    if status.get("err") is not None:
        raise RuntimeError(f"{label} failed: {status['err']}")
    print(f"FLOW_{label}_SIGNATURE={signature}")
    return signature


def submit_without_confirmation(label, keypair, metas, instruction_data):
    transaction = signed_transaction(keypair, metas, instruction_data)
    response = rpc_raw("sendTransaction", [
        base64.b64encode(transaction).decode(),
        {
            "encoding": "base64",
            "skipPreflight": False,
            "preflightCommitment": "confirmed",
        },
    ])
    if response.get("error") or not response.get("result"):
        raise RuntimeError(f"{label} failed: {response.get('error')}")
    print(f"FLOW_{label}_OUTCOME=AMBIGUOUS")


def expect_failure(label, keypair, metas, instruction_data):
    transaction = signed_transaction(keypair, metas, instruction_data)
    response = rpc_raw("sendTransaction", [
        base64.b64encode(transaction).decode(),
        {
            "encoding": "base64",
            "skipPreflight": False,
            "preflightCommitment": "confirmed",
        },
    ])
    if response.get("error"):
        print(f"FLOW_{label}_REJECT=PASS")
        return
    signature = response.get("result")
    if not signature:
        raise RuntimeError(f"{label} returned no error or signature")
    status = wait_signature(signature)
    if status.get("err") is None:
        raise RuntimeError(f"{label} unexpectedly succeeded")
    print(f"FLOW_{label}_REJECT=PASS")


def meta(address, signer=False, writable=False):
    return (address, signer, writable)


def create_refresh_data(refresh_id, state_digest, intent_hash, expires_at):
    return b"".join([
        discriminator("create_refresh"),
        refresh_id,
        state_digest,
        intent_hash,
        struct.pack("<q", expires_at),
        bytes([0, 1, 1, 0]),
    ])


def contribute_data(refresh_id, amount):
    return (
        discriminator("contribute")
        + refresh_id
        + struct.pack("<Q", amount)
    )


def claim_data(refresh_id):
    return (
        discriminator("claim_witness")
        + refresh_id
        + struct.pack("<I", CLAIM_DURATION_SECONDS)
    )


def cancel_data(refresh_id):
    return discriminator("cancel_unclaimed_refresh") + refresh_id


def refund_data(refresh_id):
    return discriminator("refund_contribution") + refresh_id


def settle_data(refresh_id, operation_hash, result_digest):
    return (
        discriminator("settle_refresh")
        + refresh_id
        + operation_hash
        + result_digest
    )


def decode_config(data):
    if len(data) != 142:
        raise RuntimeError(f"unexpected config size {len(data)}")
    return {
        "verifier": b58encode(data[42:74]),
        "mint": b58encode(data[74:106]),
    }


def decode_refresh(data):
    if len(data) != 500:
        raise RuntimeError(f"unexpected refresh size {len(data)}")
    return {
        "status": data[254],
        "total_funded": struct.unpack_from("<Q", data, 255)[0],
        "locked_reward": struct.unpack_from("<Q", data, 263)[0],
        "funding_locked": data[271] != 0,
        "settled_amount": struct.unpack_from("<Q", data, 419)[0],
        "operation_hash": data[467:499],
    }


def decode_contribution(data):
    if len(data) != 107:
        raise RuntimeError(f"unexpected contribution size {len(data)}")
    return {
        "contributed": struct.unpack_from("<Q", data, 74)[0],
        "refunded": struct.unpack_from("<Q", data, 82)[0],
    }


def require_account(address, label):
    info = account_info(address)
    if info is None:
        raise RuntimeError(f"missing {label} account {address}")
    return info


def reconcile_settlement(address, expected_operation_hash, expected_amount):
    for _ in range(100):
        info = account_info(address)
        if info is not None:
            state = decode_refresh(account_data(info))
            if state["status"] == 2:
                if state["settled_amount"] != expected_amount:
                    raise RuntimeError("reconciled settlement amount mismatch")
                if state["operation_hash"] != expected_operation_hash:
                    raise RuntimeError("reconciled settlement operation hash mismatch")
                print("FLOW_SETTLEMENT_RECONCILIATION=PASS")
                return
        time.sleep(0.25)
    raise RuntimeError("settlement reconciliation timeout")


def reconcile_refund(refresh_address, contribution_address, expected_amount):
    for _ in range(100):
        refresh_info = account_info(refresh_address)
        contribution_info = account_info(contribution_address)
        if refresh_info is not None and contribution_info is not None:
            state = decode_refresh(account_data(refresh_info))
            contribution = decode_contribution(account_data(contribution_info))
            if state["status"] == 4 and contribution["refunded"] == expected_amount:
                if contribution["contributed"] != expected_amount:
                    raise RuntimeError("reconciled refund contribution mismatch")
                if state["settled_amount"] != 0:
                    raise RuntimeError("reconciled refund has settlement amount")
                print("FLOW_REFUND_RECONCILIATION=PASS")
                return
        time.sleep(0.25)
    raise RuntimeError("refund reconciliation timeout")


def maybe_initialize(payer, addresses):
    info = account_info(addresses["config"])
    if info is not None:
        config = decode_config(account_data(info))
        if config["verifier"] != payer["address"]:
            raise RuntimeError("existing config verifier does not match payer")
        if config["mint"] != REWARD_MINT:
            raise RuntimeError("existing config reward mint mismatch")
        print("FLOW_CONFIG_REUSE=PASS")
        return
    submit("INITIALIZE_PROTOCOL", payer, [
        meta(payer["address"], True, True),
        meta(addresses["config"], False, True),
        meta(REWARD_MINT),
        meta(TOKEN_PROGRAM_ID),
        meta(SYSTEM_PROGRAM_ID),
    ], discriminator("initialize_protocol") + payer["public"])


def create_refresh(payer, addresses, name, refresh_id, intent_hash, expires_at):
    item = addresses[name]
    submit(f"CREATE_{name.upper()}", payer, [
        meta(payer["address"], True, True),
        meta(addresses["config"]),
        meta(item["refresh"], False, True),
        meta(REWARD_MINT),
        meta(item["vault"]),
        meta(TOKEN_PROGRAM_ID),
        meta(SYSTEM_PROGRAM_ID),
    ], create_refresh_data(
        refresh_id,
        hashlib.sha256(f"state:{name}".encode()).digest(),
        intent_hash,
        expires_at,
    ))


def contribute(payer, addresses, name, refresh_id, amount):
    item = addresses[name]
    submit(f"FUND_{name.upper()}", payer, [
        meta(payer["address"], True, True),
        meta(addresses["config"]),
        meta(item["refresh"], False, True),
        meta(item["contribution"], False, True),
        meta(addresses["payerAta"], False, True),
        meta(item["vault"], False, True),
        meta(REWARD_MINT),
        meta(TOKEN_PROGRAM_ID),
        meta(SYSTEM_PROGRAM_ID),
    ], contribute_data(refresh_id, amount))


def exercise():
    payer = load_keypair(PAYER_KEYPAIR)
    claimant = load_keypair(CLAIMANT_KEYPAIR)
    addresses = json.loads(Path(ADDRESSES_FILE).read_text())
    if (
        addresses["payer"] != payer["address"]
        or addresses["claimant"] != claimant["address"]
    ):
        raise RuntimeError("address file signer drift")
    if addresses["rewardMint"] != REWARD_MINT:
        raise RuntimeError("address file mint drift")

    maybe_initialize(payer, addresses)
    expires_at = int(time.time()) + 900
    settlement_intent = hashlib.sha256(
        b"now-flow-settlement-intent-v1"
    ).digest()
    refund_intent = hashlib.sha256(b"now-flow-refund-intent-v1").digest()

    create_refresh(
        payer,
        addresses,
        "settlement",
        SETTLEMENT_REFRESH_ID,
        settlement_intent,
        expires_at,
    )
    contribute(
        payer,
        addresses,
        "settlement",
        SETTLEMENT_REFRESH_ID,
        SETTLEMENT_AMOUNT,
    )

    settlement = addresses["settlement"]
    expect_failure("SELF_CLAIM", payer, [
        meta(payer["address"], True),
        meta(addresses["config"]),
        meta(settlement["refresh"], False, True),
        meta(REWARD_MINT),
        meta(addresses["payerAta"]),
    ], claim_data(SETTLEMENT_REFRESH_ID))

    submit("CLAIM_SETTLEMENT", claimant, [
        meta(claimant["address"], True),
        meta(addresses["config"]),
        meta(settlement["refresh"], False, True),
        meta(REWARD_MINT),
        meta(addresses["claimantAta"]),
    ], claim_data(SETTLEMENT_REFRESH_ID))

    before_claimant = token_balance(addresses["claimantAta"])
    execution_hash = hashlib.sha256(b"".join([
        settlement_intent,
        struct.pack("<Q", SETTLEMENT_AMOUNT),
        b58decode(settlement["refresh"]),
    ])).digest()
    operation_id = hashlib.sha256(
        b"now-flow-settlement-operation-v1"
    ).digest()
    operation_hash = hashlib.sha256(b"".join([
        b58decode(settlement["refresh"]),
        execution_hash,
        operation_id,
    ])).digest()
    result_digest = hashlib.sha256(
        b"now-flow-verification-result-v1"
    ).digest()
    settle_metas = [
        meta(payer["address"], True),
        meta(addresses["config"]),
        meta(settlement["refresh"], False, True),
        meta(settlement["vault"], False, True),
        meta(REWARD_MINT),
        meta(TOKEN_PROGRAM_ID),
        meta(addresses["claimantAta"], False, True),
    ]
    settle_payload = settle_data(
        SETTLEMENT_REFRESH_ID,
        operation_hash,
        result_digest,
    )
    unauthorized_settle_metas = [
        meta(claimant["address"], True),
        meta(addresses["config"]),
        meta(settlement["refresh"], False, True),
        meta(settlement["vault"], False, True),
        meta(REWARD_MINT),
        meta(TOKEN_PROGRAM_ID),
        meta(addresses["claimantAta"], False, True),
    ]
    expect_failure(
        "UNAUTHORIZED_SETTLEMENT",
        claimant,
        unauthorized_settle_metas,
        settle_payload,
    )
    submit_without_confirmation(
        "SETTLE_REFRESH",
        payer,
        settle_metas,
        settle_payload,
    )
    reconcile_settlement(
        settlement["refresh"],
        operation_hash,
        SETTLEMENT_AMOUNT,
    )

    after_claimant = token_balance(addresses["claimantAta"])
    if after_claimant - before_claimant != SETTLEMENT_AMOUNT:
        raise RuntimeError("claimant payout mismatch")
    if token_balance(settlement["vault"]) != 0:
        raise RuntimeError("settlement vault not empty")

    state = decode_refresh(account_data(require_account(
        settlement["refresh"],
        "settlement refresh",
    )))
    if state["status"] != 2 or state["settled_amount"] != SETTLEMENT_AMOUNT:
        raise RuntimeError("settlement terminal state mismatch")
    if state["operation_hash"] != operation_hash:
        raise RuntimeError("settlement operation hash mismatch")
    if (
        not state["funding_locked"]
        or state["locked_reward"] != SETTLEMENT_AMOUNT
    ):
        raise RuntimeError("settlement funding lock mismatch")
    expect_failure(
        "DUPLICATE_SETTLEMENT",
        payer,
        settle_metas,
        settle_payload,
    )
    if token_balance(addresses["claimantAta"]) != after_claimant:
        raise RuntimeError("duplicate settlement changed payout")
    print("FLOW_SETTLEMENT_PATH=PASS")

    create_refresh(
        payer,
        addresses,
        "refund",
        REFUND_REFRESH_ID,
        refund_intent,
        expires_at,
    )
    payer_before = token_balance(addresses["payerAta"])
    contribute(
        payer,
        addresses,
        "refund",
        REFUND_REFRESH_ID,
        REFUND_AMOUNT,
    )
    refund = addresses["refund"]
    submit("CANCEL_REFUND", payer, [
        meta(payer["address"], True),
        meta(refund["refresh"], False, True),
    ], cancel_data(REFUND_REFRESH_ID))

    refund_metas = [
        meta(payer["address"], True),
        meta(addresses["config"]),
        meta(refund["refresh"], False, True),
        meta(refund["contribution"], False, True),
        meta(refund["vault"], False, True),
        meta(addresses["payerAta"], False, True),
        meta(REWARD_MINT),
        meta(TOKEN_PROGRAM_ID),
    ]
    refund_payload = refund_data(REFUND_REFRESH_ID)
    submit_without_confirmation(
        "REFUND_CONTRIBUTION",
        payer,
        refund_metas,
        refund_payload,
    )
    reconcile_refund(
        refund["refresh"],
        refund["contribution"],
        REFUND_AMOUNT,
    )
    if token_balance(addresses["payerAta"]) != payer_before:
        raise RuntimeError("refund did not restore funder balance")
    if token_balance(refund["vault"]) != 0:
        raise RuntimeError("refund vault not empty")

    contribution = decode_contribution(account_data(require_account(
        refund["contribution"],
        "refund contribution",
    )))
    if (
        contribution["contributed"] != REFUND_AMOUNT
        or contribution["refunded"] != REFUND_AMOUNT
    ):
        raise RuntimeError("refund contribution accounting mismatch")

    refund_state = decode_refresh(account_data(require_account(
        refund["refresh"],
        "refund refresh",
    )))
    if refund_state["status"] != 4 or refund_state["settled_amount"] != 0:
        raise RuntimeError("refund terminal state mismatch")
    expect_failure(
        "DUPLICATE_REFUND",
        payer,
        refund_metas,
        refund_payload,
    )
    if token_balance(addresses["payerAta"]) != payer_before:
        raise RuntimeError("duplicate refund changed balance")

    print("FLOW_REFUND_PATH=PASS")
    print("PROTOCOL_FLOW_QUALIFICATION=PASS")


if MODE != "exercise":
    raise RuntimeError(f"unsupported NOW_FLOW_MODE {MODE}")
exercise()
