import { SupabaseEvidenceObjectStorage } from "../src/evidence-object-storage.ts";
import { ApiFault } from "../src/errors.ts";
import { inspectJpeg } from "../src/evidence-photo.ts";
import photos from "./fixtures/photos.json" with { type: "json" };

const photoBytes = (value: string) => Uint8Array.from(atob(value), (c) => c.charCodeAt(0));

function objectStorage(bytes: Uint8Array, headers: Record<string, string> = {}) {
  return new SupabaseEvidenceObjectStorage(
    "https://project.supabase.co",
    "service-role-secret",
    "evidence-private",
    (() =>
      Promise.resolve(
        new Response(new Uint8Array(bytes), {
          headers: { "content-type": "image/jpeg", ...headers },
        }),
      )) as typeof fetch,
  );
}

async function rejectsMedia(action: () => unknown) {
  try {
    await action();
  } catch (error) {
    if (
      error instanceof ApiFault && error.code === "EVIDENCE_MEDIA_INVALID" &&
      error.status === 400 && !error.safeToRetry
    ) return;
    throw error;
  }
  throw new Error("Invalid evidence was accepted");
}

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
          url: "/object/upload/sign/evidence-private/evidence/r/a/c/file?token=signed-token",
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

Deno.test("Supabase storage inspector hashes the exact private object bytes", async () => {
  const bytes = photoBytes(photos.baseline);
  const signer = new SupabaseEvidenceObjectStorage(
    "https://project.supabase.co",
    "service-role-secret",
    "evidence-private",
    ((input: string | URL | Request, init?: RequestInit) => {
      if (
        String(input) !==
          "https://project.supabase.co/storage/v1/object/evidence-private/refreshes/r/evidence/e/original" ||
        init?.method !== "GET"
      ) {
        throw new Error("unexpected evidence inspection request");
      }
      return Promise.resolve(
        new Response(bytes, {
          headers: {
            "content-length": String(bytes.length),
            "content-type": "image/jpeg",
          },
        }),
      );
    }) as typeof fetch,
  );

  const inspected = await signer.inspectUploadedObject(
    "refreshes/r/evidence/e/original",
    4096,
  );
  if (
    inspected.sizeBytes !== bytes.length ||
    inspected.mediaMime !== "image/jpeg" ||
    inspected.sha256.length !== 32
  ) {
    throw new Error("evidence object integrity was not derived");
  }
  const expected = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  if (!expected.every((byte, index) => inspected.sha256[index] === byte)) {
    throw new Error("Photo validation changed the uploaded bytes");
  }
});

Deno.test("photo inspection accepts baseline and progressive JPEG with EXIF", async () => {
  for (const value of Object.values(photos)) {
    const bytes = photoBytes(value);
    const result = await objectStorage(bytes).inspectUploadedObject("evidence/photo", 4096);
    if (result.sizeBytes !== bytes.length) throw new Error("Valid photo was changed");
    // Android Ultra HDR may append a gain map after the complete primary JPEG.
    inspectJpeg(new Uint8Array([...bytes, ...bytes]));
  }
});

Deno.test("photo inspection rejects mislabeled bytes and every truncated fixture prefix", async () => {
  await rejectsMedia(() =>
    objectStorage(new Uint8Array([1, 2, 3, 4]))
      .inspectUploadedObject("evidence/photo", 4096)
  );
  for (const value of Object.values(photos)) {
    const bytes = photoBytes(value);
    for (let length = 0; length < bytes.length; length++) {
      await rejectsMedia(() => inspectJpeg(bytes.subarray(0, length)));
    }
    const badSegment = bytes.slice();
    badSegment[4] = 0xff;
    badSegment[5] = 0xff;
    await rejectsMedia(() => inspectJpeg(badSegment));
  }
  await rejectsMedia(() => inspectJpeg(new Uint8Array([0xff, 0xd8, 0xff, 0xd9])));
});

Deno.test("empty, oversized and unsupported evidence produce permanent media errors", async () => {
  const bytes = photoBytes(photos.baseline);
  for (
    const [payload, headers, limit] of [
      [new Uint8Array(), {}, 4096],
      [bytes, {}, 10],
      [bytes, { "content-length": "9999" }, 4096],
      [bytes, { "content-type": "text/plain" }, 4096],
      [bytes, { "content-type": "" }, 4096],
    ] as const
  ) {
    await rejectsMedia(() =>
      objectStorage(payload, headers)
        .inspectUploadedObject("evidence/photo", limit)
    );
  }
});
