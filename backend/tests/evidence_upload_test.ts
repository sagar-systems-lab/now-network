import { ApiFault } from "../src/errors.ts";
import type { ActorRecord } from "../src/identity-repository.ts";
import type {
  EvidenceUploadContext,
  EvidenceUploadRepository,
  ReserveEvidenceUploadResult,
} from "../src/evidence-upload-repository.ts";
import type {
  EvidenceObjectStorage,
  SignedUploadAuthorization,
} from "../src/evidence-object-storage.ts";
import { EvidenceUploadService } from "../src/evidence-upload-service.ts";

const ACTOR_ID = "b0000000-0000-4000-8000-000000000001";
const OTHER_ACTOR_ID = "b0000000-0000-4000-8000-000000000002";
const REFRESH_ID = "b1000000-0000-4000-8000-000000000001";
const ACCEPTANCE_ID = "b2000000-0000-4000-8000-000000000001";
const CHALLENGE_ID = "b3000000-0000-4000-8000-000000000001";
const NOW = new Date("2026-09-25T12:00:00.000Z");
const NONCE_BYTES = new Uint8Array(32).fill(7);

function base64Url(bytes: Uint8Array): string {
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

function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function actor(status: ActorRecord["status"] = "ACTIVE"): ActorRecord {
  return { actorId: ACTOR_ID, status, revision: 1 };
}

async function context(
  overrides: Partial<EvidenceUploadContext> = {},
): Promise<EvidenceUploadContext> {
  return {
    challengeId: CHALLENGE_ID,
    refreshId: REFRESH_ID,
    acceptanceId: ACCEPTANCE_ID,
    actorId: ACTOR_ID,
    nonceHash: await sha256(NONCE_BYTES),
    challengeStatus: "ISSUED",
    challengeExpiresAt: new Date("2026-09-25T12:02:00.000Z"),
    claimStatus: "CAPTURE_ACTIVE",
    refreshStatus: "CAPTURE_IN_PROGRESS",
    evidenceId: null,
    objectKey: null,
    mediaMime: null,
    ...overrides,
  };
}

class MemoryUploadRepository implements EvidenceUploadRepository {
  constructor(public value: EvidenceUploadContext | null) {}

  getContext(): Promise<EvidenceUploadContext | null> {
    return Promise.resolve(this.value ? structuredClone(this.value) : null);
  }

  reserveUpload(
    input: Parameters<EvidenceUploadRepository["reserveUpload"]>[0],
  ): Promise<ReserveEvidenceUploadResult> {
    if (this.value === null) {
      return Promise.resolve({ kind: "not_found" });
    }
    if (this.value.actorId !== input.actorId) {
      return Promise.resolve({ kind: "actor_mismatch" });
    }
    if (this.value.challengeStatus !== "ISSUED") {
      return Promise.resolve({ kind: "challenge_invalid" });
    }
    if (this.value.challengeExpiresAt.getTime() <= input.observedAt.getTime()) {
      return Promise.resolve({ kind: "challenge_expired" });
    }
    if (!equalBytes(this.value.nonceHash, input.nonceHash)) {
      return Promise.resolve({ kind: "nonce_mismatch" });
    }
    if (
      this.value.claimStatus !== "CAPTURE_ACTIVE" ||
      (
        this.value.refreshStatus !== "CAPTURE_IN_PROGRESS" &&
        this.value.refreshStatus !== "CLAIMED"
      )
    ) {
      return Promise.resolve({ kind: "claim_not_active" });
    }
    if (this.value.objectKey !== null) {
      if (
        this.value.evidenceId === null ||
        this.value.evidenceId !== input.evidenceId ||
        this.value.mediaMime !== input.mediaMime
      ) {
        return Promise.resolve({ kind: "upload_conflict" });
      }
      return Promise.resolve({
        kind: "ready",
        evidenceId: this.value.evidenceId,
        objectKey: this.value.objectKey,
        mediaMime: this.value.mediaMime,
        challengeExpiresAt: this.value.challengeExpiresAt,
        replayed: true,
      });
    }

    this.value.evidenceId = input.evidenceId;
    this.value.objectKey = input.objectKey;
    this.value.mediaMime = input.mediaMime;
    return Promise.resolve({
      kind: "ready",
      evidenceId: input.evidenceId,
      objectKey: input.objectKey,
      mediaMime: input.mediaMime,
      challengeExpiresAt: this.value.challengeExpiresAt,
      replayed: false,
    });
  }
}

class MemoryStorage implements EvidenceObjectStorage {
  calls: string[] = [];
  fail = false;

  createSignedUpload(objectKey: string): Promise<SignedUploadAuthorization> {
    this.calls.push(objectKey);
    if (this.fail) return Promise.reject(new Error("storage unavailable"));
    return Promise.resolve({
      signedUrl: `https://storage.invalid/object/upload/sign/private/${objectKey}?token=test`,
    });
  }

  inspectUploadedObject(): Promise<never> {
    return Promise.reject(new Error("not used by upload authorization tests"));
  }
}

function faultCode(error: unknown): string {
  return error instanceof ApiFault ? error.code : "";
}

Deno.test("signed upload is challenge-bound and uses a system-generated immutable path", async () => {
  const repository = new MemoryUploadRepository(await context());
  const storage = new MemoryStorage();
  const service = new EvidenceUploadService(repository, storage, () => NOW);

  const result = await service.authorize({
    actor: actor(),
    challengeId: CHALLENGE_ID,
    nonce: base64Url(NONCE_BYTES),
    mediaMime: "image/jpeg",
  });

  if (
    typeof result.evidence_id !== "string" ||
    typeof result.object_key !== "string" ||
    result.object_key !==
      `refreshes/${REFRESH_ID}/evidence/${result.evidence_id}/original` ||
    result.media_mime !== "image/jpeg" ||
    result.application_deadline !== "2026-09-25T12:02:00.000Z" ||
    result.replayed !== false
  ) {
    throw new Error("signed upload authorization lost its authority binding");
  }

  const upload = result.upload as Record<string, unknown>;
  if (
    upload.method !== "PUT" ||
    upload.content_type !== "image/jpeg" ||
    typeof upload.signed_url !== "string"
  ) {
    throw new Error("signed upload response is incomplete");
  }
});

Deno.test("signed upload retry reuses the same object key", async () => {
  const repository = new MemoryUploadRepository(await context());
  const storage = new MemoryStorage();
  const service = new EvidenceUploadService(repository, storage, () => NOW);

  const first = await service.authorize({
    actor: actor(),
    challengeId: CHALLENGE_ID,
    nonce: base64Url(NONCE_BYTES),
    mediaMime: "image/jpeg",
  });
  const second = await service.authorize({
    actor: actor(),
    challengeId: CHALLENGE_ID,
    nonce: base64Url(NONCE_BYTES),
    mediaMime: "image/jpeg",
  });

  if (
    first.evidence_id !== second.evidence_id ||
    first.object_key !== second.object_key ||
    second.replayed !== true ||
    storage.calls[0] !== storage.calls[1]
  ) {
    throw new Error("signed upload retry changed the reserved object identity");
  }
});

Deno.test("signed upload rejects nonce replay with the wrong challenge proof", async () => {
  const repository = new MemoryUploadRepository(await context());
  const storage = new MemoryStorage();
  const service = new EvidenceUploadService(repository, storage, () => NOW);

  try {
    await service.authorize({
      actor: actor(),
      challengeId: CHALLENGE_ID,
      nonce: base64Url(new Uint8Array(32).fill(8)),
      mediaMime: "image/jpeg",
    });
    throw new Error("wrong challenge nonce unexpectedly authorized upload");
  } catch (error) {
    if (faultCode(error) !== "EVIDENCE_CHALLENGE_INVALID") throw error;
  }

  if (storage.calls.length !== 0) {
    throw new Error("storage was contacted after nonce rejection");
  }
});

Deno.test("signed upload rejects expired, consumed, and cross-actor challenges", async () => {
  const cases: Array<[EvidenceUploadContext, string]> = [
    [
      await context({
        challengeExpiresAt: new Date("2026-09-25T11:59:59.000Z"),
      }),
      "CHALLENGE_EXPIRED",
    ],
    [
      await context({ challengeStatus: "CONSUMED" }),
      "EVIDENCE_CHALLENGE_INVALID",
    ],
    [
      await context({ actorId: OTHER_ACTOR_ID }),
      "EVIDENCE_CHALLENGE_INVALID",
    ],
  ];

  for (const [value, expected] of cases) {
    const storage = new MemoryStorage();
    const service = new EvidenceUploadService(
      new MemoryUploadRepository(value),
      storage,
      () => NOW,
    );
    try {
      await service.authorize({
        actor: actor(),
        challengeId: CHALLENGE_ID,
        nonce: base64Url(NONCE_BYTES),
        mediaMime: "image/jpeg",
      });
      throw new Error("invalid challenge unexpectedly authorized upload");
    } catch (error) {
      if (faultCode(error) !== expected) throw error;
    }
    if (storage.calls.length !== 0) {
      throw new Error("storage was contacted for an invalid challenge");
    }
  }
});

Deno.test("signed upload rejects metadata drift after reservation", async () => {
  const repository = new MemoryUploadRepository(
    await context({
      evidenceId: "b4000000-0000-4000-8000-000000000001",
      objectKey:
        `refreshes/${REFRESH_ID}/evidence/b4000000-0000-4000-8000-000000000001/original`,
      mediaMime: "image/jpeg",
    }),
  );
  const storage = new MemoryStorage();
  const service = new EvidenceUploadService(repository, storage, () => NOW);

  try {
    await service.authorize({
      actor: actor(),
      challengeId: CHALLENGE_ID,
      nonce: base64Url(NONCE_BYTES),
      mediaMime: "image/png",
    });
    throw new Error("reserved upload metadata drift unexpectedly succeeded");
  } catch (error) {
    if (faultCode(error) !== "EVIDENCE_UPLOAD_CONFLICT") throw error;
  }
});

Deno.test("signed upload storage failure is retryable without changing object identity", async () => {
  const repository = new MemoryUploadRepository(await context());
  const storage = new MemoryStorage();
  storage.fail = true;
  const service = new EvidenceUploadService(repository, storage, () => NOW);

  try {
    await service.authorize({
      actor: actor(),
      challengeId: CHALLENGE_ID,
      nonce: base64Url(NONCE_BYTES),
      mediaMime: "image/jpeg",
    });
    throw new Error("storage failure unexpectedly succeeded");
  } catch (error) {
    if (
      !(error instanceof ApiFault) ||
      error.code !== "EVIDENCE_UPLOAD_UNAVAILABLE"
    ) {
      throw error;
    }
    if (error.safeToRetry !== true) {
      throw new Error("storage failure must be retryable");
    }
  }

  const reserved = repository.value?.objectKey;
  if (!reserved) throw new Error("upload reservation was not preserved");

  storage.fail = false;
  const retried = await service.authorize({
    actor: actor(),
    challengeId: CHALLENGE_ID,
    nonce: base64Url(NONCE_BYTES),
    mediaMime: "image/jpeg",
  });
  if (retried.object_key !== reserved || retried.replayed !== true) {
    throw new Error("retry did not reuse the reserved object identity");
  }
});
