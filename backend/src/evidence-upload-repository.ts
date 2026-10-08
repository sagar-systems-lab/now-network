export type EvidenceUploadChallengeStatus =
  | "ISSUED"
  | "CONSUMED"
  | "EXPIRED"
  | "REVOKED";

export type EvidenceUploadAuthorization = {
  signedUrl: string;
  videoSignedUrl?: string;
  expiresAt: string;
};

export type EvidenceUploadContext = {
  challengeId: string;
  refreshId: string;
  acceptanceId: string;
  actorId: string;
  nonceHash: Uint8Array;
  challengeStatus: EvidenceUploadChallengeStatus;
  challengeExpiresAt: Date;
  claimStatus: string;
  refreshStatus: string;
  evidenceId: string | null;
  objectKey: string | null;
  mediaMime: string | null;
  videoRequired?: boolean;
  authorization?: EvidenceUploadAuthorization | null;
};

export type ReserveEvidenceUploadResult =
  | {
    kind: "ready";
    evidenceId: string;
    objectKey: string;
    mediaMime: string;
    challengeExpiresAt: Date;
    replayed: boolean;
    authorization?: EvidenceUploadAuthorization | null;
  }
  | { kind: "not_found" }
  | { kind: "actor_mismatch" }
  | { kind: "challenge_invalid" }
  | { kind: "challenge_expired" }
  | { kind: "nonce_mismatch" }
  | { kind: "upload_conflict" }
  | { kind: "claim_not_active" };

export interface EvidenceUploadRepository {
  getContext(challengeId: string): Promise<EvidenceUploadContext | null>;

  reserveUpload(input: {
    challengeId: string;
    actorId: string;
    nonceHash: Uint8Array;
    evidenceId: string;
    objectKey: string;
    mediaMime: string;
    observedAt: Date;
  }): Promise<ReserveEvidenceUploadResult>;

  saveAuthorization(input: {
    challengeId: string;
    actorId: string;
    evidenceId: string;
    authorization: EvidenceUploadAuthorization;
    observedAt: Date;
  }): Promise<EvidenceUploadAuthorization | null>;
}
