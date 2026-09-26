import type { HealthState, ReadinessProbe } from "./runtime-health.ts";

export const HEALTH_SCHEMA_VERSION = 1;

export function healthPayload(
  now: () => Date = () => new Date(),
): Record<string, string | number> {
  return {
    service: "now-api",
    status: "HEALTHY",
    schema_version: HEALTH_SCHEMA_VERSION,
    time: now().toISOString(),
  };
}

function readinessPayload(
  status: HealthState,
  now: () => Date,
): Record<string, string | number> {
  return {
    service: "now-api",
    status,
    schema_version: HEALTH_SCHEMA_VERSION,
    time: now().toISOString(),
  };
}

function constantTimeEqual(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let mismatch = 0;
  for (let index = 0; index < left.length; index += 1) {
    mismatch |= left.charCodeAt(index) ^ right.charCodeAt(index);
  }
  return mismatch === 0;
}

function readinessAuthorized(request: Request, readinessToken?: string): boolean {
  if (!readinessToken || readinessToken.length < 32) return false;
  const authorization = request.headers.get("authorization") ?? "";
  return constantTimeEqual(authorization, `Bearer ${readinessToken}`);
}

export async function handleRequest(
  request: Request,
  readinessProbe?: ReadinessProbe,
  readinessToken?: string,
  now: () => Date = () => new Date(),
): Promise<Response> {
  const url = new URL(request.url);

  if (
    request.method === "GET" &&
    (url.pathname === "/health" || url.pathname.endsWith("/health"))
  ) {
    return Response.json(healthPayload(now), {
      status: 200,
      headers: {
        "cache-control": "no-store",
      },
    });
  }

  if (
    request.method === "GET" &&
    (url.pathname === "/ready" || url.pathname.endsWith("/ready"))
  ) {
    if (!readinessAuthorized(request, readinessToken)) {
      return Response.json(
        { error: "READINESS_AUTH_REQUIRED" },
        {
          status: 401,
          headers: {
            "cache-control": "no-store",
          },
        },
      );
    }

    const status = readinessProbe ? await readinessProbe.check() : "UNAVAILABLE";
    return Response.json(readinessPayload(status, now), {
      status: status === "UNAVAILABLE" ? 503 : 200,
      headers: {
        "cache-control": "no-store",
      },
    });
  }

  return Response.json(
    { error: "not_found" },
    {
      status: 404,
      headers: {
        "cache-control": "no-store",
      },
    },
  );
}
