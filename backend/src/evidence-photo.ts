import { ApiFault } from "./errors.ts";

function invalid(): never {
  throw new ApiFault(
    400,
    "EVIDENCE_MEDIA_INVALID",
    "The photo is incomplete or is not a supported JPEG. Retake the photo.",
  );
}

// Boundaries from ITU-T T.81, Annex B. This is a structural check, not a pixel decoder
// or a judgment about the scene. Walk scans as well as headers so a truncated upload
// cannot pass by carrying only a JPEG prefix. No allocation depends on image dimensions.
export function inspectJpeg(bytes: Uint8Array): void {
  if (bytes[0] !== 0xff || bytes[1] !== 0xd8) invalid();
  let offset = 2;
  let frame = false;
  let scanned = false;
  let quantization = false;
  let huffman = false;
  const components = new Set<number>();
  const u16 = (at: number) => bytes[at] * 256 + bytes[at + 1];

  while (offset < bytes.length) {
    if (bytes[offset++] !== 0xff) invalid();
    while (bytes[offset] === 0xff) offset++;
    if (offset >= bytes.length) invalid();
    const marker = bytes[offset++];
    if (marker === 0xd9) {
      if (!frame || !scanned) invalid();
      // Some Android cameras append a secondary gain-map image after the primary EOI.
      return;
    }
    if (
      marker === 0xd8 || marker === 0x00 || marker < 0xc0 ||
      (marker >= 0xd0 && marker <= 0xd7)
    ) invalid();
    if (offset + 2 > bytes.length) invalid();
    const length = u16(offset);
    const end = offset + length;
    if (length < 2 || end > bytes.length) invalid();

    if (marker === 0xc0 || marker === 0xc1 || marker === 0xc2) {
      if (frame || length < 11) invalid();
      const count = bytes[offset + 7];
      if (
        count < 1 || count > 4 || length !== 8 + 3 * count ||
        u16(offset + 3) === 0 || u16(offset + 5) === 0
      ) invalid();
      for (let i = 0; i < count; i++) {
        const id = bytes[offset + 8 + 3 * i];
        if (components.has(id)) invalid();
        components.add(id);
      }
      frame = true;
    } else if (marker === 0xdb) {
      if (length < 67) invalid();
      quantization = true;
    } else if (marker === 0xc4) {
      if (length < 20) invalid();
      huffman = true;
    } else if (marker === 0xda) {
      if (!frame || !quantization || !huffman || length < 8) invalid();
      const count = bytes[offset + 2];
      if (count < 1 || count > components.size || length !== 6 + 2 * count) invalid();
      const scanComponents = new Set<number>();
      for (let i = 0; i < count; i++) {
        const id = bytes[offset + 3 + 2 * i];
        if (!components.has(id) || scanComponents.has(id)) invalid();
        scanComponents.add(id);
      }
      offset = end;
      let dataBytes = 0;
      while (offset < bytes.length) {
        if (bytes[offset] !== 0xff) {
          dataBytes++;
          offset++;
          continue;
        }
        const start = offset++;
        while (bytes[offset] === 0xff) offset++;
        if (offset >= bytes.length) invalid();
        const next = bytes[offset++];
        if (next === 0x00) {
          dataBytes++;
        } else if (next < 0xd0 || next > 0xd7) {
          offset = start;
          break;
        }
      }
      if (dataBytes === 0) invalid();
      scanned = true;
      continue;
    }
    offset = end;
  }
  invalid();
}
