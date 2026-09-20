import { handleRequest } from "../../../backend/src/health.ts";

Deno.serve(handleRequest);
