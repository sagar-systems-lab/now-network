import { createProductionHandler } from "../../../backend/src/runtime.ts";

Deno.serve(createProductionHandler());
