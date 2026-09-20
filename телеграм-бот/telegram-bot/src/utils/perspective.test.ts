import { describe, it, expect } from "bun:test";
import fc from "fast-check";
import {
  quadBoundingBox,
  quadMaskSvg,
  orderCorners,
  fullImageQuad,
  type Point,
} from "./perspective.js";

describe("quadBoundingBox", () => {
  it("returns the tight integer box of an axis-aligned quad", () => {
    const quad = {
      topLeft: { x: 10, y: 20 },
      topRight: { x: 90, y: 22 },
      bottomRight: { x: 88, y: 130 },
      bottomLeft: { x: 12, y: 128 },
    };
    const rect = quadBoundingBox(quad, 200, 200);
    expect(rect).not.toBeNull();
    expect(rect!.left).toBe(10);
    expect(rect!.top).toBe(20);
    // right = ceil(90) = 90 -> width 80; bottom = ceil(130) = 130 -> height 110
    expect(rect!.width).toBe(80);
    expect(rect!.height).toBe(110);
  });

  it("clamps a quad that overshoots the image bounds", () => {
    const quad = {
      topLeft: { x: -50, y: -30 },
      topRight: { x: 5000, y: 0 },
      bottomRight: { x: 5000, y: 5000 },
      bottomLeft: { x: -50, y: 5000 },
    };
    const rect = quadBoundingBox(quad, 1000, 800);
    expect(rect).not.toBeNull();
    expect(rect!.left).toBe(0);
    expect(rect!.top).toBe(0);
    expect(rect!.width).toBe(1000);
    expect(rect!.height).toBe(800);
  });

  it("stays within image bounds for any quad (property)", () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 10, max: 4000 }),
        fc.integer({ min: 10, max: 4000 }),
        fc.array(fc.record({ x: fc.integer({ min: -1000, max: 5000 }), y: fc.integer({ min: -1000, max: 5000 }) }), {
          minLength: 4,
          maxLength: 4,
        }),
        (w, h, pts) => {
          const quad = {
            topLeft: pts[0],
            topRight: pts[1],
            bottomRight: pts[2],
            bottomLeft: pts[3],
          };
          const rect = quadBoundingBox(quad, w, h);
          if (rect === null) return; // degenerate is allowed
          expect(rect.left).toBeGreaterThanOrEqual(0);
          expect(rect.top).toBeGreaterThanOrEqual(0);
          expect(rect.left + rect.width).toBeLessThanOrEqual(w);
          expect(rect.top + rect.height).toBeLessThanOrEqual(h);
          expect(rect.width).toBeGreaterThanOrEqual(1);
          expect(rect.height).toBeGreaterThanOrEqual(1);
        },
      ),
      { numRuns: 300 },
    );
  });

  it("full-image quad crops to the whole image", () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 2, max: 4000 }),
        fc.integer({ min: 2, max: 4000 }),
        (w, h) => {
          const rect = quadBoundingBox(fullImageQuad(w, h), w, h);
          expect(rect).not.toBeNull();
          expect(rect!.left).toBe(0);
          expect(rect!.top).toBe(0);
          // corners go to width-1/height-1, ceil brings them back to width-1/height-1
          expect(rect!.width).toBe(w - 1);
          expect(rect!.height).toBe(h - 1);
        },
      ),
      { numRuns: 100 },
    );
  });
});

describe("quadMaskSvg", () => {
  it("produces an SVG sized to the image with a polygon of the quad corners", () => {
    const quad = {
      topLeft: { x: 10, y: 20 },
      topRight: { x: 90, y: 22 },
      bottomRight: { x: 88, y: 130 },
      bottomLeft: { x: 12, y: 128 },
    };
    const svg = quadMaskSvg(quad, 200, 300);
    expect(svg).toContain('width="200"');
    expect(svg).toContain('height="300"');
    expect(svg).toContain("<polygon");
    expect(svg).toContain('fill="#ffffff"');
    // All four corners appear in the points attribute.
    expect(svg).toContain("10.00,20.00");
    expect(svg).toContain("90.00,22.00");
    expect(svg).toContain("88.00,130.00");
    expect(svg).toContain("12.00,128.00");
  });
});

describe("orderCorners", () => {
  it("orders any permutation of a rectangle's corners into TL/TR/BR/BL", () => {
    const rect: Point[] = [
      { x: 0, y: 0 },
      { x: 100, y: 0 },
      { x: 100, y: 60 },
      { x: 0, y: 60 },
    ];
    fc.assert(
      fc.property(fc.shuffledSubarray(rect, { minLength: 4, maxLength: 4 }), (perm) => {
        const quad = orderCorners(perm);
        expect(quad).not.toBeNull();
        expect(quad!.topLeft).toEqual({ x: 0, y: 0 });
        expect(quad!.topRight).toEqual({ x: 100, y: 0 });
        expect(quad!.bottomRight).toEqual({ x: 100, y: 60 });
        expect(quad!.bottomLeft).toEqual({ x: 0, y: 60 });
      }),
      { numRuns: 50 },
    );
  });

  it("returns null when not given exactly four points", () => {
    expect(orderCorners([{ x: 0, y: 0 }])).toBeNull();
    expect(orderCorners([])).toBeNull();
  });
});
