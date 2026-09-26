import { structuredLogLine, withStructuredRequestLogging } from "../src/structured-log.ts";

Deno.test("structured log line is valid JSON without freeform payload data", () => {
  const line = structuredLogLine({
    ts: "2026-09-26T09:30:00.000Z",
    level: "INFO",
    service: "now-api",
    event: "http.request.completed",
    request_id: "00000000-0000-4000-8000-000000000001",
    method: "POST",
    status_code: 200,
    duration_ms: 12,
    result: "SUCCESS",
  });
  const parsed = JSON.parse(line);
  if (
    parsed.level !== "INFO" ||
    parsed.service !== "now-api" ||
    parsed.result !== "SUCCESS"
  ) {
    throw new Error("structured log envelope drifted");
  }
});

Deno.test("request logger assigns one request id and classifies 4xx as WARN", async () => {
  const lines: string[] = [];
  let seenRequestId: string | null = null;
  const handler = withStructuredRequestLogging(
    (request) => {
      seenRequestId = request.headers.get("x-request-id");
      return Promise.resolve(new Response(null, { status: 404 }));
    },
    "now-api",
    {
      sink: (line) => lines.push(line),
      now: () => new Date("2026-09-26T09:30:00.000Z"),
      monotonicNow: (() => {
        let value = 100;
        return () => {
          value += 5;
          return value;
        };
      })(),
    },
  );

  const response = await handler(new Request("https://now.invalid/v1/test"));
  if (response.status !== 404 || !seenRequestId) {
    throw new Error("request logger did not preserve request identity");
  }
  if (lines.length !== 1) throw new Error("unexpected structured log count");

  const parsed = JSON.parse(lines[0]);
  if (
    parsed.request_id !== seenRequestId ||
    parsed.level !== "WARN" ||
    parsed.result !== "REJECTED" ||
    parsed.status_code !== 404
  ) {
    throw new Error("request log classification is incorrect");
  }
  if ("url" in parsed || "query" in parsed || "body" in parsed) {
    throw new Error("structured request log exposed request payload data");
  }
});
