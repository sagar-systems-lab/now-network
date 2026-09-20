import { handleRequest, healthPayload } from "../src/health.ts";

Deno.test("health payload is stable and explicit", () => {
  const payload = healthPayload();

  if (payload.status !== "ok") throw new Error("expected status=ok");
  if (payload.service !== "now-api") throw new Error("unexpected service");
  if (payload.schema_version !== 1) throw new Error("unexpected schema version");
});

Deno.test("GET /health returns 200 and no-store", async () => {
  const response = handleRequest(new Request("http://localhost/health"));

  if (response.status !== 200) throw new Error(`expected 200, got ${response.status}`);
  if (response.headers.get("cache-control") !== "no-store") {
    throw new Error("health response must not be cached");
  }

  const payload = await response.json();
  if (payload.service !== "now-api") throw new Error("unexpected service");
});

Deno.test("unknown route returns 404", () => {
  const response = handleRequest(new Request("http://localhost/not-found"));
  if (response.status !== 404) throw new Error(`expected 404, got ${response.status}`);
});
