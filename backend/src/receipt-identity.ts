import { canonicalJson } from "../../packages/policy/src/index.ts";

const encoder = new TextEncoder();

function hex(value: Uint8Array): string {
  return [...value]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

export type ReceiptDigestInput = {
  refreshId: string;
  stateId: string;
  verificationResultId: string;
  settlementId: string;
  settlementOperationHash: Uint8Array;
  finalValue: unknown;
  observedAt: Date;
  verificationClass: string;
  rewardAmountAtomic: bigint;
  rewardMint: string;
  verificationDigest: Uint8Array;
  settlementSignature: string;
  chainCommitment: "finalized";
  settlementFinalizedAt: Date;
};

export async function deriveReceiptDigestV1(
  input: ReceiptDigestInput,
): Promise<Uint8Array> {
  if (
    input.settlementOperationHash.length !== 32 ||
    input.verificationDigest.length !== 32
  ) {
    throw new TypeError("receipt authority digests must be 32 bytes");
  }
  if (
    input.rewardAmountAtomic <= 0n ||
    input.rewardMint.trim().length === 0 ||
    input.settlementSignature.trim().length === 0
  ) {
    throw new TypeError("receipt settlement authority is incomplete");
  }

  const canonical = canonicalJson({
    version: 1,
    refresh_id: input.refreshId,
    state_id: input.stateId,
    verification_result_id: input.verificationResultId,
    settlement_id: input.settlementId,
    settlement_operation_hash: hex(input.settlementOperationHash),
    final_value: input.finalValue,
    observed_at: input.observedAt.toISOString(),
    verification_class: input.verificationClass,
    reward_amount_atomic: input.rewardAmountAtomic.toString(),
    reward_mint: input.rewardMint,
    verification_digest: hex(input.verificationDigest),
    settlement_signature: input.settlementSignature,
    chain_commitment: input.chainCommitment,
    settlement_finalized_at: input.settlementFinalizedAt.toISOString(),
  });
  const bytes = encoder.encode(canonical);
  const digestInput = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(digestInput).set(bytes);
  return new Uint8Array(
    await crypto.subtle.digest("SHA-256", digestInput),
  );
}
