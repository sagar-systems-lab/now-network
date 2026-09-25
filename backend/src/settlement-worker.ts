import type { SettlementCoordinator } from "./settlement-coordinator.ts";

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

export function createSettlementWorkerHandler(
  coordinator: SettlementCoordinator,
  workerToken: string,
  batchLimit = 8,
): (request: Request) => Promise<Response> {
  if (workerToken.length < 32) {
    throw new Error("worker token must contain at least 32 characters");
  }
  if (!Number.isSafeInteger(batchLimit) || batchLimit < 1 || batchLimit > 32) {
    throw new Error("worker batch limit must be between 1 and 32");
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
      return json(200, {
        ok: true,
        settlement: summary,
      });
    } catch {
      return json(503, {
        ok: false,
        error: "WORKER_DEPENDENCY_UNAVAILABLE",
      });
    }
  };
}
