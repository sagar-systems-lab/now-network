export type ActorStatus = "ACTIVE" | "DISABLED" | "RESTRICTED";

export type ActorRecord = {
  actorId: string;
  status: ActorStatus;
  revision: number;
};

export type WalletBindingRecord = {
  walletBindingId: string;
  actorId: string;
  walletAddress: string;
  cluster: string;
  status: "ACTIVE" | "REVOKED" | "SUPERSEDED";
  revision: number;
};

export type WalletBindingChallengeRecord = {
  challengeId: string;
  actorId: string;
  authUserId: string;
  walletAddress: string;
  cluster: string;
  purpose: "wallet_binding";
  domain: "NOW Network";
  message: string;
  messageSha256: Uint8Array;
  nonceHash: Uint8Array;
  status: "ISSUED" | "CONSUMED" | "EXPIRED" | "REVOKED";
  issuedAt: Date;
  expiresAt: Date;
  consumedAt: Date | null;
};

export type ChallengeConsumptionResult =
  | { kind: "bound"; actor: ActorRecord; binding: WalletBindingRecord; recovered: boolean }
  | { kind: "not_found" }
  | { kind: "expired" }
  | { kind: "consumed" }
  | { kind: "revoked" }
  | { kind: "actor_mismatch" }
  | { kind: "actor_disabled" }
  | { kind: "actor_restricted" }
  | { kind: "binding_conflict" };

export interface IdentityRepository {
  resolveActor(authUserId: string, principalType: string): Promise<ActorRecord>;
  listWalletBindings(actorId: string): Promise<WalletBindingRecord[]>;
  issueWalletBindingChallenge(challenge: WalletBindingChallengeRecord): Promise<void>;
  getWalletBindingChallenge(challengeId: string): Promise<WalletBindingChallengeRecord | null>;
  consumeWalletBindingChallenge(
    challengeId: string,
    authUserId: string,
    now: Date,
  ): Promise<ChallengeConsumptionResult>;
}
