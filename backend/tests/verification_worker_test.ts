import { VerificationWorker } from "../src/verification-worker.ts";

const actor = { actorId: "requester", status: "ACTIVE" as const, revision: 1 };
const id = "f2000000-0000-4000-8000-000000000001";

Deno.test("background verification continues committed proof without an app request and isolates failures", async () => {
  const projected: string[] = [];
  const worker = new VerificationWorker(
    {
      listPending: () =>
        Promise.resolve(["waiting", "bad", "ready"].map((refreshId) => ({ refreshId, actor }))),
    },
    {
      verify: (_actor, refreshId) => {
        if (refreshId === "bad") return Promise.reject(new Error("temporary database error"));
        return Promise.resolve({
          status: 200,
          data: {
            result: refreshId === "waiting" ? "REQUIRES_ADDITIONAL_VERIFICATION" : "VERIFIED",
            verification_result_id: id,
          },
        });
      },
    },
    {
      project: (refreshId, verificationId) => {
        if (verificationId !== id) throw new Error("verification identity changed");
        projected.push(refreshId);
        return Promise.resolve({});
      },
    },
  );
  const result = await worker.runOnce();
  if (
    result.verified !== 1 || result.awaitingEvidence !== 1 || result.deferred !== 1 ||
    projected.join() !== "ready"
  ) {
    throw new Error("background proof processing skipped authority or blocked unrelated work");
  }
});

Deno.test("projection interruption is retried from the same verified identity", async () => {
  let attempts = 0;
  const worker = new VerificationWorker(
    { listPending: () => Promise.resolve([{ refreshId: "ready", actor }]) },
    {
      verify: () =>
        Promise.resolve({
          status: 200,
          data: { result: "VERIFIED", verification_result_id: id, replayed: true },
        }),
    },
    {
      project: (_refreshId, verificationId) => {
        if (verificationId !== id) throw new Error("recovery changed verification identity");
        attempts++;
        return attempts === 1 ? Promise.reject(new Error("connection lost")) : Promise.resolve({});
      },
    },
  );
  const first = await worker.runOnce(), recovered = await worker.runOnce();
  if (first.deferred !== 1 || first.verified !== 0 || recovered.verified !== 1 || attempts !== 2) {
    throw new Error("verified proof was lost after interrupted projection");
  }
});
