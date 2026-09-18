import { describe, it, expect } from "bun:test";
import fc from "fast-check";
import {
  computeHomography,
  applyHomography,
  warpQuadToRect,
  outputSizeForQuad,
  orderCorners,
  fullImageQuad,
  type Point,
  type Quad,
  type RawImage,
} from "./perspective.js";

/**
 * Property: a homography that maps src corners to dst corners actually sends each
 * src[i] to dst[i] (within floating-point tolerance).
 */
describe("computeHomography / applyHomography", () => {
  it("maps each source corner onto its destination corner", () => {
    fc.assert(
      fc.property(
        // Four reasonably-spread source points forming a convex quad.
        fc.record({
          w: fc.integer({ min: 50, max: 1000 }),
          h: fc.integer({ min: 50, max: 1000 }),
          jitter: fc.integer({ min: 0, max: 20 }),
        }),
        ({ w, h, jitter }) => {
          const src: Point[] = [
            { x: jitter, y: jitter },
            { x: w - jitter, y: jitter + 3 },
            { x: w - jitter - 5, y: h - jitter },
            { x: jitter + 2, y: h - jitter - 4 },
          ];
          const dst: Point[] = [
            { x: 0, y: 0 },
            { x: w, y: 0 },
            { x: w, y: h },
            { x: 0, y: h },
          ];
          const H = computeHomography(src, dst);
          expect(H).not.toBeNull();
          for (let i = 0; i < 4; i++) {
            const mapped = applyHomography(H!, src[i])!;
            expect(Math.abs(mapped.x - dst[i].x)).toBeLessThan(1e-3);
            expect(Math.abs(mapped.y - dst[i].y)).toBeLessThan(1e-3);
          }
        },
      ),
      { numRuns: 100 },
    );
  });

  it("returns null for a degenerate (collinear) configuration", () => {
    const src: Point[] = [
      { x: 0, y: 0 },
      { x: 0, y: 0 },
      { x: 0, y: 0 },
      { x: 0, y: 0 },
    ];
    const dst: Point[] = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
      { x: 10, y: 10 },
      { x: 0, y: 10 },
    ];
    expect(computeHomography(src, dst)).toBeNull();
  });
});

describe("outputSizeForQuad", () => {
  it("returns integer dimensions >= 1", () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 1, max: 4000 }),
        fc.integer({ min: 1, max: 4000 }),
        (w, h) => {
          const quad = fullImageQuad(w, h);
          const size = outputSizeForQuad(quad);
          expect(Number.isInteger(size.width)).toBe(true);
          expect(Number.isInteger(size.height)).toBe(true);
          expect(size.width).toBeGreaterThanOrEqual(1);
          expect(size.height).toBeGreaterThanOrEqual(1);
        },
      ),
      { numRuns: 100 },
    );
  });
});

describe("orderCorners", () => {
  it("orders any permutation of a rectangle's corners into TL/TR/BR/BL", () => {
    const rect: Point[] = [
      { x: 0, y: 0 }, // TL
      { x: 100, y: 0 }, // TR
      { x: 100, y: 60 }, // BR
      { x: 0, y: 60 }, // BL
    ];
    fc.assert(
      fc.property(
        fc.shuffledSubarray(rect, { minLength: 4, maxLength: 4 }),
        (perm) => {
          const quad = orderCorners(perm);
          expect(quad).not.toBeNull();
          expect(quad!.topLeft).toEqual({ x: 0, y: 0 });
          expect(quad!.topRight).toEqual({ x: 100, y: 0 });
          expect(quad!.bottomRight).toEqual({ x: 100, y: 60 });
          expect(quad!.bottomLeft).toEqual({ x: 0, y: 60 });
        },
      ),
      { numRuns: 50 },
    );
  });

  it("returns null when not given exactly four points", () => {
    expect(orderCorners([{ x: 0, y: 0 }])).toBeNull();
    expect(orderCorners([])).toBeNull();
  });
});

/**
 * Builds a solid-colour RGBA image for warp tests.
 */
function solidImage(width: number, height: number, r: number, g: number, b: number): RawImage {
  const data = new Uint8Array(width * height * 4);
  for (let i = 0; i < width * height; i++) {
    data[i * 4] = r;
    data[i * 4 + 1] = g;
    data[i * 4 + 2] = b;
    data[i * 4 + 3] = 255;
  }
  return { data, width, height, channels: 4 };
}

describe("warpQuadToRect", () => {
  it("produces an image of exactly the requested output size", () => {
    const src = solidImage(40, 30, 10, 20, 30);
    const quad: Quad = fullImageQuad(40, 30);
    const out = warpQuadToRect(src, quad, 20, 15);
    expect(out.width).toBe(20);
    expect(out.height).toBe(15);
    expect(out.channels).toBe(4);
    expect(out.data.length).toBe(20 * 15 * 4);
  });

  it("identity warp of a full-image quad reproduces the solid colour", () => {
    const src = solidImage(16, 16, 200, 100, 50);
    const quad: Quad = fullImageQuad(16, 16);
    const out = warpQuadToRect(src, quad, 16, 16);
    // Sample the centre pixel; should equal the source colour.
    const idx = (8 * 16 + 8) * 4;
    expect(out.data[idx]).toBe(200);
    expect(out.data[idx + 1]).toBe(100);
    expect(out.data[idx + 2]).toBe(50);
  });
});
