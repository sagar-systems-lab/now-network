import { handleRequest } from "./health.ts";

if (import.meta.main) {
  const port = Number(Deno.env.get("PORT") ?? "8787");
  Deno.serve({ port }, handleRequest);
}
