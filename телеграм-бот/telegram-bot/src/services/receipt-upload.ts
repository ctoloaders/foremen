import { uploadPhotosToFolder, uploadReceiptPdfToFolder, uploadRawToFolder } from "./drive.js";
import { reprocessPages } from "./reprocess.js";
import { buildReceiptPdf } from "./pdf.js";
import { withRetry } from "../utils/retry.js";
import { config } from "../config.js";
import { logger } from "../utils/logger.js";

export interface ReceiptUploadResult {
  /** Primary artifact link(s): the processed PDF, or the original photos when PDF is disabled. */
  links: string[];
  /** Link(s) to the ALWAYS-saved untouched original photo(s), newest page order. */
  originalLinks: string[];
}

/**
 * Uploads a receipt to the centralized shared Drive folder and returns the resulting view links.
 *
 * The ORIGINAL photo(s) are ALWAYS uploaded (every page, untouched, with a `_raw` suffix) and
 * returned in `originalLinks`, so the raw source is preserved for every receipt regardless of
 * the processing outcome.
 *
 * Primary artifact (`links`):
 *  - When `config.reprocess.enabled`: every page is reprocessed (background whited-out),
 *    assembled into ONE multi-page PDF, and uploaded. On ANY failure of that path the primary
 *    link falls back to the uploaded originals.
 *  - When reprocessing is disabled: the originals themselves are the primary artifact.
 *
 * All files go to the single shared folder `config.google.receiptsPdfFolderId`.
 *
 * This is the single shared entry point used by both the OCR callback flow
 * (handlers/callback.ts) and the step-by-step text flow (handlers/text.ts).
 */
export async function uploadReceiptArtifact(
  storeName: string,
  sum: number,
  files: Array<{ buffer: Buffer; mimeType: string }>,
  logContext: Record<string, unknown> = {},
): Promise<ReceiptUploadResult> {
  const folderId = config.google.receiptsPdfFolderId;

  // 1. ALWAYS save the untouched original(s) first, so the raw source is preserved for every
  //    receipt. Each page is uploaded with a `_raw` suffix. A failure here must not block the
  //    main save — we log and continue with whatever originals succeeded.
  const originalLinks: string[] = [];
  const multiRaw = files.length > 1;
  for (let i = 0; i < files.length; i++) {
    try {
      const { link } = await withRetry(() =>
        uploadRawToFolder(
          folderId,
          storeName,
          sum,
          files[i].buffer,
          files[i].mimeType,
          multiRaw ? i + 1 : undefined,
        ),
      );
      originalLinks.push(link);
    } catch (rawErr: any) {
      logger.warn("Raw original upload failed", { ...logContext, page: i + 1, error: rawErr?.message });
    }
  }

  // 2. Primary artifact: reprocessed PDF (preferred) or original photos (fallback / flag off).
  if (config.reprocess.enabled && files.length > 0) {
    try {
      const pages = await reprocessPages(files);
      const pdfBuffer = await buildReceiptPdf(
        pages.map((p) => ({ buffer: p.buffer, mimeType: p.mimeType })),
      );
      const { link } = await withRetry(() =>
        uploadReceiptPdfToFolder(folderId, storeName, sum, pdfBuffer),
      );
      logger.info("Receipt PDF uploaded", {
        ...logContext,
        pages: pages.length,
        processedPages: pages.filter((p) => p.processed).length,
        originals: originalLinks.length,
      });
      return { links: [link], originalLinks };
    } catch (err: any) {
      logger.warn("Receipt PDF path failed, falling back to original photos", {
        ...logContext,
        error: err?.message,
      });
    }
  }

  // Fallback / PDF disabled: the originals are the primary artifact too.
  const result = await withRetry(() => uploadPhotosToFolder(folderId, storeName, sum, files));
  return { links: result.links, originalLinks };
}
