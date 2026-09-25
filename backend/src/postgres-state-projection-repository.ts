import postgres from "npm:postgres@3.4.7";
import type {
  StateProjectionContext,
  StateProjectionRepository,
  StateProjectionResult,
} from "./state-projection-repository.ts";

type DateLike = Date | string;

type ProjectionContextRow = {
  refresh_id: string;
  refresh_status: StateProjectionContext["refreshStatus"];
  refresh_expires_at: DateLike;
  state_id: string;
  state_version: number | string;
  state_type: StateProjectionContext["stateType"];
  unit_code: string | null;
  verification_class: StateProjectionContext["verificationClass"];
  refresh_execution_hash: Uint8Array | null;
  proof_policy_snapshot: StateProjectionContext["proofPolicySnapshot"];
  verification_result_id: string;
  verification_execution_hash: Uint8Array;
  final_answer: unknown;
  matching_evidence_ids: string[];
  completed_at: DateLike;
  observation_earliest: DateLike;
  observation_latest: DateLike;
};

type LiveStateRow = {
  latest_verification_result_id: string | null;
  current_value: unknown | null;
  observed_at: DateLike | null;
  aging_at: DateLike | null;
  fresh_until: DateLike | null;
  revision: number | string;
};

type HistoryRow = {
  history_id: string;
  value: unknown;
  observed_at: DateLike;
  aging_at: DateLike;
  fresh_until: DateLike;
};

function date(value: DateLike): Date {
  return value instanceof Date ? value : new Date(value);
}

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

const CONTEXT_SQL = String.raw`
  select
    rr.refresh_id,
    rr.status as refresh_status,
    rr.refresh_expires_at,
    rr.state_id,
    rr.state_version,
    sd.state_type,
    sd.unit_code,
    rr.verification_class,
    rr.execution_hash as refresh_execution_hash,
    rr.proof_policy_snapshot,
    vr.verification_result_id,
    vr.execution_hash as verification_execution_hash,
    vr.final_answer,
    coalesce(
      array(
        select jsonb_array_elements_text(
          coalesce(vr.verification_trace -> 'matching_evidence_ids', '[]'::jsonb)
        )
      ),
      array[]::text[]
    ) as matching_evidence_ids,
    vr.completed_at,
    evidence_window.observation_earliest,
    evidence_window.observation_latest
  from app.refresh_requests rr
  join app.state_definitions sd
    on sd.state_id = rr.state_id
   and sd.version = rr.state_version
  join app.verification_results vr
    on vr.refresh_id = rr.refresh_id
  join lateral (
    select
      min(ep.server_observation_earliest) as observation_earliest,
      max(ep.server_observation_latest) as observation_latest
    from app.evidence_packets ep
    where ep.evidence_id = any(vr.evidence_ids)
  ) evidence_window on true
  where rr.refresh_id = $1::uuid
    and vr.verification_result_id = $2::uuid
    and vr.status = 'VERIFIED'
    and vr.completed_at is not null
    and evidence_window.observation_earliest is not null
    and evidence_window.observation_latest is not null
`;

function contextFromRow(row: ProjectionContextRow): StateProjectionContext {
  return {
    refreshId: row.refresh_id,
    refreshStatus: row.refresh_status,
    refreshExpiresAt: date(row.refresh_expires_at),
    stateId: row.state_id,
    stateVersion: Number(row.state_version),
    stateType: row.state_type,
    unitCode: row.unit_code,
    verificationClass: row.verification_class,
    refreshExecutionHash: row.refresh_execution_hash === null
      ? null
      : new Uint8Array(row.refresh_execution_hash),
    proofPolicySnapshot: row.proof_policy_snapshot,
    verificationResultId: row.verification_result_id,
    verificationStatus: "VERIFIED",
    verificationExecutionHash: new Uint8Array(row.verification_execution_hash),
    finalAnswer: row.final_answer,
    matchingEvidenceIds: row.matching_evidence_ids,
    completedAt: date(row.completed_at),
    observationEarliest: date(row.observation_earliest),
    observationLatest: date(row.observation_latest),
  };
}

