import { inspectMp4, parseEvidenceVideo, VIDEO_MAX_BYTES } from "../src/evidence-video.ts";
import { ApiFault } from "../src/errors.ts";
import fixtures from "./fixtures/short_video.json" with { type: "json" };
import { SupabaseEvidenceObjectStorage } from "../src/evidence-object-storage.ts";

const decode = (value: string) => Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
function rejects(fn: () => unknown) {
  try {
    fn();
  } catch (error) {
    if (error instanceof ApiFault) return;
    throw error;
  }
  throw new Error("Invalid video was accepted");
}

Deno.test("real H264 MP4 sample timelines enforce 3–15 seconds", () => {
  if (inspectMp4(decode(fixtures.valid)) !== 3000) throw new Error("Wrong actual track duration");
  rejects(() => inspectMp4(decode(fixtures.short)));
  rejects(() => inspectMp4(decode(fixtures.long)));
  rejects(() => inspectMp4(decode(fixtures.valid).subarray(0, 300)));
  rejects(() => inspectMp4(new Uint8Array(VIDEO_MAX_BYTES + 1)));
  const noMedia = decode(fixtures.valid);
  const marker = new TextDecoder("latin1").decode(noMedia).indexOf("mdat");
  noMedia.set(new TextEncoder().encode("free"), marker);
  rejects(() => inspectMp4(noMedia));
});

Deno.test("video metadata binds the companion path and requires capture after the photo", () => {
  const good = {
    sha256: "ab".repeat(32),
    size_bytes: 4096,
    duration_ms: 3000,
    capture_started_monotonic_ms: 2000,
    capture_completed_monotonic_ms: 5100,
  };
  const video = parseEvidenceVideo(good, "refreshes/r/evidence/e/original", 1700);
  if (video?.object_key !== "refreshes/r/evidence/e/original.mp4") {
    throw new Error("Unbound clip path");
  }
  rejects(() => parseEvidenceVideo({ ...good, duration_ms: 2999 }, "p", 1700));
  rejects(() => parseEvidenceVideo({ ...good, duration_ms: 15001 }, "p", 1700));
  rejects(() => parseEvidenceVideo({ ...good, capture_started_monotonic_ms: 1600 }, "p", 1700));
  rejects(() => parseEvidenceVideo({ ...good, size_bytes: VIDEO_MAX_BYTES + 1 }, "p", 1700));
  rejects(() => parseEvidenceVideo({ ...good, sha256: "not-a-hash" }, "p", 1700));
});

Deno.test("private storage inspects actual MP4 bytes and bounds a chunked body", async () => {
  const bytes = decode(fixtures.valid);
  const storage = new SupabaseEvidenceObjectStorage(
    "https://project.supabase.co",
    "test-service-key",
    "private",
    (() =>
      Promise.resolve(
        new Response(bytes, { headers: { "content-type": "video/mp4" } }),
      )) as typeof fetch,
  );
  const result = await storage.inspectUploadedObject("evidence/original.mp4", VIDEO_MAX_BYTES);
  if (
    result.videoDurationMs !== 3000 || result.sizeBytes !== bytes.length ||
    result.sha256.length !== 32
  ) {
    throw new Error("Video was not inspected from stored bytes");
  }
  let rejected = false;
  try {
    await storage.inspectUploadedObject("evidence/original.mp4", 128);
  } catch {
    rejected = true;
  }
  if (!rejected) throw new Error("Chunked body exceeded the inspection bound");
});
