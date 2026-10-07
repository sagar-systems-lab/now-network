import type { ActorRecord } from "./identity-repository.ts";
import type { VerificationService } from "./verification-service.ts";
import type { StateProjectionService } from "./state-projection-service.ts";

export type VerificationCandidate = { refreshId: string; actor: ActorRecord };
export type VerificationTickSummary = {
  verified: number;
  awaitingEvidence: number;
  deferred: number;
};

export class VerificationWorker {
  constructor(
    private readonly queue: { listPending(limit: number): Promise<VerificationCandidate[]> },
    private readonly verification: Pick<VerificationService, "verify">,
    private readonly projection: Pick<StateProjectionService, "project">,
  ) {}

  async runOnce(limit = 8): Promise<VerificationTickSummary> {
    const summary = { verified: 0, awaitingEvidence: 0, deferred: 0 };
    const candidates = await this.queue.listPending(Math.max(1, Math.min(32, Math.trunc(limit))));
    for (const candidate of candidates) {
      try {
        const result = await this.verification.verify(candidate.actor, candidate.refreshId);
        if (result.data.result !== "VERIFIED") {
          summary.awaitingEvidence++;
          continue;
        }
        const id = result.data.verification_result_id;
        if (
          typeof id !== "string" ||
          !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(id)
        ) {
          throw new Error("verified result is missing its identity");
        }
        await this.projection.project(candidate.refreshId, id);
        summary.verified++;
      } catch {
        // The durable candidate remains eligible for the next tick.
        summary.deferred++;
      }
    }
    return summary;
  }
}
