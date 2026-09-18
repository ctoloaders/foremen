import { describe, it, expect } from "bun:test";
import sharp from "sharp";
import { PDFDocument } from "pdf-lib";
import { buildReceiptPdf } from "./pdf.js";

/** Creates a solid-colour JPEG of the given size for use as a fake receipt page. */
async function makeJpeg(width: number, height: number): Promise<Buffer> {
  return sharp({
    create: {
      width,
      height,
      channels: 3,
      background: { r: 240, g: 240, b: 240 },
    },
  })
    .jpeg()
    .toBuffer();
}

describe("buildReceiptPdf", () => {
  it("throws when given no pages", async () => {
    await expect(buildReceiptPdf([])).rejects.toThrow();
  });

  it("produces a single-page PDF for one image", async () => {
    const jpg = await makeJpeg(300, 400);
    const pdfBytes = await buildReceiptPdf([{ buffer: jpg, mimeType: "image/jpeg" }]);

    // Valid PDF header.
    expect(pdfBytes.subarray(0, 5).toString("latin1")).toBe("%PDF-");

    const doc = await PDFDocument.load(pdfBytes);
    expect(doc.getPageCount()).toBe(1);
    const page = doc.getPage(0);
    // Page dimensions equal image pixel dimensions (72 DPI: 1px = 1pt).
    expect(Math.round(page.getWidth())).toBe(300);
    expect(Math.round(page.getHeight())).toBe(400);
  });

  it("produces one PDF page per input image, preserving order/count", async () => {
    const sizes: Array<[number, number]> = [
      [200, 300],
      [400, 200],
      [150, 150],
    ];
    const pages = [];
    for (const [w, h] of sizes) {
      pages.push({ buffer: await makeJpeg(w, h), mimeType: "image/jpeg" });
    }

    const pdfBytes = await buildReceiptPdf(pages);
    const doc = await PDFDocument.load(pdfBytes);
    expect(doc.getPageCount()).toBe(3);

    for (let i = 0; i < sizes.length; i++) {
      const page = doc.getPage(i);
      expect(Math.round(page.getWidth())).toBe(sizes[i][0]);
      expect(Math.round(page.getHeight())).toBe(sizes[i][1]);
    }
  });

  it("embeds a PNG page as well", async () => {
    const png = await sharp({
      create: { width: 120, height: 90, channels: 4, background: { r: 255, g: 255, b: 255, alpha: 1 } },
    })
      .png()
      .toBuffer();

    const pdfBytes = await buildReceiptPdf([{ buffer: png, mimeType: "image/png" }]);
    const doc = await PDFDocument.load(pdfBytes);
    expect(doc.getPageCount()).toBe(1);
  });
});
