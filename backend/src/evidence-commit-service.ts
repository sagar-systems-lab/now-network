import { parseEvidenceVideo, VIDEO_MAX_BYTES } from "./evidence-video.ts";
import { ApiFault } from "./errors.ts";
import type { ActorRecord } from "./identity-repository.ts";
import type {
  CommitEvidenceResult,
  EvidenceCommitContext,
  EvidenceCommitRepository,
  EvidenceLocationSample,
} from "./evidence-commit-repository.ts";
import type { EvidenceObjectStorage } from "./evidence-object-storage.ts";
import { deriveExecutionHashV1 } from "./solana-refresh-addresses.ts";
import { canonicalJson } from "../../packages/policy/src/template.ts";
import { sha256Bytes } from "../../packages/contracts/src/refresh-intent.ts";
import { transitionClaim } from "../../packages/domain/src/claim-machine.ts";
import { transitionEvidence } from "../../packages/domain/src/evidence-machine.ts";
import { transitionRefresh } from "../../packages/domain/src/refresh-machine.ts";

const IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{8,128}$/;
const HEX_32 = /^[0-9a-fA-F]{64}$/;
const encoder = new TextEncoder();

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

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function fromHex32(value: unknown, field: string): Uint8Array {
  if (typeof value !== "string" || !HEX_32.test(value)) {
    throw new ApiFault(400, "INVALID_REQUEST", `Invalid ${field}.`);
  }
  const parts = value.match(/../gu) ?? [];
  return Uint8Array.from(parts, (part) => Number.parseInt(part, 16));
}

function requiredSafeInteger(
  value: unknown,
  field: string,
  minimum = 0,
): number {
  if (!Number.isSafeInteger(value) || (value as number) < minimum) {
    throw new ApiFault(400, "INVALID_REQUEST", `Invalid ${field}.`);
  }
  return value as number;
}

function optionalString(
  value: unknown,
  field: string,
  maxLength: number,
): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value !== "string") {
    throw new ApiFault(400, "INVALID_REQUEST", `Invalid ${field}.`);
  }
  const normalized = value.trim();
  if (!normalized || normalized.length > maxLength) {
    throw new ApiFault(400, "INVALID_REQUEST", `Invalid ${field}.`);
  }
  return normalized;
}

function locationSamples(value: unknown): EvidenceLocationSample[] {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > 12) {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid location_samples.");
  }

  return value.map((sample, index) => {
    if (!sample || typeof sample !== "object" || Array.isArray(sample)) {
      throw new ApiFault(400, "INVALID_REQUEST", "Invalid location_samples.");
    }
    const row = sample as Record<string, unknown>;
    if (
      typeof row.lat !== "number" ||
      !Number.isFinite(row.lat) ||
      row.lat < -90 ||
      row.lat > 90 ||
      typeof row.lng !== "number" ||
      !Number.isFinite(row.lng) ||
      row.lng < -180 ||
      row.lng > 180
    ) {
      throw new ApiFault(400, "INVALID_REQUEST", "Invalid location_samples.");
    }

    let accuracyM: number | null = null;
    if (row.accuracy_m !== undefined && row.accuracy_m !== null) {
      if (
        typeof row.accuracy_m !== "number" ||
        !Number.isFinite(row.accuracy_m) ||
        row.accuracy_m < 0
      ) {
        throw new ApiFault(400, "INVALID_REQUEST", "Invalid location_samples.");
      }
      accuracyM = row.accuracy_m;
    }

    let mockSignal: boolean | null = null;
    if (row.mock_signal !== undefined && row.mock_signal !== null) {
      if (typeof row.mock_signal !== "boolean") {
        throw new ApiFault(400, "INVALID_REQUEST", "Invalid location_samples.");
      }
      mockSignal = row.mock_signal;
    }

    let capturedOffsetMs: number | null = null;
    if (row.captured_offset_ms !== undefined && row.captured_offset_ms !== null) {
      if (!Number.isSafeInteger(row.captured_offset_ms)) {
        throw new ApiFault(400, "INVALID_REQUEST", "Invalid location_samples.");
      }
      capturedOffsetMs = row.captured_offset_ms as number;
    }

    return {
      sampleOrder: index,
      lat: row.lat,
      lng: row.lng,
      accuracyM,
      provider: optionalString(row.provider, "location_samples.provider", 64),
      mockSignal,
      capturedOffsetMs,
    };
  });
}

