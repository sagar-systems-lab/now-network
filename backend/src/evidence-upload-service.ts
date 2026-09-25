import { ApiFault } from "./errors.ts";
import type { ActorRecord } from "./identity-repository.ts";
import type {
  EvidenceUploadContext,
  EvidenceUploadRepository,
  ReserveEvidenceUploadResult,
} from "./evidence-upload-repository.ts";
import type { EvidenceObjectStorage } from "./evidence-object-storage.ts";

const MIME_PATTERN = /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/u;

function copyToArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

function decodeBase64Url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]{43}$/u.test(value)) {
    throw new ApiFault(
      403,
      "EVIDENCE_CHALLENGE_INVALID",
      "Evidence challenge proof is invalid.",
    );
  }

  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  try {
    const binary = atob(padded);
    const bytes = Uint8Array.from(
      binary,
      (character) => character.charCodeAt(0),
    );
    if (bytes.length !== 32) throw new Error("wrong nonce length");
    return bytes;
  } catch {
    throw new ApiFault(
      403,
      "EVIDENCE_CHALLENGE_INVALID",
      "Evidence challenge proof is invalid.",
    );
  }
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

function normalizedMime(value: string): string {
  const mime = value.trim().toLowerCase();
  if (mime.length === 0 || mime.length > 128 || !MIME_PATTERN.test(mime)) {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid media_mime.");
  }
  return mime;
}

function objectKey(context: EvidenceUploadContext): string {
  return [
    "evidence",
    context.refreshId,
    context.acceptanceId,
    context.challengeId,
    crypto.randomUUID(),
  ].join("/");
}

function reserveFault(
  result: Exclude<ReserveEvidenceUploadResult, { kind: "ready" }>,
): ApiFault {
  switch (result.kind) {
    case "not_found":
    case "actor_mismatch":
      return new ApiFault(
        404,
        "EVIDENCE_CHALLENGE_INVALID",
        "Evidence challenge was not found.",
      );
    case "challenge_invalid":
    case "nonce_mismatch":
      return new ApiFault(
        403,
        "EVIDENCE_CHALLENGE_INVALID",
        "Evidence challenge proof is invalid.",
      );
    case "challenge_expired":
      return new ApiFault(410, "CHALLENGE_EXPIRED", "Evidence challenge expired.");
    case "upload_conflict":
      return new ApiFault(
        409,
        "EVIDENCE_UPLOAD_CONFLICT",
        "Evidence upload is already reserved with different metadata.",
      );
    case "claim_not_active":
      return new ApiFault(
        409,
        "CLAIM_NOT_AVAILABLE",
        "This claim cannot upload evidence.",
      );
  }
}

export class EvidenceUploadService {
  constructor(
    private readonly repository: EvidenceUploadRepository,
    private readonly storage: EvidenceObjectStorage,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async authorize(input: {
    actor: ActorRecord;
    challengeId: string;
    nonce: string;
    mediaMime: string;
  }): Promise<Record<string, unknown>> {
    assertActorActive(input.actor);
    const mediaMime = normalizedMime(input.mediaMime);
    const context = await this.repository.getContext(input.challengeId);
    if (context === null || context.actorId !== input.actor.actorId) {
      throw new ApiFault(
        404,
        "EVIDENCE_CHALLENGE_INVALID",
        "Evidence challenge was not found.",
      );
    }

    const observedAt = this.now();
    if (context.challengeExpiresAt.getTime() <= observedAt.getTime()) {
      throw new ApiFault(410, "CHALLENGE_EXPIRED", "Evidence challenge expired.");
    }

    const nonceHash = await sha256(decodeBase64Url(input.nonce));
    const reserved = await this.repository.reserveUpload({
      challengeId: input.challengeId,
      actorId: input.actor.actorId,
      nonceHash,
      objectKey: context.objectKey ?? objectKey(context),
      mediaMime,
      observedAt,
    });
    if (reserved.kind !== "ready") throw reserveFault(reserved);

    let signedUrl: string;
    try {
      signedUrl = (await this.storage.createSignedUpload(reserved.objectKey))
        .signedUrl;
    } catch {
      throw new ApiFault(
        503,
        "EVIDENCE_UPLOAD_UNAVAILABLE",
        "Evidence upload authorization is temporarily unavailable.",
        true,
        1_000,
      );
    }

    return {
      challenge_id: input.challengeId,
      object_key: reserved.objectKey,
      media_mime: reserved.mediaMime,
      upload: {
        method: "PUT",
        signed_url: signedUrl,
        content_type: reserved.mediaMime,
      },
      application_deadline: reserved.challengeExpiresAt.toISOString(),
      replayed: reserved.replayed,
    };
  }
}
