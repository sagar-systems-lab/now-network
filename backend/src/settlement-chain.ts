import type { SettlementOperation } from "./settlement-repository.ts";

export type PreparedSettlementAttempt = {
  signature: string;
  recentBlockhash: string;
  lastValidBlockHeight: number;
  signedTransactionBase64: string;
};

export type SettlementBroadcast =
  | { kind: "accepted" }
  | { kind: "ambiguous"; errorCode: string };

export type SettlementInspection =
  | { kind: "pending" }
  | {
    kind: "confirmed";
    commitment: "confirmed" | "finalized";
    signature: string;
    observedAt: Date;
  }
  | { kind: "not_settled"; observedAt: Date }
  | { kind: "authority_conflict"; errorCode: string; observedAt: Date };

export interface SettlementChainClient {
  prepare(operation: SettlementOperation): Promise<PreparedSettlementAttempt>;
  broadcast(attempt: PreparedSettlementAttempt): Promise<SettlementBroadcast>;
  inspect(operation: SettlementOperation): Promise<SettlementInspection>;
}

export class SettlementChainError extends Error {
  constructor(
    readonly code: string,
    readonly retryable: boolean,
    message: string,
  ) {
    super(message);
    this.name = "SettlementChainError";
  }
}
