import { ApiFault } from "./errors.ts";
import { UUID_PATTERN } from "./http.ts";

export const DEFAULT_NOTIFICATION_PREFERENCES = {
  opportunities: false, proof: true, payments: true, security: true,
  quiet_enabled: false, quiet_start: "22:00", quiet_end: "08:00", timezone: "UTC", area_ids: [] as string[],
};

export function notificationPreferences(value: Record<string, unknown>) {
  const result = { ...DEFAULT_NOTIFICATION_PREFERENCES };
  for (const key of ["opportunities", "proof", "payments", "security", "quiet_enabled"] as const) {
    if (typeof value[key] !== "boolean") throw new ApiFault(400, "INVALID_PREFERENCES", `Invalid ${key}.`);
    result[key] = value[key];
  }
  for (const key of ["quiet_start", "quiet_end"] as const) {
    if (typeof value[key] !== "string" || !/^([01]\d|2[0-3]):[0-5]\d$/.test(value[key])) {
      throw new ApiFault(400, "INVALID_PREFERENCES", "Use a valid quiet-hours time.");
    }
    result[key] = value[key];
  }
  if (typeof value.timezone !== "string" || value.timezone.length > 80) throw new ApiFault(400, "INVALID_TIMEZONE", "Choose a timezone.");
  try { new Intl.DateTimeFormat("en", { timeZone: value.timezone }).format(new Date()); }
  catch { throw new ApiFault(400, "INVALID_TIMEZONE", "Choose a supported timezone."); }
  result.timezone = value.timezone;
  if (!Array.isArray(value.area_ids) || value.area_ids.length > 20 || value.area_ids.some((id) => typeof id !== "string" || !UUID_PATTERN.test(id))) {
    throw new ApiFault(400, "INVALID_AREAS", "Choose at most 20 areas.");
  }
  result.area_ids = [...new Set(value.area_ids as string[])];
  return result;
}

/** Mirrors the program: the entire division remainder belongs to the first selected claim slot. */
export function canonicalPayouts(pool: bigint, mask: number, recipients: { slot: number; wallet: string }[]) {
  if (pool <= 0n || pool > 18446744073709551615n || !Number.isInteger(mask) || mask < 1 || mask > 7) throw new Error("Invalid settlement authority");
  const selected = recipients.filter((r) => (mask & (1 << r.slot)) !== 0).sort((a, b) => a.slot - b.slot);
  const slots = [0, 1, 2].filter((slot) => (mask & (1 << slot)) !== 0);
  if (selected.length !== slots.length || selected.some((r, i) => r.slot !== slots[i] || !r.wallet)) throw new Error("Incomplete settlement recipient authority");
  const base = pool / BigInt(selected.length), remainder = pool % BigInt(selected.length);
  return selected.map((r, i) => ({ ...r, amount_atomic: (base + (i === 0 ? remainder : 0n)).toString() }));
}

export function quietNow(prefs: typeof DEFAULT_NOTIFICATION_PREFERENCES, now: Date): boolean {
  if (!prefs.quiet_enabled || prefs.quiet_start === prefs.quiet_end) return false;
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: prefs.timezone, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(now);
  const time = `${parts.find((p) => p.type === "hour")?.value}:${parts.find((p) => p.type === "minute")?.value}`;
  return prefs.quiet_start < prefs.quiet_end
    ? time >= prefs.quiet_start && time < prefs.quiet_end
    : time >= prefs.quiet_start || time < prefs.quiet_end;
}

/** Strip ancillary PNG chunks (including EXIF/text) and reject animated/oversized avatar data. */
export function sanitizeAvatarPng(bytes: Uint8Array): Uint8Array {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (bytes.length < 45 || bytes.length > 524288 || !signature.every((b, i) => bytes[i] === b)) throw new ApiFault(400, "INVALID_AVATAR", "Choose a PNG image under 512 KB.");
  const chunks: Uint8Array[] = [bytes.slice(0, 8)];
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let offset = 8, ended = false, hasPixels = false, headers = 0;
  while (offset + 12 <= bytes.length) {
    const length = view.getUint32(offset), end = offset + 12 + length;
    if (end > bytes.length) throw new ApiFault(400, "INVALID_AVATAR", "The image is incomplete.");
    const type = String.fromCharCode(...bytes.slice(offset + 4, offset + 8));
    if (type === "acTL") throw new ApiFault(400, "INVALID_AVATAR", "Choose a still image.");
    if (type === "IHDR") {
      const w = view.getUint32(offset + 8), h = view.getUint32(offset + 12);
      if (++headers !== 1 || offset !== 8 || length !== 13 || w < 1 || h < 1 || w > 512 || h > 512) throw new ApiFault(400, "INVALID_AVATAR", "Use an image up to 512 × 512 pixels.");
    }
    if (type === "IDAT") hasPixels = true;
    if (["IHDR", "PLTE", "tRNS", "IDAT", "IEND"].includes(type)) chunks.push(bytes.slice(offset, end));
    if (type === "IEND") { ended = true; break; }
    offset = end;
  }
  if (!ended || !hasPixels || headers !== 1) throw new ApiFault(400, "INVALID_AVATAR", "Choose a valid PNG image.");
  const result = new Uint8Array(chunks.reduce((n, b) => n + b.length, 0));
  let write = 0; for (const chunk of chunks) { result.set(chunk, write); write += chunk.length; }
  return result;
}
