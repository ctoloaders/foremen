import { describe, it, expect } from "bun:test";
import sharp from "sharp";
import { quadMaskSvg } from "../utils/perspective.js";

/**
 * Verifies the white-out compositing behaviour used by reprocessPage: pixels inside the quad
 * are preserved, pixels outside are turned white, and the image keeps its original size.
 *
 * The full reprocessPage() call depends on Gemini for corner detection, so it can't run
 * offline; this test exercises the deterministic sharp masking step directly with a known quad.
 */
describe("white-out masking (reprocess core)", () => {
  it("keeps inside-quad pixels, whites out the background, preserves size", async () => {
    const W = 100;
    const H = 100;

    // Solid red source image.
    const src = await sharp({
      create: { width: W, height: H, channels: 3, background: { r: 200, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    // Quad covering the central region only.
    const quad = {
      topLeft: { x: 30, y: 30 },
      topRight: { x: 70, y: 30 },
      bottomRight: { x: 70, y: 70 },
      bottomLeft: { x: 30, y: 70 },
    };
    const mask = Buffer.from(quadMaskSvg(quad, W, H));

    const receiptOnly = await sharp(src)
      .ensureAlpha()
      .composite([{ input: mask, blend: "dest-in" }])
      .png()
      .toBuffer();

    const out = await sharp({
      create: { width: W, height: H, channels: 3, background: "#ffffff" },
    })
      .composite([{ input: receiptOnly, blend: "over" }])
      .jpeg({ quality: 90 })
      .toBuffer();

    const outMeta = await sharp(out).metadata();
    expect(outMeta.width).toBe(W);
    expect(outMeta.height).toBe(H);

    // Read raw pixels to inspect corners vs centre.
    const { data, info } = await sharp(out).raw().toBuffer({ resolveWithObject: true });
    const ch = info.channels;
    const px = (x: number, y: number) => {
      const i = (y * info.width + x) * ch;
      return { r: data[i], g: data[i + 1], b: data[i + 2] };
    };

    // Corner (outside quad) should be white.
    const corner = px(5, 5);
    expect(corner.r).toBeGreaterThan(240);
    expect(corner.g).toBeGreaterThan(240);
    expect(corner.b).toBeGreaterThan(240);

    // Centre (inside quad) should remain red-ish (JPEG tolerance).
    const centre = px(50, 50);
    expect(centre.r).toBeGreaterThan(150);
    expect(centre.g).toBeLessThan(80);
    expect(centre.b).toBeLessThan(80);
  });
});
