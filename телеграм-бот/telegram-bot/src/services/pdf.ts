import { PDFDocument } from "pdf-lib";

/**
 * Assembles receipt page images into a single multi-page PDF.
 *
 * Each input page becomes one PDF page sized to that image's pixel dimensions (at 72 DPI,
 * 1px = 1pt), so pages keep their aspect ratio and no whitespace/cropping is introduced.
 * Only JPEG and PNG inputs are supported (the reprocessing pipeline emits JPEG; PNG is
 * accepted for the original-image fallback path).
 */

export interface PdfPageInput {
  buffer: Buffer;
  mimeType: string;
}

/**
 * Builds a multi-page PDF from the given page images and returns the PDF bytes.
 * Throws if there are no pages or if an image cannot be embedded — callers treat a throw
 * as the signal to fall back to uploading the original images.
 */
export async function buildReceiptPdf(pages: PdfPageInput[]): Promise<Buffer> {
  if (pages.length === 0) {
    throw new Error("buildReceiptPdf: no pages provided");
  }

  const pdf = await PDFDocument.create();

  for (const page of pages) {
    const isPng = page.mimeType === "image/png";
    const image = isPng
      ? await pdf.embedPng(page.buffer)
      : await pdf.embedJpg(page.buffer);

    const pdfPage = pdf.addPage([image.width, image.height]);
    pdfPage.drawImage(image, {
      x: 0,
      y: 0,
      width: image.width,
      height: image.height,
    });
  }

  const bytes = await pdf.save();
  return Buffer.from(bytes);
}
