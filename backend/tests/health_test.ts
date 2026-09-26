import { handleRequest, healthPayload } from "../src/health.ts";
import type { ReadinessProbe } from "../src/runtime-health.ts";

const READY_TOKEN = "ready-test-token-0123456789-abcdef";

Deno.test("health payload is stable, public-safe, and explicit", () => {
  const now = () => new Date("2026-09-26T09:30:00.000Z");
  const payload = healthPayload(now);

  if (payload.status !== "HEALTHY") throw new Error("expected status=HEALTHY");
  if (payload.service !== "now-api") throw new Error("unexpected service");
  if (payload.schema_version !== 1) throw new Error("unexpected schema version");
  if (payload.time !== "2026-09-26T09:30:00.000Z") {
    throw new Error("unexpected health time");
  }
});

Deno.test("GET /health returns 200 and no-store", async () => {
  const response = await handleRequest(
    new Request("http://localhost/health"),
  );

  if (response.status !== 200) throw new Error(`expected 200, got ${response.status}`);
  if (response.headers.get("cache-control") !== "no-store") {
    throw new Error("health response must not be cached");
  }

  const payload = await response.json();
  if (payload.service !== "now-api" || payload.status !== "HEALTHY") {
    throw new Error("unexpected health response");
  }
});

Deno.test("GET /ready rejects missing internal authorization", async () => {
  const probe: ReadinessProbe = {
    check: () => Promise.resolve("HEALTHY"),
  };
  const response = await handleRequest(
    new Request("http://localhost/ready"),
    probe,
    READY_TOKEN,
  );

  if (response.status !== 401) {
    throw new Error(`expected 401, got ${response.status}`);
  }
  const payload = await response.json();
  if (payload.error !== "READINESS_AUTH_REQUIRED") {
    throw new Error("readiness auth failure drifted");
  }
});

Deno.test("GET /ready exposes only readiness status to an authorized caller", async () => {
  const probe: ReadinessProbe = {
    check: () => Promise.resolve("DEGRADED"),
  };
  const response = await handleRequest(
    new Request("http://localhost/ready", {
      headers: { authorization: `Bearer ${READY_TOKEN}` },
    }),
    probe,
    READY_TOKEN,
    () => new Date("2026-09-26T09:31:00.000Z"),
  );

  if (response.status !== 200) throw new Error(`expected 200, got ${response.status}`);
  const payload = await response.json();
  if (
    payload.status !== "DEGRADED" ||
    "worker_last_seen" in payload ||
    "database" in payload
  ) {
    throw new Error("readiness response leaked internal diagnostics");
  }
});

Deno.test("GET /ready fails closed when no readiness probe exists", async () => {
  const response = await handleRequest(
    new Request("http://localhost/ready", {
      headers: { authorization: `Bearer ${READY_TOKEN}` },
    }),
    undefined,
    READY_TOKEN,
  );
  if (response.status !== 503) {
    throw new Error(`expected 503, got ${response.status}`);
  }
  const payload = await response.json();
  if (payload.status !== "UNAVAILABLE") {
    throw new Error("missing readiness probe must be unavailable");
  }
});

Deno.test("unknown route returns 404", async () => {
  const response = await handleRequest(
    new Request("http://localhost/not-found"),
  );
  if (response.status !== 404) throw new Error(`expected 404, got ${response.status}`);
});
