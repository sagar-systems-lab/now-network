import { ApiFault } from "../src/errors.ts";
import { EvidenceChallengeService } from "../src/evidence-challenge-service.ts";
import type {
  EvidenceChallengeContext,
  EvidenceChallengeRepository,
  IssueEvidenceChallengeResult,
} from "../src/evidence-challenge-repository.ts";
import type { ActorRecord } from "../src/identity-repository.ts";

const ACTOR_ID = "a0000000-0000-4000-8000-000000000001";
const OTHER_ACTOR_ID = "a0000000-0000-4000-8000-000000000002";
const REFRESH_ID = "a1000000-0000-4000-8000-000000000001";
const ACCEPTANCE_ID = "a2000000-0000-4000-8000-000000000001";
const CHALLENGE_ID = "a3000000-0000-4000-8000-000000000001";
const WALLET = "GuardWallet";
const NOW = new Date("2026-09-25T12:00:00.000Z");

function actor(status: ActorRecord["status"] = "ACTIVE"): ActorRecord {
  return { actorId: ACTOR_ID, status, revision: 1 };
}

function context(
  overrides: Partial<EvidenceChallengeContext> = {},
): EvidenceChallengeContext {
  return {
    acceptanceId: ACCEPTANCE_ID,
    refreshId: REFRESH_ID,
    actorId: ACTOR_ID,
    walletAddress: WALLET,
    claimStatus: "CLAIMED",
    claimDeadline: new Date("2026-09-25T12:03:00.000Z"),
    claimRevision: 5,
    refreshStatus: "CLAIMED",
    refreshExpiresAt: new Date("2026-09-25T12:15:00.000Z"),
    evidenceDeadline: new Date("2026-09-25T12:02:00.000Z"),
    proofPolicySnapshot: {
      template_key: "parking.available_spaces.v1",
      state_type: "NUMERIC",
      fresh_ttl_seconds: 300,
      aging_ratio: 0.75,
      verification_class: "FAST",
      required_witnesses: 1,
      capture: {
        media_required: true,
        location_required: true,
      },
      numeric: {
        scale: 0,
        min: 0,
        conflict_tolerance: 1,
      },
    },
    ...overrides,
  };
}

class MemoryChallengeRepository implements EvidenceChallengeRepository {
  issueInput:
    | Parameters<EvidenceChallengeRepository["issueChallenge"]>[0]
    | null = null;
  issueOverride: IssueEvidenceChallengeResult | null = null;

  constructor(public challengeContext: EvidenceChallengeContext | null) {}

  getContext(): Promise<EvidenceChallengeContext | null> {
    return Promise.resolve(
      this.challengeContext ? structuredClone(this.challengeContext) : null,
    );
  }

  issueChallenge(
    input: Parameters<EvidenceChallengeRepository["issueChallenge"]>[0],
  ): Promise<IssueEvidenceChallengeResult> {
    this.issueInput = input;
    if (this.issueOverride !== null) {
      return Promise.resolve(this.issueOverride);
    }
    if (this.challengeContext === null) {
      return Promise.resolve({ kind: "not_found" });
    }

    return Promise.resolve({
      kind: "issued",
      challenge: {
        challengeId: input.challengeId,
        refreshId: this.challengeContext.refreshId,
        acceptanceId: input.acceptanceId,
        actorId: input.actorId,
        walletAddress: this.challengeContext.walletAddress,
        nonceHash: input.nonceHash,
        status: "ISSUED",
        issuedAt: input.issuedAt,
        expiresAt: input.expiresAt,
        consumedAt: null,
        revokedAt: null,
        policyVersion: input.policyVersion,
      },
      claimStatus: "CAPTURE_ACTIVE",
      claimRevision: this.challengeContext.claimRevision + 1,
    });
  }
}

function faultCode(error: unknown): string {
  return error instanceof ApiFault ? error.code : "";
}

Deno.test("evidence challenge is claim-bound and expires at the earliest authority deadline", async () => {
  const repository = new MemoryChallengeRepository(context());
  const service = new EvidenceChallengeService(repository, () => NOW);

  const response = await service.issue(actor(), ACCEPTANCE_ID);
  if (
    response.acceptance_id !== ACCEPTANCE_ID ||
    response.refresh_id !== REFRESH_ID ||
    response.expires_at !== "2026-09-25T12:02:00.000Z" ||
    response.policy_version !== 1 ||
    response.claim_status !== "CAPTURE_ACTIVE"
  ) {
    throw new Error("challenge response lost authority-bound fields");
  }

  const capture = response.capture as Record<string, unknown>;
  if (capture.media_required !== true || capture.location_required !== true) {
    throw new Error("capture policy was not preserved");
  }
  if (typeof response.nonce !== "string" || response.nonce.length < 40) {
    throw new Error("challenge nonce was not returned");
  }
  if (
    repository.issueInput === null ||
    repository.issueInput.nonceHash.length !== 32 ||
    repository.issueInput.expiresAt.toISOString() !==
      "2026-09-25T12:02:00.000Z"
  ) {
    throw new Error("challenge persistence input is incomplete");
  }
});

Deno.test("evidence challenge hides claims owned by another actor", async () => {
  const repository = new MemoryChallengeRepository(
    context({ actorId: OTHER_ACTOR_ID }),
  );
  const service = new EvidenceChallengeService(repository, () => NOW);

  try {
    await service.issue(actor(), ACCEPTANCE_ID);
    throw new Error("cross-actor challenge issue unexpectedly succeeded");
  } catch (error) {
    if (faultCode(error) !== "CLAIM_NOT_FOUND") throw error;
  }
});

Deno.test("evidence challenge rejects closed or expired capture windows", async () => {
  for (
    const [challengeContext, expectedCode] of [
      [
        context({ claimStatus: "EVIDENCE_COMMITTED" }),
        "CLAIM_NOT_AVAILABLE",
      ],
      [
        context({ evidenceDeadline: new Date("2026-09-25T11:59:59.000Z") }),
        "CHALLENGE_EXPIRED",
      ],
    ] as const
  ) {
    const repository = new MemoryChallengeRepository(challengeContext);
    const service = new EvidenceChallengeService(repository, () => NOW);

    try {
      await service.issue(actor(), ACCEPTANCE_ID);
      throw new Error("invalid capture window unexpectedly succeeded");
    } catch (error) {
      if (faultCode(error) !== expectedCode) throw error;
    }
  }
});

Deno.test("restricted actors cannot issue an evidence challenge", async () => {
  const repository = new MemoryChallengeRepository(context());
  const service = new EvidenceChallengeService(repository, () => NOW);

  try {
    await service.issue(actor("RESTRICTED"), ACCEPTANCE_ID);
    throw new Error("restricted actor issued a challenge");
  } catch (error) {
    if (faultCode(error) !== "ACTOR_RESTRICTED") throw error;
  }
});

Deno.test("repository policy race fails closed", async () => {
  const repository = new MemoryChallengeRepository(context());
  repository.issueOverride = { kind: "policy_mismatch" };
  const service = new EvidenceChallengeService(repository, () => NOW);

  try {
    await service.issue(actor(), ACCEPTANCE_ID);
    throw new Error("policy race unexpectedly succeeded");
  } catch (error) {
    if (faultCode(error) !== "REVISION_CONFLICT") throw error;
  }
});

void CHALLENGE_ID;
