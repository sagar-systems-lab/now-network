import { type AppDependencies, createApp } from "../../backend/src/app.ts";
import { NowWorkerCoordinator } from "../../backend/src/now-worker-coordinator.ts";
import type { ReceiptCoordinator } from "../../backend/src/receipt-coordinator.ts";
import type { RealtimePublisher } from "../../backend/src/realtime-outbox.ts";
import type { SettlementCoordinator } from "../../backend/src/settlement-coordinator.ts";
import { createSettlementWorkerHandler } from "../../backend/src/settlement-worker.ts";

const ACTOR_ID = "10000000-0000-4000-8000-000000000001";
const AUTH_USER_ID = "10000000-0000-4000-8000-000000000002";
const STATE_ID = "20000000-0000-4000-8000-000000000001";
const WALLET_BINDING_ID = "30000000-0000-4000-8000-000000000001";
const REFRESH_ID = "40000000-0000-4000-8000-000000000001";
const ACCEPTANCE_ID = "50000000-0000-4000-8000-000000000001";
const CHALLENGE_ID = "60000000-0000-4000-8000-000000000001";
const EVIDENCE_ID = "70000000-0000-4000-8000-000000000001";
const VERIFICATION_ID = "80000000-0000-4000-8000-000000000001";
const RECEIPT_ID = "90000000-0000-4000-8000-000000000001";
const WORKER_TOKEN = "vertical-worker-token-0123456789abcdef";

type FlowState = {
  freshness: "STALE" | "LIVE";
  refresh: string;
  claim: string;
  evidenceCommitted: boolean;
  verified: boolean;
  settled: boolean;
  receiptFinal: boolean;
  realtimePublished: boolean;
};

function authenticatedRequest(
  path: string,
  init: RequestInit = {},
): Request {
  const headers = new Headers(init.headers);
  headers.set("authorization", "Bearer integration-session");
  return new Request(`https://now.invalid${path}`, {
    ...init,
    headers,
  });
}

async function expectSuccess(
  handler: (request: Request) => Promise<Response>,
  request: Request,
  expectedStatus: number,
): Promise<Record<string, unknown>> {
  const response = await handler(request);
  const body = await response.json() as Record<string, unknown>;
  if (response.status !== expectedStatus) {
    throw new Error(
      `unexpected response ${response.status}: ${JSON.stringify(body)}`,
    );
  }
  return body;
}

