import sharp from "sharp";
import { detectDocumentQuad } from "./document-detect.js";
import { logger } from "../utils/logger.js";
import { quadMaskSvg } from "../utils/perspective.js";

/**
 * Receipt reprocessing pipeline (white-out background).
 *
 * For each uploaded page:
 *   1. auto-rotate by EXIF (no resizing, no resampling of content);
 *   2. ask Gemini for the document's four corners (falls back to the full image);
 *   3. paint everything OUTSIDE the detected receipt quadrilateral white, keeping the image
 *      at its ORIGINAL size and the receipt pixels untouched.
 *
 * There is deliberately NO crop, NO perspective warp, NO scaling, and NO DPI change: the
 * output has the same dimensions as the (oriented) original; only the background around the
 * receipt is replaced with white. If a page fails at any step it falls back to its original
 * bytes.
 */

export interface ReprocessedPage {
  /** Image bytes for the page (background whited-out, or the original on fallback). */
  buffer: Buffer;
  mimeType: string;
  /** True when the background was successfully whited-out, false when it fell back. */
  processed: boolean;
}

/**
 * Reprocesses a single page image buffer by whiting-out everything around the detected receipt.
 * Never throws: on failure returns the original buffer with processed=false.
 */
export async function reprocessPage(
  input: Buffer,
  inputMimeType: string,
): Promise<ReprocessedPage> {
  try {
    // 1. Normalize orientation only (bake in EXIF rotation; does not resample content).
    const oriented = await sharp(input).rotate().toBuffer();
    const meta = await sharp(oriented).metadata();
    const width = meta.width ?? 0;
    const height = meta.height ?? 0;
    const outMime = meta.format === "png" ? "image/png" : "image/jpeg";
    if (!width || !height) {
      return { buffer: input, mimeType: inputMimeType, processed: false };
    }

    // 2. Detect the document quad (Gemini). Hand it the oriented image so coordinates align.
    const { quad, detected } = await detectDocumentQuad(oriented, outMime, width, height);

    if (!detected) {
      // No usable detection → keep the original (oriented) image untouched.
      return { buffer: oriented, mimeType: outMime, processed: false };
    }

    // 3. Build a mask opaque inside the receipt quad and transparent outside. Keep only the
    //    receipt pixels (dest-in) as a transparent PNG, then composite that over a white canvas
    //    of the SAME size. Everything outside the receipt becomes white; the receipt pixels are
    //    untouched and the dimensions are unchanged.
    const maskSvg = Buffer.from(quadMaskSvg(quad, width, height));

    const receiptOnly = await sharp(oriented)
      .ensureAlpha()
      .composite([{ input: maskSvg, blend: "dest-in" }])
      .png()
      .toBuffer();

    const buffer = await sharp({
      create: { width, height, channels: 3, background: "#ffffff" },
    })
      .composite([{ input: receiptOnly, blend: "over" }])
      .jpeg({ quality: 90 })
      .toBuffer();

    return { buffer, mimeType: "image/jpeg", processed: true };
  } catch (err: any) {
    logger.warn("reprocessPage failed, using original page", { error: err?.message });
    return { buffer: input, mimeType: inputMimeType, processed: false };
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