function validateAnswer(context: EvidenceCommitContext, value: unknown): unknown {
  switch (context.stateType) {
    case "NUMERIC": {
      if (
        typeof value !== "number" ||
        !Number.isFinite(value) ||
        !Number.isSafeInteger(value)
      ) {
        throw new ApiFault(400, "INVALID_REQUEST", "Invalid answer_value.");
      }
      const numeric = context.proofPolicySnapshot.numeric;
      if (
        numeric === undefined ||
        value < numeric.min ||
        (numeric.max !== undefined && value > numeric.max)
      ) {
        throw new ApiFault(400, "INVALID_REQUEST", "Invalid answer_value.");
      }
      return value;
    }
    case "BINARY":
      if (
        typeof value !== "string" ||
        value.trim().length === 0 ||
        value.length > 64
      ) {
        throw new ApiFault(400, "INVALID_REQUEST", "Invalid answer_value.");
      }
      return value.trim();
    case "VISUAL":
      if (
        typeof value !== "string" || value.trim().length === 0 || value.length > 500
      ) {
        throw new ApiFault(400, "INVALID_REQUEST", "Invalid answer_value.");
      }
      return value.trim();
  }
}

function assertActorActive(actor: ActorRecord): void {
  if (actor.status === "DISABLED") {
    throw new ApiFault(403, "ACTOR_DISABLED", "This actor is disabled.");
  }
  if (actor.status === "RESTRICTED") {
    throw new ApiFault(403, "ACTOR_RESTRICTED", "This actor is restricted.");
  }
}

function requireIdempotencyKey(value: string): string {
  if (!IDEMPOTENCY_KEY.test(value)) {
    throw new ApiFault(
      400,
      "IDEMPOTENCY_KEY_REQUIRED",
      "A valid Idempotency-Key header is required.",
    );
  }
  return value;
}

function commitFault(
  result: Exclude<
    CommitEvidenceResult,
    { kind: "committed" | "replayed" }
  >,
): ApiFault {
  switch (result.kind) {
    case "not_found":
    case "actor_mismatch":
      return new ApiFault(404, "NOT_FOUND", "Evidence was not found.");
    case "idempotency_conflict":
      return new ApiFault(
        409,
        "IDEMPOTENCY_CONFLICT",
        "Idempotency key was already used with different evidence.",
      );
    case "challenge_invalid":
      return new ApiFault(
        409,
        "EVIDENCE_ALREADY_COMMITTED",
        "Evidence challenge is no longer available.",
      );
    case "expired":
      return new ApiFault(
        410,
        "CHALLENGE_EXPIRED",
        "Evidence expired before commit.",
      );
    case "claim_not_active":
    case "execution_conflict":
    case "state_conflict":
      return new ApiFault(
        409,
        "VERIFICATION_NOT_ELIGIBLE",
        "Evidence is not eligible for verification.",
      );
    case "reservation_mismatch":
      return new ApiFault(
        409,
        "EVIDENCE_UPLOAD_CONFLICT",
        "Uploaded evidence does not match its reservation.",
      );
    case "exact_replay":
      return new ApiFault(
        409,
        "EVIDENCE_REPLAY",
        "This exact evidence was already committed.",
      );
  }
}

export class EvidenceCommitService {
  constructor(
    private readonly repository: EvidenceCommitRepository,
    private readonly storage: EvidenceObjectStorage,
    private readonly maxMediaBytes: number,
    private readonly now: () => Date = () => new Date(),
  ) {
    if (!Number.isSafeInteger(maxMediaBytes) || maxMediaBytes <= 0) {
      throw new RangeError("maxMediaBytes must be a positive safe integer");
    }
  }

