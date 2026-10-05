import { ApiFault } from "./errors.ts";

export const VIDEO_MIN_MS = 3_000;
export const VIDEO_MAX_MS = 15_000;
export const VIDEO_MAX_BYTES = 6 * 1024 * 1024;

export type EvidenceVideo = {
  object_key: string;
  sha256: string;
  size_bytes: number;
  duration_ms: number;
  capture_started_monotonic_ms: number;
  capture_completed_monotonic_ms: number;
};

function invalid(): never {
  throw new ApiFault(
    400,
    "EVIDENCE_MEDIA_INVALID",
    "Record a valid 3–15 second MP4 video after the photo.",
  );
}

export function parseEvidenceVideo(
  value: unknown,
  photoKey: string,
  photoCompleted: number,
): EvidenceVideo | null {
  if (value == null) return null;
  if (typeof value !== "object" || Array.isArray(value)) invalid();
  const row = value as Record<string, unknown>;
  const integer = (key: string, min: number, max = Number.MAX_SAFE_INTEGER): number => {
    const n = row[key];
    if (!Number.isSafeInteger(n) || (n as number) < min || (n as number) > max) invalid();
    return n as number;
  };
  if (typeof row.sha256 !== "string" || !/^[a-f0-9]{64}$/u.test(row.sha256)) invalid();
  const duration = integer("duration_ms", VIDEO_MIN_MS, VIDEO_MAX_MS);
  const started = integer("capture_started_monotonic_ms", photoCompleted);
  const completed = integer("capture_completed_monotonic_ms", started);
  if (completed - started < duration - 250 || completed - started > VIDEO_MAX_MS + 5_000) invalid();
  return {
    object_key: `${photoKey}.mp4`,
    sha256: row.sha256,
    size_bytes: integer("size_bytes", 1, VIDEO_MAX_BYTES),
    duration_ms: duration,
    capture_started_monotonic_ms: started,
    capture_completed_monotonic_ms: completed,
  };
}

// Read the actual video track's sample timeline, not client-supplied duration or HTTP headers.
export function inspectMp4(bytes: Uint8Array): number {
  if (bytes.length > VIDEO_MAX_BYTES || bytes.length < 32) invalid();
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const u32 = (p: number) => {
    if (p < 0 || p + 4 > bytes.length) invalid();
    return view.getUint32(p);
  };
  const text = (p: number) => String.fromCharCode(...bytes.subarray(p, p + 4));
  type Box = { type: string; start: number; end: number };
  let count = 0;
  function boxes(start: number, end: number): Box[] {
    const result: Box[] = [];
    for (let p = start; p < end;) {
      if (++count > 4096 || p + 8 > end) invalid();
      let size = u32(p), header = 8;
      if (size === 1) {
        if (p + 16 > end || u32(p + 8) !== 0) invalid();
        size = u32(p + 12);
        header = 16;
      } else if (size === 0) size = end - p;
      if (size < header || p + size > end) invalid();
      result.push({ type: text(p + 4), start: p + header, end: p + size });
      p += size;
    }
    return result;
  }
  function one(parent: Box[], type: string): Box {
    const found = parent.filter((b) => b.type === type);
    if (found.length !== 1) invalid();
    return found[0];
  }
  const top = boxes(0, bytes.length);
  const ftyp = one(top, "ftyp");
  if (ftyp.end - ftyp.start < 8) invalid();
  const dataBytes = top.filter((b) => b.type === "mdat").reduce(
    (sum, b) => sum + b.end - b.start,
    0,
  );
  if (dataBytes < 16 || top.some((b) => b.type === "moof")) invalid();
  const moov = one(top, "moov");
  const durations: number[] = [];
  for (const track of boxes(moov.start, moov.end).filter((b) => b.type === "trak")) {
    const mdia = one(boxes(track.start, track.end), "mdia");
    const media = boxes(mdia.start, mdia.end);
    const handler = one(media, "hdlr");
    if (handler.start + 12 > handler.end) invalid();
    if (text(handler.start + 8) !== "vide") continue;
    const header = one(media, "mdhd");
    const version = bytes[header.start];
    if (version !== 0 && version !== 1) invalid();
    const scaleOffset = header.start + (version === 0 ? 12 : 20);
    if (scaleOffset + (version === 0 ? 8 : 12) > header.end) invalid();
    const scale = u32(scaleOffset);
    if (scale === 0) invalid();
    const minf = one(media, "minf"), stbl = one(boxes(minf.start, minf.end), "stbl");
    const samples = boxes(stbl.start, stbl.end),
      stts = one(samples, "stts"),
      stsz = one(samples, "stsz");
    const stsd = one(samples, "stsd");
    if (stsd.start + 8 > stsd.end || u32(stsd.start + 4) !== 1) invalid();
    const descriptions = boxes(stsd.start + 8, stsd.end);
    const description = descriptions[0];
    if (
      descriptions.length !== 1 ||
      !["avc1", "avc3", "hvc1", "hev1", "av01", "vp09"].includes(description.type) ||
      description.end - description.start < 78
    ) invalid();
    const width = view.getUint16(description.start + 24),
      height = view.getUint16(description.start + 26);
    if (!width || !height || width > 8192 || height > 8192) invalid();
    const codec = description.type.startsWith("avc")
      ? "avcC"
      : description.type === "av01"
      ? "av1C"
      : description.type === "vp09"
      ? "vpcC"
      : "hvcC";
    const configuration = one(boxes(description.start + 78, description.end), codec);
    if (configuration.end - configuration.start < 4) invalid();
    if (stts.start + 8 > stts.end || stsz.start + 12 > stsz.end) invalid();
    const entries = u32(stts.start + 4);
    if (!entries || entries > 4096 || stts.start + 8 + entries * 8 !== stts.end) invalid();
    let ticks = 0, frames = 0;
    for (let i = 0; i < entries; i++) {
      const n = u32(stts.start + 8 + i * 8), delta = u32(stts.start + 12 + i * 8);
      if (!n || !delta) invalid();
      ticks += n * delta;
      frames += n;
    }
    if (
      !Number.isSafeInteger(ticks) || frames < 2 || frames > 3600 || u32(stsz.start + 8) !== frames
    ) invalid();
    const fixedSize = u32(stsz.start + 4);
    let sampleBytes = fixedSize * frames;
    if (!fixedSize) {
      if (stsz.start + 12 + frames * 4 !== stsz.end) invalid();
      for (let i = 0; i < frames; i++) sampleBytes += u32(stsz.start + 12 + i * 4);
    }
    if (!sampleBytes || sampleBytes > dataBytes) invalid();
    const duration = Math.round(ticks * 1000 / scale);
    if (duration < VIDEO_MIN_MS || duration > VIDEO_MAX_MS) invalid();
    durations.push(duration);
  }
  if (durations.length !== 1) invalid();
  return durations[0];
}
