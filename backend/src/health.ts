export const HEALTH_SCHEMA_VERSION = 1;

export function healthPayload(): Record<string, string | number> {
  return {
    service: "now-api",
    status: "ok",
    schema_version: HEALTH_SCHEMA_VERSION,
  };
}

export function handleRequest(request: Request): Response {
  const url = new URL(request.url);

  if (
    request.method === "GET" &&
    (url.pathname === "/health" || url.pathname.endsWith("/health"))
  ) {
    return Response.json(healthPayload(), {
      status: 200,
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