function historyResult(
  kind: "replayed" | "superseded",
  input: Parameters<StateProjectionRepository["project"]>[0],
  history: HistoryRow,
  stateRevision: number,
  currentValue: unknown,
): StateProjectionResult {
  return {
    kind,
    stateId: input.stateId,
    refreshId: input.refreshId,
    verificationResultId: input.verificationResultId,
    historyId: history.history_id,
    stateRevision,
    observedAt: date(history.observed_at),
    agingAt: date(history.aging_at),
    freshUntil: date(history.fresh_until),
    currentValue,
  };
}

export class PostgresStateProjectionRepository
  implements StateProjectionRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async getContext(
    refreshId: string,
    verificationResultId: string,
  ): Promise<StateProjectionContext | null> {
    const rows = await this.sql.unsafe(CONTEXT_SQL, [
      refreshId,
      verificationResultId,
    ]);
    return rows[0]
      ? contextFromRow(rows[0] as unknown as ProjectionContextRow)
      : null;
  }

  async project(
    input: Parameters<StateProjectionRepository["project"]>[0],
  ): Promise<StateProjectionResult> {
    return await this.sql.begin(async (tx) => {
      await tx`
        select pg_advisory_xact_lock(
          hashtextextended(${input.stateId}, 61)
        )
      `;

      const contextRows = await tx.unsafe(
        `${CONTEXT_SQL} for update of rr, vr`,
        [input.refreshId, input.verificationResultId],
      );
      if (!contextRows[0]) return { kind: "not_found" } as const;
      const context = contextFromRow(
        contextRows[0] as unknown as ProjectionContextRow,
      );

      if (
        context.stateId !== input.stateId ||
        context.stateVersion !== input.stateVersion ||
        context.verificationStatus !== "VERIFIED"
      ) {
        return { kind: "not_verified" } as const;
      }
      if (
        context.refreshExecutionHash === null ||
        context.refreshExecutionHash.length !== 32 ||
        input.executionHash.length !== 32 ||
        !bytesEqual(context.refreshExecutionHash, input.executionHash) ||
        !bytesEqual(context.verificationExecutionHash, input.executionHash)
      ) {
        return { kind: "authority_conflict" } as const;
      }
      if (
        context.refreshStatus === "VERIFIED" &&
        context.refreshExpiresAt.getTime() <= input.observedAt.getTime()
      ) {
        return { kind: "expired" } as const;
      }
      if (
        ![
          "VERIFIED",
          "SETTLEMENT_PENDING",
          "SETTLEMENT_VERIFYING",
          "COMPLETED",
        ].includes(context.refreshStatus)
      ) {
        return { kind: "not_verified" } as const;
      }
      if (
        context.completedAt.getTime() !== input.observedAt.getTime() ||
        context.observationEarliest.getTime() !==
          input.observationEarliest.getTime() ||
        context.observationLatest.getTime() !==
          input.observationLatest.getTime()
      ) {
        return { kind: "authority_conflict" } as const;
      }

      const historyRows = await tx`
        select history_id, value, observed_at, aging_at, fresh_until
        from app.state_history
        where verification_result_id = ${input.verificationResultId}::uuid
        limit 1
      `;
      const liveRows = await tx`
        select
          latest_verification_result_id,
          current_value,
          observed_at,
          aging_at,
          fresh_until,
          revision
        from app.live_states
        where state_id = ${input.stateId}::uuid
        for update
      `;
      const live = liveRows[0]
        ? liveRows[0] as unknown as LiveStateRow
        : null;

      if (historyRows[0]) {
        const history = historyRows[0] as unknown as HistoryRow;
        const replayed = live?.latest_verification_result_id ===
            input.verificationResultId
          ? "replayed"
          : "superseded";
        return historyResult(
          replayed,
          input,
          history,
          Number(live?.revision ?? 1),
          live?.current_value ?? history.value,
        );
      }

      const historyId = crypto.randomUUID();
      await tx`
        insert into app.state_history(
          history_id,
          state_id,
          state_version,
          refresh_id,
          verification_result_id,
          value,
          value_digest,
          observed_at,
          aging_at,
          fresh_until,
          verification_class
        ) values (
          ${historyId}::uuid,
          ${input.stateId}::uuid,
          ${input.stateVersion},
          ${input.refreshId}::uuid,
          ${input.verificationResultId}::uuid,
          ${JSON.stringify(input.currentValue)}::jsonb,
          ${input.currentValueDigest},
          ${input.observedAt},
          ${input.agingAt},
          ${input.freshUntil},
          ${input.verificationClass}
        )
      `;

      if (
        live !== null &&
        live.observed_at !== null &&
        date(live.observed_at).getTime() >= input.observedAt.getTime()
      ) {
        await tx`
          insert into app.domain_events(
            event_id,
            entity_type,
            entity_id,
            event_type,
            operation_id,
            entity_revision,
            payload,
            occurred_at
          ) values (
            ${crypto.randomUUID()}::uuid,
            'state_history',
            ${historyId}::uuid,
            'STATE_HISTORY_RECORDED',
            ${input.verificationResultId}::uuid,
            1,
            ${JSON.stringify({
              state_id: input.stateId,
              refresh_id: input.refreshId,
              verification_result_id: input.verificationResultId,
              superseded: true,
            })}::jsonb,
            ${input.observedAt}
          )
        `;
        return {
          kind: "superseded",
          stateId: input.stateId,
          refreshId: input.refreshId,
          verificationResultId: input.verificationResultId,
          historyId,
          stateRevision: Number(live.revision),
          observedAt: input.observedAt,
          agingAt: input.agingAt,
          freshUntil: input.freshUntil,
          currentValue: live.current_value,
        } as const;
      }

      let stateRevision: number;
      if (live === null) {
        await tx`
          insert into app.live_states(
            state_id,
            state_version,
            current_value,
            current_value_digest,
            observed_at,
            observation_earliest,
            observation_latest,
            aging_at,
            fresh_until,
            verification_class,
            latest_verification_result_id,
            latest_refresh_id,
            conflict_active,
            revision,
            updated_at
          ) values (
            ${input.stateId}::uuid,
            ${input.stateVersion},
            ${JSON.stringify(input.currentValue)}::jsonb,
            ${input.currentValueDigest},
            ${input.observedAt},
            ${input.observationEarliest},
            ${input.observationLatest},
            ${input.agingAt},
            ${input.freshUntil},
            ${input.verificationClass},
            ${input.verificationResultId}::uuid,
            ${input.refreshId}::uuid,
            false,
            1,
            ${input.observedAt}
          )
        `;
        stateRevision = 1;
      } else {
        const updated = await tx`
          update app.live_states
          set
            state_version = ${input.stateVersion},
            current_value = ${JSON.stringify(input.currentValue)}::jsonb,
            current_value_digest = ${input.currentValueDigest},
            observed_at = ${input.observedAt},
            observation_earliest = ${input.observationEarliest},
            observation_latest = ${input.observationLatest},
            aging_at = ${input.agingAt},
            fresh_until = ${input.freshUntil},
            verification_class = ${input.verificationClass},
            latest_verification_result_id =
              ${input.verificationResultId}::uuid,
            latest_refresh_id = ${input.refreshId}::uuid,
            conflict_active = false,
            revision = revision + 1,
            updated_at = ${input.observedAt}
          where state_id = ${input.stateId}::uuid
          returning revision
        `;
        if (!updated[0]) {
          throw new Error("locked live-state projection disappeared");
        }
        stateRevision = Number(updated[0].revision);
      }

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          operation_id,
          entity_revision,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'state',
          ${input.stateId}::uuid,
          'STATE_PROJECTED',
          ${input.verificationResultId}::uuid,
          ${stateRevision},
          ${JSON.stringify({
            refresh_id: input.refreshId,
            verification_result_id: input.verificationResultId,
            observed_at: input.observedAt.toISOString(),
            aging_at: input.agingAt.toISOString(),
            fresh_until: input.freshUntil.toISOString(),
            conflict_active: false,
          })}::jsonb,
          ${input.observedAt}
        )
      `;

      return {
        kind: "projected",
        stateId: input.stateId,
        refreshId: input.refreshId,
        verificationResultId: input.verificationResultId,
        historyId,
        stateRevision,
        observedAt: input.observedAt,
        agingAt: input.agingAt,
        freshUntil: input.freshUntil,
        currentValue: input.currentValue,
      } as const;
    });
  }
}