Deno.test("headless backend flow reaches a final receipt", async () => {
  const flow: FlowState = {
    freshness: "STALE",
    refresh: "NONE",
    claim: "NONE",
    evidenceCommitted: false,
    verified: false,
    settled: false,
    receiptFinal: false,
    realtimePublished: false,
  };

  const actor = {
    actorId: ACTOR_ID,
    status: "ACTIVE" as const,
    revision: 1,
  };

  const authVerifier: AppDependencies["authVerifier"] = {
    verify: () =>
      Promise.resolve({
        authUserId: AUTH_USER_ID,
        principalType: "SUPABASE_ANONYMOUS",
      }),
  };

  const identityRepository = {
    resolveActor: () => Promise.resolve(actor),
    listWalletBindings: () => Promise.resolve([]),
  } as unknown as AppDependencies["identityRepository"];

  const refreshCoordinator = {
    create() {
      if (flow.freshness !== "STALE" || flow.refresh !== "NONE") {
        throw new Error("refresh did not start from stale state");
      }
      flow.refresh = "DRAFT";
      return Promise.resolve({
        status: 201,
        data: {
          refresh_id: REFRESH_ID,
          state_id: STATE_ID,
          status: flow.refresh,
        },
      });
    },
    fundingIntent() {
      if (flow.refresh !== "DRAFT") {
        throw new Error("funding intent was out of order");
      }
      flow.refresh = "AWAITING_FUNDING";
      return Promise.resolve({
        refresh_id: REFRESH_ID,
        status: flow.refresh,
        operation_id: "41000000-0000-4000-8000-000000000001",
      });
    },
    observeFunding() {
      if (flow.refresh !== "AWAITING_FUNDING") {
        throw new Error("funding observation was out of order");
      }
      flow.refresh = "AVAILABLE";
      return Promise.resolve({
        refresh_id: REFRESH_ID,
        status: flow.refresh,
      });
    },
    get() {
      return Promise.resolve({
        refresh_id: REFRESH_ID,
        status: flow.refresh,
      });
    },
  } as unknown as NonNullable<AppDependencies["refreshCoordinator"]>;

  const claimCoordinator = {
    prepare() {
      if (flow.refresh !== "AVAILABLE" || flow.claim !== "NONE") {
        throw new Error("claim preparation was out of order");
      }
      flow.claim = "WALLET_PENDING";
      return Promise.resolve({
        status: 201,
        data: {
          acceptance_id: ACCEPTANCE_ID,
          refresh_id: REFRESH_ID,
          status: flow.claim,
        },
      });
    },
    observe() {
      if (flow.claim !== "WALLET_PENDING") {
        throw new Error("claim observation was out of order");
      }
      flow.claim = "CLAIMED";
      flow.refresh = "CLAIMED";
      return Promise.resolve({
        acceptance_id: ACCEPTANCE_ID,
        refresh_id: REFRESH_ID,
        status: flow.claim,
      });
    },
    get() {
      return Promise.resolve({
        acceptance_id: ACCEPTANCE_ID,
        status: flow.claim,
      });
    },
  } as unknown as NonNullable<AppDependencies["claimCoordinator"]>;

  const evidenceChallengeService = {
    issue() {
      if (flow.claim !== "CLAIMED") {
        throw new Error("evidence challenge was issued before claim");
      }
      flow.claim = "CAPTURE_ACTIVE";
      flow.refresh = "CAPTURE_IN_PROGRESS";
      return Promise.resolve({
        challenge_id: CHALLENGE_ID,
        nonce: "integration-nonce",
        expires_at: "2026-09-26T10:00:00.000Z",
      });
    },
  } as unknown as NonNullable<AppDependencies["evidenceChallengeService"]>;

  const evidenceUploadService = {
    authorize() {
      if (flow.claim !== "CAPTURE_ACTIVE") {
        throw new Error("upload authorization was out of order");
      }
      return Promise.resolve({
        evidence_id: EVIDENCE_ID,
        challenge_id: CHALLENGE_ID,
        upload_url: "https://storage.invalid/signed-upload",
      });
    },
  } as unknown as NonNullable<AppDependencies["evidenceUploadService"]>;

  const evidenceCommitService = {
    commit() {
      if (flow.claim !== "CAPTURE_ACTIVE") {
        throw new Error("evidence commit was out of order");
      }
      flow.evidenceCommitted = true;
      flow.claim = "EVIDENCE_COMMITTED";
      flow.refresh = "EVIDENCE_SUBMITTED";
      return Promise.resolve({
        status: 201,
        data: {
          evidence_id: EVIDENCE_ID,
          refresh_id: REFRESH_ID,
          status: "COMMITTED",
        },
      });
    },
  } as unknown as NonNullable<AppDependencies["evidenceCommitService"]>;

  const verificationService = {
    verify() {
      if (!flow.evidenceCommitted || flow.refresh !== "EVIDENCE_SUBMITTED") {
        throw new Error("verification was out of order");
      }
      flow.verified = true;
      flow.refresh = "VERIFIED";
      return Promise.resolve({
        status: 200,
        data: {
          result: "VERIFIED",
          refresh_id: REFRESH_ID,
          verification_result_id: VERIFICATION_ID,
        },
      });
    },
  } as unknown as NonNullable<AppDependencies["verificationService"]>;

  const stateProjectionService = {
    project(refreshId: string, verificationResultId: string) {
      if (
        !flow.verified ||
        refreshId !== REFRESH_ID ||
        verificationResultId !== VERIFICATION_ID
      ) {
        throw new Error("state projection lost verification authority");
      }
      flow.freshness = "LIVE";
      return Promise.resolve({
        state_id: STATE_ID,
        refresh_id: REFRESH_ID,
        verification_result_id: VERIFICATION_ID,
        freshness: "LIVE",
      });
    },
  } as unknown as NonNullable<AppDependencies["stateProjectionService"]>;

  const receiptService = {
    get() {
      if (!flow.receiptFinal) {
        throw new Error("receipt was requested before finalization");
      }
      return Promise.resolve({
        receipt_id: RECEIPT_ID,
        refresh_id: REFRESH_ID,
        state_id: STATE_ID,
        verification_result_id: VERIFICATION_ID,
        status: "FINAL",
      });
    },
  } as unknown as NonNullable<AppDependencies["receiptService"]>;

  const app = createApp({
    authVerifier,
    identityRepository,
    refreshCoordinator,
    claimCoordinator,
    evidenceChallengeService,
    evidenceUploadService,
    evidenceCommitService,
    verificationService,
    stateProjectionService,
    receiptService,
  });

  await expectSuccess(
    app,
    authenticatedRequest("/v1/refreshes", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "idempotency-key": "aaaaaaa1",
      },
      body: JSON.stringify({
        state_id: STATE_ID,
        wallet_binding_id: WALLET_BINDING_ID,
        funding_target_atomic: "1000",
      }),
    }),
    201,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/refreshes/${REFRESH_ID}/funding-intent`, {
      method: "POST",
      headers: { "idempotency-key": "bbbbbbb2" },
    }),
    200,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/refreshes/${REFRESH_ID}/funding-observe`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "idempotency-key": "ccccccc3",
      },
      body: JSON.stringify({ signature: "integration-funding-signature" }),
    }),
    200,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/opportunities/${REFRESH_ID}/claim`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "idempotency-key": "ddddddd4",
      },
      body: JSON.stringify({
        wallet_binding_id: WALLET_BINDING_ID,
      }),
    }),
    201,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/claims/${ACCEPTANCE_ID}/observe`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ signature: "integration-claim-signature" }),
    }),
    200,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/claims/${ACCEPTANCE_ID}/challenge`, {
      method: "POST",
    }),
    201,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/evidence-challenges/${CHALLENGE_ID}/upload`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        nonce: "integration-nonce",
        media_mime: "image/jpeg",
      }),
    }),
    201,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/evidence/${EVIDENCE_ID}/commit`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "idempotency-key": "eeeeeee5",
      },
      body: JSON.stringify({
        answer_value: { kind: "numeric", scaled_value: "2", scale: 0 },
      }),
    }),
    201,
  );

  const verificationResponse = await expectSuccess(
    app,
    authenticatedRequest(`/v1/refreshes/${REFRESH_ID}/verify`, {
      method: "POST",
    }),
    200,
  );
  const verificationData = verificationResponse.data as Record<string, unknown>;
  const projection = verificationData.state_projection as Record<string, unknown>;
  if (projection.freshness !== "LIVE" || flow.freshness !== "LIVE") {
    throw new Error("verified state did not become LIVE");
  }

  const settlement = {
    runOnce() {
      if (!flow.verified || flow.freshness !== "LIVE") {
        throw new Error("settlement started before verified LIVE state");
      }
      flow.settled = true;
      return Promise.resolve({
        prepared: 1,
        signed: 1,
        submitted: 1,
        ambiguous: 0,
        pending: 0,
        confirmed: 0,
        finalized: 1,
        safeRetries: 0,
        conflicts: 0,
        deferred: 0,
      });
    },
  } as unknown as SettlementCoordinator;

  const receipts = {
    runOnce() {
      if (!flow.settled) {
        throw new Error("receipt finalization ran before settlement");
      }
      flow.receiptFinal = true;
      flow.refresh = "COMPLETED";
      return Promise.resolve({
        finalized: 1,
        replayed: 0,
        conflicts: 0,
      });
    },
  } as unknown as ReceiptCoordinator;

  const realtime = {
    runOnce() {
      if (!flow.receiptFinal) {
        throw new Error("realtime publication ran before receipt");
      }
      flow.realtimePublished = true;
      return Promise.resolve({ published: 2 });
    },
  } as unknown as RealtimePublisher;

  const worker = createSettlementWorkerHandler(
    new NowWorkerCoordinator(settlement, receipts, realtime),
    WORKER_TOKEN,
  );
  const workerResponse = await worker(
    new Request("https://worker.invalid/", {
      method: "POST",
      headers: { authorization: `Bearer ${WORKER_TOKEN}` },
    }),
  );
  if (workerResponse.status !== 200) {
    throw new Error("headless worker did not complete settlement pipeline");
  }

  const receiptResponse = await expectSuccess(
    app,
    authenticatedRequest(`/v1/refreshes/${REFRESH_ID}/receipt`),
    200,
  );
  const receipt = receiptResponse.data as Record<string, unknown>;
  if (
    receipt.status !== "FINAL" ||
    !flow.settled ||
    !flow.receiptFinal ||
    !flow.realtimePublished ||
    flow.refresh !== "COMPLETED"
  ) {
    throw new Error("headless backend flow did not close at final receipt");
  }
});
