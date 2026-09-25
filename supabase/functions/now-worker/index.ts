import { createProductionSettlementWorkerHandler } from "../../../backend/src/settlement-worker-runtime.ts";

Deno.serve(createProductionSettlementWorkerHandler());
