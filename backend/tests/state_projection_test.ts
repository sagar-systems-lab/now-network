import { ApiFault } from "../src/errors.ts";
import type {
  StateProjectionContext,
  StateProjectionRepository,
  StateProjectionResult,
} from "../src/state-projection-repository.ts";
import { StateProjectionService } from "../src/state-projection-service.ts";

const REFRESH_ID = "e1000000-0000-4000-8000-000000000001";
const VERIFICATION_ID = "e2000000-0000-4000-8000-000000000001";
const STATE_ID = "e3000000-0000-4000-8000-000000000001";
const EVIDENCE_ID = "e4000000-0000-4000-8000-000000000001";
const COMPLETED_AT = new Date("2026-09-25T12:00:00.000Z");

function context(
  overrides: Partial<StateProjectionContext> = {},
): StateProjectionContext {
  return {
    refreshId: REFRESH_ID,
    refreshStatus: "VERIFIED",
    refreshExpiresAt: new Date("2026-09-25T12:10:00.000Z"),
    stateId: STATE_ID,
    stateVersion: 1,
    stateType: "NUMERIC",
    unitCode: "spaces",
    verificationClass: "FAST",
    refreshExecutionHash: new Uint8Array(32).fill(2),
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
    verificationResultId: VERIFICATION_ID,
    verificationStatus: "VERIFIED",
    verificationExecutionHash: new Uint8Array(32).fill(2),
    finalAnswer: 2,
    matchingEvidenceIds: [EVIDENCE_ID],
    completedAt: COMPLETED_AT,
    observationEarliest: new Date("2026-09-25T11:59:00.000Z"),
    observationLatest: new Date("2026-09-25T11:59:30.000Z"),
    ...overrides,
  };
}

class MemoryProjectionRepository implements StateProjectionRepository {
  calls = 0;
  lastInput: Parameters<StateProjectionRepository["project"]>[0] | null = null;
  resultKind: "projected" | "replayed" | "superseded" = "projected";

  constructor(public value: StateProjectionContext | null) {}

  getContext(): Promise<StateProjectionContext | null> {
    return Promise.resolve(this.value ? structuredClone(this.value) : null);
  }

  project(
    input: Parameters<StateProjectionRepository["project"]>[0],
  ): Promise<StateProjectionResult> {
    this.calls += 1;
    this.lastInput = structuredClone(input);
    return Promise.resolve({
      kind: this.resultKind,
      stateId: input.stateId,
      refreshId: input.refreshId,
      verificationResultId: input.verificationResultId,
      historyId: "e5000000-0000-4000-8000-000000000001",
      stateRevision: 4,
      observedAt: input.observedAt,
      agingAt: input.agingAt,
      freshUntil: input.freshUntil,
      currentValue: input.currentValue,
    });
  }
}

function faultCode(error: unknown): string {
  return error instanceof ApiFault ? error.code : "";
}

Deno.test("numeric projection canonicalizes value and resets freshness from verification time", async () => {
  const repository = new MemoryProjectionRepository(context());
  const service = new StateProjectionService(repository, () => COMPLETED_AT);

  const result = await service.project(REFRESH_ID, VERIFICATION_ID);
  if (
    result.projected !== true ||
    result.freshness !== "LIVE" ||
    result.observed_at !== "2026-09-25T12:00:00.000Z" ||
    result.aging_at !== "2026-09-25T12:07:00.000Z" ||
    result.fresh_until !== "2026-09-25T12:10:00.000Z"
  ) {
    throw new Error("projection freshness window is incorrect");
  }

  const value = repository.lastInput?.currentValue as
    | Record<string, unknown>
    | undefined;
  if (
    value?.kind !== "numeric" ||
    value.scaled_value !== "2" ||
    value.scale !== 0 ||
    value.unit !== "spaces" ||
    repository.lastInput?.currentValueDigest.length !== 32
  ) {
    throw new Error("numeric live-state value was not canonicalized");
  }
});

Deno.test("visual projection binds the canonical live value to verified evidence identity", async () => {
  const repository = new MemoryProjectionRepository(
    context({
      stateType: "VISUAL",
      unitCode: null,
      finalAnswer: { condition: "clear" },
      proofPolicySnapshot: {
        template_key: "visual.current_condition.v1",
        state_type: "VISUAL",
        fresh_ttl_seconds: 300,
        aging_ratio: 0.7,
        verification_class: "FAST",
        required_witnesses: 1,
        capture: {
          media_required: true,
          location_required: true,
        },
      },
    }),
  );
  const service = new StateProjectionService(repository, () => COMPLETED_AT);

  await service.project(REFRESH_ID, VERIFICATION_ID);
  const value = repository.lastInput?.currentValue as
    | Record<string, unknown>
    | undefined;
  if (value?.kind !== "visual" || value.evidence_id !== EVIDENCE_ID) {
    throw new Error("visual projection was not evidence-bound");
  }
});

Deno.test("projection replay preserves the same history identity", async () => {
  const repository = new MemoryProjectionRepository(context());
  repository.resultKind = "replayed";
  const service = new StateProjectionService(repository, () => COMPLETED_AT);

  const result = await service.project(REFRESH_ID, VERIFICATION_ID);
  if (
    result.replayed !== true ||
    result.projected !== false ||
    result.history_id !== "e5000000-0000-4000-8000-000000000001"
  ) {
    throw new Error("projection replay was not idempotent");
  }
});

Deno.test("superseded projection never reports a new LIVE mutation", async () => {
  const repository = new MemoryProjectionRepository(context());
  repository.resultKind = "superseded";
  const service = new StateProjectionService(repository, () => COMPLETED_AT);

  const result = await service.project(REFRESH_ID, VERIFICATION_ID);
  if (
    result.superseded !== true ||
    result.projected !== false ||
    result.freshness !== null
  ) {
    throw new Error("superseded projection was reported as authoritative");
  }
});

Deno.test("projection recovery after refresh expiry preserves verified history and derives staleness", async () => {
  const repository = new MemoryProjectionRepository(context());
  const service = new StateProjectionService(
    repository,
    () => new Date("2026-09-25T12:11:00.000Z"),
  );

  const result = await service.project(REFRESH_ID, VERIFICATION_ID);
  if (
    result.projected !== true ||
    result.freshness !== "STALE" ||
    repository.calls !== 1
  ) {
    throw new Error("delayed projection recovery did not preserve verified state");
  }
});

Deno.test("projection rejects verification completed after refresh expiry", async () => {
  const repository = new MemoryProjectionRepository(
    context({
      refreshExpiresAt: new Date("2026-09-25T11:59:59.000Z"),
    }),
  );
  const service = new StateProjectionService(repository, () => COMPLETED_AT);

  try {
    await service.project(REFRESH_ID, VERIFICATION_ID);
    throw new Error("expired verification unexpectedly projected");
  } catch (error) {
    if (faultCode(error) !== "REFRESH_EXPIRED") throw error;
  }
  if (repository.calls !== 0) {
    throw new Error("projection repository mutated after expiry");
  }
});
