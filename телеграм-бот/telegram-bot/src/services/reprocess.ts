import sharp from "sharp";
import { detectDocumentQuad } from "./document-detect.js";
import { logger } from "../utils/logger.js";
import {
  warpQuadToRect,
  outputSizeForQuad,
  type RawImage,
} from "../utils/perspective.js";

/**
 * Receipt reprocessing pipeline (Variant A).
 *
 * For each uploaded page:
 *   1. decode with sharp (auto-rotate by EXIF), downscale very large photos to keep the
 *      pure-JS warp affordable, and read raw RGBA pixels;
 *   2. ask Gemini for the document's four corners (falls back to the full image);
 *   3. warp the detected quadrilateral into an upright rectangle;
 *   4. re-encode as JPEG.
 *
 * The result is a per-page JPEG buffer. If any step fails for a page, that page falls back
 * to its original bytes so a single bad page never blocks the whole receipt.
 */

/** Cap the longest edge to bound the O(w*h) pure-JS warp cost while keeping receipts legible. */
const MAX_EDGE = 2000;
const JPEG_QUALITY = 85;

export interface ReprocessedPage {
  /** JPEG bytes for the page (either warped or the original fallback). */
  buffer: Buffer;
  mimeType: "image/jpeg";
  /** True when the page was successfully warped, false when it fell back to the original. */
  warped: boolean;
}

/**
 * Reprocesses a single page image buffer into an upright JPEG.
 * Never throws: on failure returns the original buffer with warped=false.
 */
export async function reprocessPage(
  input: Buffer,
  inputMimeType: string,
): Promise<ReprocessedPage> {
  try {
    // 1. Normalize orientation and bound the size, then read raw RGBA pixels.
    const pipeline = sharp(input).rotate().resize({
      width: MAX_EDGE,
      height: MAX_EDGE,
      fit: "inside",
      withoutEnlargement: true,
    });

    const { data, info } = await pipeline
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true });

    const src: RawImage = {
      data,
      width: info.width,
      height: info.height,
      channels: info.channels,
    };

    // 2. Detect the document quad (Gemini). We hand the detector a JPEG snapshot at the
    //    same resolution so its normalized coordinates line up with `src`.
    const detectJpeg = await sharp(data, {
      raw: { width: info.width, height: info.height, channels: info.channels },
    })
      .jpeg({ quality: 80 })
      .toBuffer();

    const { quad, detected } = await detectDocumentQuad(
      detectJpeg,
      "image/jpeg",
      info.width,
      info.height,
    );

    // 3. Warp the quad into a straight rectangle.
    const { width: outW, height: outH } = outputSizeForQuad(quad);
    const warped = warpQuadToRect(src, quad, outW, outH);

    // 4. Re-encode as JPEG (drop the alpha channel over a white background).
    const buffer = await sharp(Buffer.from(warped.data), {
      raw: {
        width: warped.width,
        height: warped.height,
        channels: warped.channels as 1 | 2 | 3 | 4,
      },
    })
      .flatten({ background: "#ffffff" })
      .jpeg({ quality: JPEG_QUALITY })
      .toBuffer();

    return { buffer, mimeType: "image/jpeg", warped: detected };
  } catch (err: any) {
    logger.warn("reprocessPage failed, using original page", { error: err?.message });
    return { buffer: input, mimeType: "image/jpeg", warped: false };
  }
}

/**
 * Reprocesses all pages of a receipt. Preserves page order. Never throws — each page
 * independently falls back to its original bytes on failure.
 */
export async function reprocessPages(
  pages: Array<{ buffer: Buffer; mimeType: string }>,
): Promise<ReprocessedPage[]> {
  const results: ReprocessedPage[] = [];
  for (const page of pages) {
    results.push(await reprocessPage(page.buffer, page.mimeType));
  }
  return results;
}
