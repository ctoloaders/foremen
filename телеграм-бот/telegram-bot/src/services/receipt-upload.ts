import { uploadPhotosToFolder, uploadReceiptPdfToFolder, uploadRawToFolder } from "./drive.js";
import { reprocessPages } from "./reprocess.js";
import { buildReceiptPdf } from "./pdf.js";
import { withRetry } from "../utils/retry.js";
import { config } from "../config.js";
import { logger } from "../utils/logger.js";

/**
 * Uploads a receipt to the centralized shared Drive folder and returns the resulting view link(s).
 *
 * Preferred path (when `config.reprocess.enabled`): reprocess every page (white-out the
 * background around the detected receipt), assemble a single multi-page PDF, and upload it.
 * On ANY failure of that path — reprocessing, PDF assembly, or the PDF Drive upload — it
 * falls back to uploading the original photos as-is (also to the shared folder). A failure of
 * the fallback itself propagates to the caller (so the existing error handling / retry
 * messaging still runs).
 *
 * All receipts now go to the single shared folder `config.google.receiptsPdfFolderId`,
 * regardless of project (the project is recorded as a column in the shared sheet instead).
 *
 * This is the single shared entry point used by both the OCR callback flow
 * (handlers/callback.ts) and the step-by-step text flow (handlers/text.ts).
 */
export async function uploadReceiptArtifact(
  storeName: string,
  sum: number,
  files: Array<{ buffer: Buffer; mimeType: string }>,
  logContext: Record<string, unknown> = {},
): Promise<string[]> {
  const folderId = config.google.receiptsPdfFolderId;

  if (config.reprocess.enabled && files.length > 0) {
    try {
      const pages = await reprocessPages(files);
      const pdfBuffer = await buildReceiptPdf(
        pages.map((p) => ({ buffer: p.buffer, mimeType: p.mimeType })),
      );
      const { link } = await withRetry(() =>
        uploadReceiptPdfToFolder(folderId, storeName, sum, pdfBuffer),
      );

      // For any page where background whiteout FAILED, also save the untouched original with a
      // `_raw` suffix, so the raw source is preserved alongside the (fallback) PDF page.
      const failedPages = pages.filter((p) => !p.processed);
      const multiRaw = failedPages.length > 1;
      let rawIndex = 0;
      for (const p of failedPages) {
        rawIndex += 1;
        try {
          await withRetry(() =>
            uploadRawToFolder(
              folderId,
              storeName,
              sum,
              p.originalBuffer,
              p.originalMimeType,
              multiRaw ? rawIndex : undefined,
            ),
          );
        } catch (rawErr: any) {
          // A raw-copy failure must not break the main save.
          logger.warn("Raw original upload failed", { ...logContext, error: rawErr?.message });
        }
      }

      logger.info("Receipt PDF uploaded", {
        ...logContext,
        pages: pages.length,
        processedPages: pages.filter((p) => p.processed).length,
        rawCopies: failedPages.length,
      });
      return [link];
    } catch (err: any) {
      logger.warn("Receipt PDF path failed, falling back to original photos", {
        ...logContext,
        error: err?.message,
      });
    }
  }

  // Fallback: upload the original photos as-is to the same shared folder.
  const result = await withRetry(() => uploadPhotosToFolder(folderId, storeName, sum, files));
  return result.links;
}
