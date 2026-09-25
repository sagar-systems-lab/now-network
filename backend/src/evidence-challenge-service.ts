import { ApiFault } from "./errors.ts";
import type { ActorRecord } from "./identity-repository.ts";
import type {
  EvidenceChallengeContext,
  EvidenceChallengeRepository,
  IssueEvidenceChallengeResult,
} from "./evidence-challenge-repository.ts";
import { transitionClaim } from "../../packages/domain/src/claim-machine.ts";
import { policyVersion } from "../../packages/policy/src/template.ts";

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

function copyToArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(
    await crypto.subtle.digest("SHA-256", copyToArrayBuffer(bytes)),
  );
}

function assertActorActive(actor: ActorRecord): void {
  if (actor.status === "DISABLED") {
    throw new ApiFault(403, "ACTOR_DISABLED", "This actor is disabled.");
  }
  if (actor.status === "RESTRICTED") {
    throw new ApiFault(403, "ACTOR_RESTRICTED", "This actor is restricted.");
  }
}

function challengeDeadline(context: EvidenceChallengeContext): Date {
  if (context.claimDeadline === null) {
    throw new ApiFault(409, "CLAIM_NOT_AVAILABLE", "This claim cannot capture evidence.");
  }

  return new Date(
    Math.min(
      context.claimDeadline.getTime(),
      context.evidenceDeadline.getTime(),
      context.refreshExpiresAt.getTime(),
    ),
  );
}

function ensureChallengeable(
  context: EvidenceChallengeContext,
  actorId: string,
  now: Date,
): Date {
  if (context.actorId !== actorId) {
    throw new ApiFault(404, "CLAIM_NOT_FOUND", "Claim was not found.");
  }
  if (context.claimStatus !== "CLAIMED" && context.claimStatus !== "CAPTURE_ACTIVE") {
    throw new ApiFault(409, "CLAIM_NOT_AVAILABLE", "This claim cannot capture evidence.");
  }
  if (
    context.refreshStatus !== "AVAILABLE" &&
    context.refreshStatus !== "CLAIMED" &&
    context.refreshStatus !== "CAPTURE_IN_PROGRESS"
  ) {
    throw new ApiFault(409, "CLAIM_NOT_AVAILABLE", "This claim cannot capture evidence.");
  }

  const expiresAt = challengeDeadline(context);
  if (expiresAt.getTime() <= now.getTime()) {
    throw new ApiFault(410, "CHALLENGE_EXPIRED", "The evidence capture window expired.");
  }
  return expiresAt;
}

function issueFault(
  result: Exclude<IssueEvidenceChallengeResult, { kind: "issued" }>,
): ApiFault {
  switch (result.kind) {
    case "not_found":
    case "actor_mismatch":
      return new ApiFault(404, "CLAIM_NOT_FOUND", "Claim was not found.");
    case "not_available":
      return new ApiFault(409, "CLAIM_NOT_AVAILABLE", "This claim cannot capture evidence.");
    case "expired":
      return new ApiFault(410, "CHALLENGE_EXPIRED", "The evidence capture window expired.");
    case "policy_mismatch":
      return new ApiFault(409, "REVISION_CONFLICT", "The evidence policy changed.");
  }
}

export class EvidenceChallengeService {
  constructor(
    private readonly repository: EvidenceChallengeRepository,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async issue(
    actor: ActorRecord,
    acceptanceId: string,
  ): Promise<Record<string, unknown>> {
    assertActorActive(actor);

    const context = await this.repository.getContext(acceptanceId);
    if (context === null || context.actorId !== actor.actorId) {
      throw new ApiFault(404, "CLAIM_NOT_FOUND", "Claim was not found.");
    }

    const issuedAt = this.now();
    const expiresAt = ensureChallengeable(context, actor.actorId, issuedAt);
    if (context.claimStatus === "CLAIMED") {
      transitionClaim("CLAIMED", "CAPTURE_STARTED", {
        challengeIssued: true,
        refreshExpired: false,
      });
    }

    const nonceBytes = crypto.getRandomValues(new Uint8Array(32));
    const nonce = bytesToBase64Url(nonceBytes);
    const challengeId = crypto.randomUUID();
    const version = policyVersion(context.proofPolicySnapshot.template_key);

    const result = await this.repository.issueChallenge({
      challengeId,
      acceptanceId,
      actorId: actor.actorId,
      nonceHash: await sha256(nonceBytes),
      issuedAt,
      expiresAt,
      policyVersion: version,
    });
    if (result.kind !== "issued") throw issueFault(result);

    return {
      challenge_id: result.challenge.challengeId,
      refresh_id: result.challenge.refreshId,
      acceptance_id: result.challenge.acceptanceId,
      nonce,
      issued_at: result.challenge.issuedAt.toISOString(),
      expires_at: result.challenge.expiresAt.toISOString(),
      policy_version: result.challenge.policyVersion,
      capture: {
        media_required: context.proofPolicySnapshot.capture.media_required,
        location_required: context.proofPolicySnapshot.capture.location_required,
      },
      claim_status: result.claimStatus,
      claim_revision: result.claimRevision,
    };
  }
}