  async commit(input: {
    actor: ActorRecord;
    evidenceId: string;
    body: Record<string, unknown>;
    idempotencyKey: string;
  }): Promise<{ status: number; data: Record<string, unknown> }> {
    assertActorActive(input.actor);
    const idempotencyKey = requireIdempotencyKey(input.idempotencyKey);
    const context = await this.repository.getContext(input.evidenceId);
    if (context === null || context.actorId !== input.actor.actorId) {
      throw new ApiFault(404, "NOT_FOUND", "Evidence was not found.");
    }

    const observedAt = this.now();
    const committedReplay = context.challengeStatus === "CONSUMED";
    if (
      (context.challengeStatus !== "ISSUED" && !committedReplay) ||
      context.reservedObjectKey === null ||
      context.reservedMediaMime === null
    ) {
      throw new ApiFault(
        409,
        "VERIFICATION_NOT_ELIGIBLE",
        "Evidence is not eligible for commit.",
      );
    }
    if (
      !committedReplay &&
      (
        context.claimStatus !== "CAPTURE_ACTIVE" ||
        context.refreshStatus !== "CAPTURE_IN_PROGRESS" ||
        context.claimDeadline === null
      )
    ) {
      throw new ApiFault(
        409,
        "VERIFICATION_NOT_ELIGIBLE",
        "Evidence is not eligible for commit.",
      );
    }
    if (
      !committedReplay &&
      (
        context.challengeExpiresAt.getTime() <= observedAt.getTime() ||
        (context.claimDeadline?.getTime() ?? 0) <= observedAt.getTime() ||
        context.evidenceDeadline.getTime() <= observedAt.getTime() ||
        context.refreshExpiresAt.getTime() <= observedAt.getTime()
      )
    ) {
      throw new ApiFault(
        410,
        "CHALLENGE_EXPIRED",
        "Evidence expired before commit.",
      );
    }

    const nonceValue = input.body.nonce;
    if (typeof nonceValue !== "string") {
      throw new ApiFault(400, "INVALID_REQUEST", "Invalid nonce.");
    }
    const nonceHash = await sha256(decodeBase64Url(nonceValue));
    if (!bytesEqual(nonceHash, context.nonceHash)) {
      throw new ApiFault(
        403,
        "EVIDENCE_CHALLENGE_INVALID",
        "Evidence challenge proof is invalid.",
      );
    }

    const mediaSha256 = fromHex32(input.body.media_sha256, "media_sha256");
    const mediaSizeBytes = requiredSafeInteger(
      input.body.media_size_bytes,
      "media_size_bytes",
      1,
    );
    if (mediaSizeBytes > this.maxMediaBytes) {
      throw new ApiFault(
        400,
        "EVIDENCE_MEDIA_INVALID",
        "Evidence media exceeds the configured size limit.",
      );
    }

    const captureStartedMonotonicMs = requiredSafeInteger(
      input.body.capture_started_monotonic_ms,
      "capture_started_monotonic_ms",
    );
    const captureCompletedMonotonicMs = requiredSafeInteger(
      input.body.capture_completed_monotonic_ms,
      "capture_completed_monotonic_ms",
    );
    if (captureCompletedMonotonicMs < captureStartedMonotonicMs) {
      throw new ApiFault(400, "INVALID_REQUEST", "Invalid capture timing.");
    }

    const video = parseEvidenceVideo(
      input.body.video,
      context.reservedObjectKey,
      captureCompletedMonotonicMs,
    );
    if (context.proofPolicySnapshot.capture.video_required && !video) {
      throw new ApiFault(
        400,
        "EVIDENCE_MEDIA_INVALID",
        "A fresh 3–15 second video is required after the photo.",
      );
    }
    const answerValue = validateAnswer(context, input.body.answer_value);
    const samples = locationSamples(input.body.location_samples);
    if (
      context.proofPolicySnapshot.capture.location_required &&
      samples.length === 0
    ) {
      throw new ApiFault(
        400,
        "INVALID_REQUEST",
        "Location samples are required for this evidence.",
      );
    }

    const requestHash = await sha256Bytes(
      encoder.encode(
        canonicalJson({
          evidence_id: input.evidenceId,
          media_sha256: [...mediaSha256],
          media_size_bytes: mediaSizeBytes,
          answer_type: context.stateType,
          answer_value: answerValue,
          capture_started_monotonic_ms: captureStartedMonotonicMs,
          capture_completed_monotonic_ms: captureCompletedMonotonicMs,
          location_samples: samples,
          ...(video ? { video } : {}),
        }),
      ),
    );

    let executionHash = context.executionHash;
    if (executionHash === null) {
      if (
        context.chainLockedRewardAtomic === null ||
        context.chainRefreshAddress === null
      ) {
        throw new ApiFault(
          409,
          "VERIFICATION_NOT_ELIGIBLE",
          "Locked execution identity is unavailable.",
        );
      }
      executionHash = await deriveExecutionHashV1({
        intentCoreHash: context.intentCoreHash,
        lockedRewardAtomic: context.chainLockedRewardAtomic,
        refreshAddress: context.chainRefreshAddress,
      });
    }

    if (!committedReplay) {
      let objectIntegrity;
      try {
        objectIntegrity = await this.storage.inspectUploadedObject(
          context.reservedObjectKey,
          this.maxMediaBytes,
        );
      } catch {
        throw new ApiFault(
          503,
          "EVIDENCE_UPLOAD_UNAVAILABLE",
          "Uploaded evidence could not be inspected yet.",
          true,
          1_000,
        );
      }

      if (
        objectIntegrity.sizeBytes !== mediaSizeBytes ||
        objectIntegrity.mediaMime !== context.reservedMediaMime ||
        !bytesEqual(objectIntegrity.sha256, mediaSha256)
      ) {
        throw new ApiFault(
          409,
          "EVIDENCE_MEDIA_INVALID",
          "Uploaded evidence does not match the committed media metadata.",
        );
      }

      if (video) {
        let clip;
        try {
          clip = await this.storage.inspectUploadedObject(video.object_key, VIDEO_MAX_BYTES);
        } catch (error) {
          if (error instanceof ApiFault) throw error;
          throw new ApiFault(
            503,
            "EVIDENCE_UPLOAD_UNAVAILABLE",
            "Video upload is not ready yet.",
            true,
            1000,
          );
        }
        if (
          clip.mediaMime !== "video/mp4" || clip.sizeBytes !== video.size_bytes ||
          clip.videoDurationMs === undefined ||
          Math.abs(clip.videoDurationMs - video.duration_ms) > 100 ||
          !bytesEqual(clip.sha256, fromHex32(video.sha256, "video.sha256"))
        ) {
          throw new ApiFault(
            409,
            "EVIDENCE_MEDIA_INVALID",
            "Video bytes or duration do not match this proof.",
          );
        }
      }
      transitionEvidence("UPLOADED", "COMMIT");
      transitionClaim("CAPTURE_ACTIVE", "EVIDENCE_COMMITTED", {
        evidenceCommittedBeforeDeadline: true,
      });
    }

    const result = await this.repository.commitEvidence({
      evidenceId: input.evidenceId,
      actorId: input.actor.actorId,
      idempotencyKey,
      requestHash,
      idempotencyExpiresAt: new Date(observedAt.getTime() + 24 * 60 * 60 * 1_000),
      nonceHash,
      executionHash,
      answerType: context.stateType,
      answerValue,
      captureStartedMonotonicMs,
      captureCompletedMonotonicMs,
      mediaObjectKey: context.reservedObjectKey,
      mediaSha256,
      mediaSizeBytes,
      mediaMime: context.reservedMediaMime,
      locationSamples: samples,
      video,
      observedAt,
    });
    if (result.kind !== "committed" && result.kind !== "replayed") {
      throw commitFault(result);
    }

    transitionEvidence(
      "COMMITTING",
      result.kind === "replayed" ? "IDEMPOTENT_DUPLICATE" : "COMMIT_OK",
      {
        commitAccepted: result.kind === "committed",
        commitAlreadyAccepted: result.kind === "replayed",
      },
    );
    if (
      result.kind === "committed" &&
      result.refreshStatus === "EVIDENCE_SUBMITTED"
    ) {
      transitionRefresh("CAPTURE_IN_PROGRESS", "EVIDENCE_COMMITTED", {
        evidenceCommitted: true,
        refreshExpired: false,
      });
    }

    return {
      status: result.kind === "committed" ? 201 : 200,
      data: {
        evidence_id: result.evidenceId,
        refresh_id: result.refreshId,
        acceptance_id: result.acceptanceId,
        challenge_id: result.challengeId,
        evidence_status: result.status,
        claim_status: "EVIDENCE_COMMITTED",
        refresh_status: result.refreshStatus,
        committed_at: result.committedAt.toISOString(),
        media: {
          object_key: result.mediaObjectKey,
          sha256: [...result.mediaSha256]
            .map((byte) => byte.toString(16).padStart(2, "0"))
            .join(""),
          size_bytes: result.mediaSizeBytes,
          mime: result.mediaMime,
        },
        replayed: result.kind === "replayed",
        next_step: result.refreshStatus === "EVIDENCE_SUBMITTED"
          ? "VERIFICATION"
          : "AWAIT_MORE_EVIDENCE",
      },
    };
  }
}
