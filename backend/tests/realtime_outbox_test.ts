import { type RealtimeOutboxRepository, RealtimePublisher } from "../src/realtime-outbox.ts";

Deno.test("realtime publisher bounds each worker batch", async () => {
  let observedLimit = 0;
  const repository: RealtimeOutboxRepository = {
    publishBatch: (input) => {
      observedLimit = input.limit;
      return Promise.resolve({ published: 3 });
    },
  };
  const publisher = new RealtimePublisher(
    repository,
    () => new Date("2026-09-26T06:00:00.000Z"),
  );

  const result = await publisher.runOnce(999);
  if (result.published !== 3 || observedLimit !== 64) {
    throw new Error("realtime batch was not bounded");
  }
});
