import type { NowWorkerCoordinator } from "./now-worker-coordinator.ts";
import type { RuntimeHealthRepository } from "./runtime-health.ts";

function json(
  status: number,
  body: Record<string, unknown>,
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function constantTimeEqual(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let mismatch = 0;
  for (let index = 0; index < left.length; index += 1) {
    mismatch |= left.charCodeAt(index) ^ right.charCodeAt(index);
  }
  return mismatch === 0;
}

export type SettlementWorkerHealthOptions = {
  repository: Pick<RuntimeHealthRepository, "recordWorkerHeartbeat">;
  workerId: string;
  buildVersion: string;
  now?: () => Date;
};

async function recordHeartbeat(
  options: SettlementWorkerHealthOptions | undefined,
  result: "SUCCESS" | "FAILURE",
  summary: unknown,
): Promise<void> {
  if (!options) return;
  const observedAt = (options.now ?? (() => new Date()))();

  try {
    await options.repository.recordWorkerHeartbeat({
      workerId: options.workerId,
      observedAt,
      lastJobAt: observedAt,
      buildVersion: options.buildVersion,
      result,
      summary,
    });
  } catch {
    return;
  }
}

export function createSettlementWorkerHandler(
  coordinator: NowWorkerCoordinator,
  workerToken: string,
  batchLimit = 8,
  health?: SettlementWorkerHealthOptions,
): (request: Request) => Promise<Response> {
  if (workerToken.length < 32) {
    throw new Error("worker token must contain at least 32 characters");
  }
  if (!Number.isSafeInteger(batchLimit) || batchLimit < 1 || batchLimit > 32) {
    throw new Error("worker batch limit must be between 1 and 32");
  }
  if (
    health &&
    (health.workerId.trim().length === 0 ||
      health.buildVersion.trim().length === 0)
  ) {
    throw new Error("worker health identity is incomplete");
  }

  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") {
      return json(405, {
        error: "METHOD_NOT_ALLOWED",
      });
    }

    const authorization = request.headers.get("authorization") ?? "";
    const expected = `Bearer ${workerToken}`;
    if (!constantTimeEqual(authorization, expected)) {
      return json(401, {
        error: "WORKER_AUTH_REQUIRED",
      });
    }

    try {
      const summary = await coordinator.runOnce(batchLimit);
      await recordHeartbeat(health, "SUCCESS", summary);
      return json(200, {
        ok: true,
        ...summary,
      });
    } catch {
      await recordHeartbeat(health, "FAILURE", {
        error: "WORKER_DEPENDENCY_UNAVAILABLE",
      });
      return json(503, {
        ok: false,
        error: "WORKER_DEPENDENCY_UNAVAILABLE",
      });
    }
  };
}
