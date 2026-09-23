import { ApiFault, asApiFault } from "./errors.ts";

export const UUID_PATTERN =\n  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function requestId(request: Request): string {
  const candidate = request.headers.get("x-request-id")?.trim();
  return candidate && UUID_PATTERN.test(candidate) ? candidate : crypto.randomUUID();
}

export function successResponse(
  id: string,
  data: unknown,
  status = 200,
  revision?: number,
): Response {
  return Response.json(
    {
      request_id: id,
      server_time: new Date().toISOString(),
      data,
      meta: revision === undefined ? {} : { revision },
    },
    {
      status,
      headers: { "cache-control": "no-store" },
    },
  );
}

export function faultResponse(id: string, error: unknown): Response {
  const fault = asApiFault(error);
  return Response.json(
    {
      request_id: id,
      server_time: new Date().toISOString(),
      error: {
        code: fault.code,
        message: fault.message,
        safe_to_retry: fault.safeToRetry,
        retry_after_ms: fault.retryAfterMs,
      },
    },
    {
      status: fault.status,
      headers: { "cache-control": "no-store" },
    },
  );
}

export async function readJsonObject(request: Request): Promise<Record<string, unknown>> {
  let value: unknown;
  try {
    value = await request.json();
  } catch {
    throw new ApiFault(400, "INVALID_REQUEST", "A valid JSON body is required.");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ApiFault(400, "INVALID_REQUEST", "A JSON object is required.");
  }
  return value as Record<string, unknown>;
}

export function requiredString(
  object: Record<string, unknown>,
  key: string,
  maxLength = 512,
): string {
  const value = object[key];
  if (typeof value !== "string" || value.trim().length === 0 || value.length > maxLength) {
    throw new ApiFault(400, "INVALID_REQUEST", `Invalid ${key}.`);
  }
  return value.trim();
}

export function requiredUuid(object: Record<string, unknown>, key: string): string {
  const value = requiredString(object, key, 64);
  if (!UUID_PATTERN.test(value)) {
    throw new ApiFault(400, "INVALID_REQUEST", `Invalid ${key}.`);
  }
  return value;
}
