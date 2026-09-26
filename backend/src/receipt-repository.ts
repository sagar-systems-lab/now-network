export type ReceiptAuthority = {
  refreshId: string;
  stateId: string;
  requesterActorId: string;
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

export type ReceiptRecord = {
  receiptId: string;
  refreshId: string;
  stateId: string;
  verificationResultId: string;
  settlementId: string;
  status: "FINAL";
  finalValue: unknown;
  observedAt: Date;
  verificationClass: string;
  rewardAmountAtomic: bigint;
  rewardMint: string;
  verificationDigest: Uint8Array;
  settlementOperationHash: Uint8Array;
  settlementSignature: string;
  chainCommitment: "finalized";
  receiptDigest: Uint8Array;
  finalizedAt: Date;
  revision: number;
};

export type FinalizeReceiptResult =
  | { kind: "created" | "replayed"; receipt: ReceiptRecord }
  | { kind: "not_finalizable" }
  | { kind: "authority_conflict" };

export interface ReceiptRepository {
  findFinalizable(limit: number): Promise<ReceiptAuthority[]>;

  finalize(input: {
    authority: ReceiptAuthority;
    receiptId: string;
    receiptDigest: Uint8Array;
    observedAt: Date;
  }): Promise<FinalizeReceiptResult>;

  getForActor(
    refreshId: string,
    actorId: string,
  ): Promise<ReceiptRecord | null>;
}
