import { uploadPhotos, uploadReceiptPdf } from "./drive.js";
import { reprocessPages } from "./reprocess.js";
import { buildReceiptPdf } from "./pdf.js";
import { withRetry } from "../utils/retry.js";
import { config } from "../config.js";
import { logger } from "../utils/logger.js";

/**
 * Uploads a receipt to Drive and returns the resulting view link(s).
 *
 * Preferred path (when `config.reprocess.enabled`): reprocess every page (white-out the
 * background around the detected receipt), assemble a single multi-page PDF, and upload it.
 * On ANY failure of that path — reprocessing, PDF assembly, or the PDF Drive upload — it
 * falls back to uploading the original photos as-is. A failure of the fallback itself
 * propagates to the caller (so the existing error handling / retry messaging still runs).
 *
 * This is the single shared entry point used by both the OCR callback flow
 * (handlers/callback.ts) and the step-by-step text flow (handlers/text.ts).
 */
export async function uploadReceiptArtifact(
  driveUrl: string,
  storeName: string,
  sum: number,
  files: Array<{ buffer: Buffer; mimeType: string }>,
  logContext: Record<string, unknown> = {},
): Promise<string[]> {
  if (config.reprocess.enabled && files.length > 0) {
    try {
      const pages = await reprocessPages(files);
      const pdfBuffer = await buildReceiptPdf(
        pages.map((p) => ({ buffer: p.buffer, mimeType: p.mimeType })),
      );
      const { link } = await withRetry(() =>
        uploadReceiptPdf(driveUrl, storeName, sum, pdfBuffer),
      );
      logger.info("Receipt PDF uploaded", {
        ...logContext,
        pages: pages.length,
        processedPages: pages.filter((p) => p.processed).length,
      });
      return [link];
    } catch (err: any) {
      logger.warn("Receipt PDF path failed, falling back to original photos", {
        ...logContext,
        error: err?.message,
      });
    }
  }

  // Fallback: upload the original photos as-is.
  const result = await withRetry(() => uploadPhotos(driveUrl, storeName, sum, files));
  return result.links;
}
