import { ApiFault } from "../src/errors.ts";
import type {
  CommitEvidenceResult,
  EvidenceCommitContext,
  EvidenceCommitRepository,
} from "../src/evidence-commit-repository.ts";
import type {
  EvidenceObjectIntegrity,
  EvidenceObjectStorage,
  SignedUploadAuthorization,
} from "../src/evidence-object-storage.ts";
import { EvidenceCommitService } from "../src/evidence-commit-service.ts";
import type { ActorRecord } from "../src/identity-repository.ts";

const ACTOR_ID = "c0000000-0000-4000-8000-000000000001";
const REFRESH_ID = "c1000000-0000-4000-8000-000000000001";
const ACCEPTANCE_ID = "c2000000-0000-4000-8000-000000000001";
const CHALLENGE_ID = "c3000000-0000-4000-8000-000000000001";
const EVIDENCE_ID = "c4000000-0000-4000-8000-000000000001";
const NOW = new Date("2026-09-25T12:00:00.000Z");
const NONCE = new Uint8Array(32).fill(7);
const MEDIA = new Uint8Array([10, 20, 30, 40, 50]);

function actor(status: ActorRecord["status"] = "ACTIVE"): ActorRecord {
  return { actorId: ACTOR_ID, status, revision: 1 };
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

async function digest(bytes: Uint8Array): Promise<Uint8Array> {
  const copy = new Uint8Array(bytes.length);
  copy.set(bytes);
  return new Uint8Array(await crypto.subtle.digest("SHA-256", copy.buffer));
}

function hex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function context(
  overrides: Partial<EvidenceCommitContext> = {},
): Promise<EvidenceCommitContext> {
  return {
    evidenceId: EVIDENCE_ID,
    challengeId: CHALLENGE_ID,
    refreshId: REFRESH_ID,
    acceptanceId: ACCEPTANCE_ID,
    actorId: ACTOR_ID,
    walletAddress: "ContributorWallet",
    nonceHash: await digest(NONCE),
    challengeStatus: "ISSUED",
    challengeIssuedAt: new Date("2026-09-25T11:59:00.000Z"),
    challengeExpiresAt: new Date("2026-09-25T12:02:00.000Z"),
    reservedObjectKey: `refreshes/${REFRESH_ID}/evidence/${EVIDENCE_ID}/original`,
    reservedMediaMime: "image/jpeg",
    claimStatus: "CAPTURE_ACTIVE",
    claimDeadline: new Date("2026-09-25T12:03:00.000Z"),
    claimRevision: 8,
    refreshStatus: "CAPTURE_IN_PROGRESS",
    requiredWitnesses: 1,
    refreshExpiresAt: new Date("2026-09-25T12:15:00.000Z"),
    evidenceDeadline: new Date("2026-09-25T12:10:00.000Z"),
    refreshRevision: 10,
    stateId: "c5000000-0000-4000-8000-000000000001",
    stateVersion: 1,
    stateType: "NUMERIC",
    intentCoreHash: new Uint8Array(32).fill(1),
    executionHash: new Uint8Array(32).fill(2),
    chainLockedRewardAtomic: 500_000n,
    chainRefreshAddress: null,
    proofPolicySnapshot: {
      template_key: "parking.available_spaces.v1",
      state_type: "NUMERIC",
      fresh_ttl_seconds: 600,
      aging_ratio: 0.7,
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

class MemoryCommitRepository implements EvidenceCommitRepository {
  calls = 0;
  lastInput: Parameters<EvidenceCommitRepository["commitEvidence"]>[0] | null = null;
  resultOverride: CommitEvidenceResult | null = null;

  constructor(public value: EvidenceCommitContext | null) {}

  getContext(): Promise<EvidenceCommitContext | null> {
    return Promise.resolve(this.value ? structuredClone(this.value) : null);
  }

  commitEvidence(
    input: Parameters<EvidenceCommitRepository["commitEvidence"]>[0],
  ): Promise<CommitEvidenceResult> {
    this.calls += 1;
    this.lastInput = input;
    if (this.resultOverride !== null) {
      return Promise.resolve(this.resultOverride);
    }
    if (this.value === null) return Promise.resolve({ kind: "not_found" });

    return Promise.resolve({
      kind: "committed",
      evidenceId: input.evidenceId,
      refreshId: this.value.refreshId,
      acceptanceId: this.value.acceptanceId,
      challengeId: this.value.challengeId,
      actorId: input.actorId,
      status: "COMMITTED",
      mediaObjectKey: input.mediaObjectKey,
      mediaSha256: input.mediaSha256,
      mediaSizeBytes: input.mediaSizeBytes,
      mediaMime: input.mediaMime,
      committedAt: input.observedAt,
      revision: 1,
      claimRevision: this.value.claimRevision + 1,
      refreshRevision: this.value.refreshRevision + 1,
      refreshStatus: "EVIDENCE_SUBMITTED",
    });
  }
}

class MemoryStorage implements EvidenceObjectStorage {
  integrity: EvidenceObjectIntegrity | null = null;
  inspectCalls = 0;

  createSignedUpload(): Promise<SignedUploadAuthorization> {
    return Promise.resolve({
      signedUrl: "https://storage.invalid/upload?token=test",
    });
  }

  inspectUploadedObject(): Promise<EvidenceObjectIntegrity> {
    this.inspectCalls += 1;
    if (this.integrity === null) {
      return Promise.reject(new Error("missing object"));
    }
    return Promise.resolve(this.integrity);
  }
}

function body(mediaSha256: string): Record<string, unknown> {
  return {
    nonce: base64Url(NONCE),
    media_sha256: mediaSha256,
    media_size_bytes: MEDIA.length,
    answer_value: 2,
    capture_started_monotonic_ms: 1_000,
    capture_completed_monotonic_ms: 1_650,
    location_samples: [{
      lat: 30.129,
      lng: 77.267,
      accuracy_m: 8.5,
      provider: "fused",
      mock_signal: false,
      captured_offset_ms: 200,
    }],
  };
}

function faultCode(error: unknown): string {
  return error instanceof ApiFault ? error.code : "";
}

Deno.test("evidence commit binds uploaded bytes and advances the authoritative state", async () => {
  const mediaHash = await digest(MEDIA);
  const repository = new MemoryCommitRepository(await context());
  const storage = new MemoryStorage();
  storage.integrity = {
    sha256: mediaHash,
    sizeBytes: MEDIA.length,
    mediaMime: "image/jpeg",
  };
  const service = new EvidenceCommitService(repository, storage, 1024, () => NOW);

  const result = await service.commit({
    actor: actor(),
    evidenceId: EVIDENCE_ID,
    body: body(hex(mediaHash)),
    idempotencyKey: "evidence-commit-0001",
  });

  if (
    result.status !== 201 ||
    result.data.evidence_id !== EVIDENCE_ID ||
    result.data.evidence_status !== "COMMITTED" ||
    result.data.claim_status !== "EVIDENCE_COMMITTED" ||
    result.data.refresh_status !== "EVIDENCE_SUBMITTED" ||
    result.data.next_step !== "VERIFICATION" ||
    repository.calls !== 1
  ) {
    throw new Error("evidence commit did not advance exactly once");
  }
});

Deno.test("evidence commit rejects media hash mismatch before database mutation", async () => {
  const mediaHash = await digest(MEDIA);
  const repository = new MemoryCommitRepository(await context());
  const storage = new MemoryStorage();
  storage.integrity = {
    sha256: await digest(new Uint8Array([99])),
    sizeBytes: MEDIA.length,
    mediaMime: "image/jpeg",
  };
  const service = new EvidenceCommitService(repository, storage, 1024, () => NOW);

  try {
    await service.commit({
      actor: actor(),
      evidenceId: EVIDENCE_ID,
      body: body(hex(mediaHash)),
      idempotencyKey: "evidence-commit-0002",
    });
    throw new Error("hash mismatch unexpectedly committed");
  } catch (error) {
    if (faultCode(error) !== "EVIDENCE_MEDIA_INVALID") throw error;
  }
  if (repository.calls !== 0) {
    throw new Error("repository was called after media integrity failure");
  }
});

Deno.test("evidence commit rejects expired challenge before storage access", async () => {
  const mediaHash = await digest(MEDIA);
  const repository = new MemoryCommitRepository(
    await context({
      challengeExpiresAt: new Date("2026-09-25T11:59:59.000Z"),
    }),
  );
  const storage = new MemoryStorage();
  storage.integrity = {
    sha256: mediaHash,
    sizeBytes: MEDIA.length,
    mediaMime: "image/jpeg",
  };
  const service = new EvidenceCommitService(repository, storage, 1024, () => NOW);

  try {
    await service.commit({
      actor: actor(),
      evidenceId: EVIDENCE_ID,
      body: body(hex(mediaHash)),
      idempotencyKey: "evidence-commit-0003",
    });
    throw new Error("expired challenge unexpectedly committed");
  } catch (error) {
    if (faultCode(error) !== "CHALLENGE_EXPIRED") throw error;
  }
  if (repository.calls !== 0) {
    throw new Error("repository was called after expiry");
  }
});

Deno.test("evidence commit maps exact media replay to a stable conflict", async () => {
  const mediaHash = await digest(MEDIA);
  const repository = new MemoryCommitRepository(await context());
  repository.resultOverride = { kind: "exact_replay" };
  const storage = new MemoryStorage();
  storage.integrity = {
    sha256: mediaHash,
    sizeBytes: MEDIA.length,
    mediaMime: "image/jpeg",
  };
  const service = new EvidenceCommitService(repository, storage, 1024, () => NOW);

  try {
    await service.commit({
      actor: actor(),
      evidenceId: EVIDENCE_ID,
      body: body(hex(mediaHash)),
      idempotencyKey: "evidence-commit-0004",
    });
    throw new Error("exact replay unexpectedly committed");
  } catch (error) {
    if (faultCode(error) !== "EVIDENCE_REPLAY") throw error;
  }
});

Deno.test("evidence commit requires location samples when policy requires location", async () => {
  const mediaHash = await digest(MEDIA);
  const repository = new MemoryCommitRepository(await context());
  const storage = new MemoryStorage();
  storage.integrity = {
    sha256: mediaHash,
    sizeBytes: MEDIA.length,
    mediaMime: "image/jpeg",
  };
  const service = new EvidenceCommitService(repository, storage, 1024, () => NOW);
  const requestBody = body(hex(mediaHash));
  requestBody.location_samples = [];

  try {
    await service.commit({
      actor: actor(),
      evidenceId: EVIDENCE_ID,
      body: requestBody,
      idempotencyKey: "evidence-commit-0005",
    });
    throw new Error("location-free evidence unexpectedly committed");
  } catch (error) {
    if (faultCode(error) !== "INVALID_REQUEST") throw error;
  }
});

Deno.test("idempotent evidence replay preserves the committed identity", async () => {
  const mediaHash = await digest(MEDIA);
  const value = await context({
    challengeStatus: "CONSUMED",
    claimStatus: "EVIDENCE_COMMITTED",
    refreshStatus: "EVIDENCE_SUBMITTED",
  });
  const repository = new MemoryCommitRepository(value);
  repository.resultOverride = {
    kind: "replayed",
    evidenceId: EVIDENCE_ID,
    refreshId: REFRESH_ID,
    acceptanceId: ACCEPTANCE_ID,
    challengeId: CHALLENGE_ID,
    actorId: ACTOR_ID,
    status: "COMMITTED",
    mediaObjectKey: value.reservedObjectKey as string,
    mediaSha256: mediaHash,
    mediaSizeBytes: MEDIA.length,
    mediaMime: "image/jpeg",
    committedAt: NOW,
    revision: 1,
    claimRevision: 9,
    refreshRevision: 11,
    refreshStatus: "EVIDENCE_SUBMITTED",
  };
  const storage = new MemoryStorage();
  storage.integrity = {
    sha256: mediaHash,
    sizeBytes: MEDIA.length,
    mediaMime: "image/jpeg",
  };
  const service = new EvidenceCommitService(repository, storage, 1024, () => NOW);

  const result = await service.commit({
    actor: actor(),
    evidenceId: EVIDENCE_ID,
    body: body(hex(mediaHash)),
    idempotencyKey: "evidence-commit-0006",
  });
  if (
    result.status !== 200 ||
    result.data.evidence_id !== EVIDENCE_ID ||
    result.data.replayed !== true ||
    storage.inspectCalls !== 0
  ) {
    throw new Error("idempotent commit replay changed evidence identity");
  }
});

Deno.test("video-required commit binds both hashes and rejects missing or mismatched clips", async () => {
  const mediaHash = await digest(MEDIA);
  const videoHash = new Uint8Array(32).fill(42);
  const value = await context();
  value.proofPolicySnapshot.capture.video_required = true;
  const repository = new MemoryCommitRepository(value);
  let storedDuration = 3000;
  const storage: EvidenceObjectStorage = {
    createSignedUpload: () => Promise.resolve({ signedUrl: "https://storage.invalid" }),
    inspectUploadedObject: (key) =>
      Promise.resolve(
        key.endsWith(".mp4")
          ? {
            sha256: videoHash,
            sizeBytes: 4096,
            mediaMime: "video/mp4",
            videoDurationMs: storedDuration,
          }
          : { sha256: mediaHash, sizeBytes: MEDIA.length, mediaMime: "image/jpeg" },
      ),
  };
  const service = new EvidenceCommitService(repository, storage, 10_485_760, () => NOW);
  const request = {
    actor: actor(),
    evidenceId: EVIDENCE_ID,
    idempotencyKey: "video-proof-123",
    body: body(hex(mediaHash)),
  };
  async function rejected(input: typeof request) {
    try {
      await service.commit(input);
    } catch (error) {
      if (faultCode(error) === "EVIDENCE_MEDIA_INVALID") return;
      throw error;
    }
    throw new Error("Invalid clip reached commit");
  }
  await rejected(request);
  const clip = {
    sha256: hex(videoHash),
    size_bytes: 4096,
    duration_ms: 3000,
    capture_started_monotonic_ms: 2000,
    capture_completed_monotonic_ms: 5100,
  };
  const complete = { ...request, body: { ...request.body, video: clip } };
  storedDuration = 4000;
  await rejected(complete);
  storedDuration = 3000;
  await rejected({
    ...complete,
    body: { ...complete.body, video: { ...clip, sha256: "00".repeat(32) } },
  });
  if (repository.calls !== 0) throw new Error("Invalid video mutated evidence");
  await service.commit(complete);
  if (
    repository.lastInput?.video?.sha256 !== clip.sha256 ||
    repository.lastInput?.video?.object_key !== value.reservedObjectKey + ".mp4"
  ) {
    throw new Error("Clip metadata was not committed with the photo");
  }
});
