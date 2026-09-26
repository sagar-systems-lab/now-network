import { UUID_PATTERN } from "./http.ts";

export type StructuredLogLevel = "DEBUG" | "INFO" | "WARN" | "ERROR" | "FATAL";
export type StructuredLogSink = (line: string) => void;

export type StructuredLogRecord = {
  ts: string;
  level: StructuredLogLevel;
  service: string;
  event: string;
  request_id?: string;
  method?: string;
  status_code?: number;
  duration_ms?: number;
  result?: "SUCCESS" | "REJECTED" | "FAILURE";
};

function requestIdentity(request: Request): {
  request: Request;
  requestId: string;
} {
  const candidate = request.headers.get("x-request-id")?.trim();
  const requestId = candidate && UUID_PATTERN.test(candidate) ? candidate : crypto.randomUUID();

  if (candidate === requestId) return { request, requestId };

  const headers = new Headers(request.headers);
  headers.set("x-request-id", requestId);
  return {
    request: new Request(request, { headers }),
    requestId,
  };
}

export function structuredLogLine(record: StructuredLogRecord): string {
  return JSON.stringify(record);
}

export function withStructuredRequestLogging(
  handler: (request: Request) => Promise<Response>,
  service: string,
  options: {
    sink?: StructuredLogSink;
    now?: () => Date;
    monotonicNow?: () => number;
  } = {},
): (request: Request) => Promise<Response> {
  const sink = options.sink ?? console.log;
  const now = options.now ?? (() => new Date());
  const monotonicNow = options.monotonicNow ?? (() => performance.now());

  return async (incoming: Request): Promise<Response> => {
    const identified = requestIdentity(incoming);
    const started = monotonicNow();

    try {
      const response = await handler(identified.request);
      const status = response.status;
      const level: StructuredLogLevel = status >= 500 ? "ERROR" : status >= 400 ? "WARN" : "INFO";
      const result = status >= 500 ? "FAILURE" : status >= 400 ? "REJECTED" : "SUCCESS";

      sink(structuredLogLine({
        ts: now().toISOString(),
        level,
        service,
        event: "http.request.completed",
        request_id: identified.requestId,
        method: incoming.method,
        status_code: status,
        duration_ms: Math.max(0, Math.round(monotonicNow() - started)),
        result,
      }));
      return response;
    } catch (error) {
      sink(structuredLogLine({
        ts: now().toISOString(),
        level: "ERROR",
        service,
        event: "http.request.failed",
        request_id: identified.requestId,
        method: incoming.method,
        duration_ms: Math.max(0, Math.round(monotonicNow() - started)),
        result: "FAILURE",
      }));
      throw error;
    }
  };
}
