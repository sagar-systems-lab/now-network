import { SupabaseEvidenceObjectStorage } from "../src/evidence-object-storage.ts";

Deno.test("Supabase storage signer uses the private bucket upload-sign endpoint without upsert", async () => {
  let seenUrl = "";
  let seenInit: RequestInit | undefined;
  const signer = new SupabaseEvidenceObjectStorage(
    "https://project.supabase.co",
    "service-role-secret",
    "evidence-private",
    ((input: string | URL | Request, init?: RequestInit) => {
      seenUrl = String(input);
      seenInit = init;
      return Promise.resolve(
        Response.json({
          url:
            "/object/upload/sign/evidence-private/evidence/r/a/c/file?token=signed-token",
        }),
      );
    }) as typeof fetch,
  );

  const result = await signer.createSignedUpload("evidence/r/a/c/file");
  if (
    seenUrl !==
      "https://project.supabase.co/storage/v1/object/upload/sign/evidence-private/evidence/r/a/c/file"
  ) {
    throw new Error(`unexpected storage signing endpoint: ${seenUrl}`);
  }
  if (seenInit?.method !== "POST") {
    throw new Error("storage signer must use POST");
  }
  const headers = new Headers(seenInit?.headers);
  if (
    headers.get("authorization") !== "Bearer service-role-secret" ||
    headers.get("apikey") !== "service-role-secret" ||
    headers.has("x-upsert")
  ) {
    throw new Error("storage signer authorization or overwrite policy is wrong");
  }
  if (
    result.signedUrl !==
      "https://project.supabase.co/storage/v1/object/upload/sign/evidence-private/evidence/r/a/c/file?token=signed-token"
  ) {
    throw new Error("signed upload URL was assembled incorrectly");
  }
});

Deno.test("Supabase storage signer rejects traversal keys before network access", async () => {
  let calls = 0;
  const signer = new SupabaseEvidenceObjectStorage(
    "https://project.supabase.co",
    "service-role-secret",
    "evidence-private",
    (() => {
      calls += 1;
      return Promise.resolve(Response.json({}));
    }) as typeof fetch,
  );

  try {
    await signer.createSignedUpload("evidence/../escape");
    throw new Error("traversal key unexpectedly reached storage");
  } catch {
    if (calls !== 0) {
      throw new Error("invalid object key reached the storage service");
    }
  }
});
