import { SupabaseEvidenceObjectStorage } from "../src/evidence-object-storage.ts";

Deno.test("stalled storage signing and inspection abort instead of waiting indefinitely", async () => {
  const signals: AbortSignal[] = [];
  const stalled = ((_input: string | URL | Request, init?: RequestInit) => {
    const signal = init?.signal;
    if (!signal) throw new Error("storage I/O has no deadline");
    signals.push(signal);
    return new Promise<Response>((_resolve, reject) => {
      signal.addEventListener("abort", () => reject(signal.reason), { once: true });
    });
  }) as typeof fetch;
  const storage = new SupabaseEvidenceObjectStorage(
    "https://project.supabase.co",
    "server-only",
    "private",
    stalled,
  );
  const errors = await Promise.all([
    storage.createSignedUpload("evidence/photo").catch((error: unknown) => error),
    storage.inspectUploadedObject("evidence/photo", 4096).catch((error: unknown) => error),
  ]);
  if (
    signals.length !== 2 || signals.some((signal) => !signal.aborted) ||
    errors.some((error) => !(error instanceof DOMException) || error.name !== "TimeoutError")
  ) {
    throw new Error("stalled storage did not stop at its transport deadline");
  }
});
