import type { RefreshStatus } from "../../packages/contracts/src/lifecycle.ts";
import type { ClaimStatus } from "../../packages/domain/src/claim-machine.ts";
import type { PolicyTemplateV1 } from "../../packages/policy/src/types.ts";

export type EvidenceChallengeStatus = "ISSUED" | "CONSUMED" | "EXPIRED" | "REVOKED";

export type EvidenceChallengeContext = {
  acceptanceId: string;
  refreshId: string;
  actorId: string;
  walletAddress: string;
  claimStatus: ClaimStatus;
  claimDeadline: Date | null;
  claimRevision: number;
  refreshStatus: RefreshStatus;
  refreshExpiresAt: Date;
  evidenceDeadline: Date;
  proofPolicySnapshot: PolicyTemplateV1;
};

export type EvidenceChallengeRecord = {
  challengeId: string;
  refreshId: string;
  acceptanceId: string;
  actorId: string;
  walletAddress: string;
  nonceHash: Uint8Array;
  status: EvidenceChallengeStatus;
  issuedAt: Date;
  expiresAt: Date;
  consumedAt: Date | null;
  revokedAt: Date | null;
  policyVersion: number;
};

export type IssueEvidenceChallengeResult =
  | {
    kind: "issued";
    challenge: EvidenceChallengeRecord;
    claimStatus: "CAPTURE_ACTIVE";
    claimRevision: number;
  }
  | { kind: "not_found" }
  | { kind: "actor_mismatch" }
  | { kind: "not_available" }
  | { kind: "expired" }
  | { kind: "policy_mismatch" };

export interface EvidenceChallengeRepository {
  getContext(acceptanceId: string): Promise<EvidenceChallengeContext | null>;

  issueChallenge(input: {
    challengeId: string;
    acceptanceId: string;
    actorId: string;
    nonceHash: Uint8Array;
    issuedAt: Date;
    expiresAt: Date;
    policyVersion: number;
  }): Promise<IssueEvidenceChallengeResult>;
}
