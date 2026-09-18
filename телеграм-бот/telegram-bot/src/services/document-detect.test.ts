import { describe, it, expect } from "bun:test";
import fc from "fast-check";
import {
  parseCornerResponse,
  normalizedToPixelQuad,
  quadAreaFraction,
} from "./document-detect.js";
import { fullImageQuad } from "../utils/perspective.js";

describe("parseCornerResponse", () => {
  it("parses a valid normalized-corner JSON object", () => {
    const json = JSON.stringify({
      top_left: { x: 0.1, y: 0.08 },
      top_right: { x: 0.9, y: 0.12 },
      bottom_right: { x: 0.88, y: 0.95 },
      bottom_left: { x: 0.08, y: 0.92 },
    });
    const result = parseCornerResponse(json);
    expect(result).not.toBeNull();
    expect(result!.topLeft.x).toBeCloseTo(0.1, 5);
    expect(result!.bottomRight.y).toBeCloseTo(0.95, 5);
  });

  it("extracts JSON wrapped in a markdown fence", () => {
    const json = JSON.stringify({
      top_left: { x: 0, y: 0 },
      top_right: { x: 1, y: 0 },
      bottom_right: { x: 1, y: 1 },
      bottom_left: { x: 0, y: 1 },
    });
    const result = parseCornerResponse("```json\n" + json + "\n```");
    expect(result).not.toBeNull();
  });

  it("returns null when a corner is missing", () => {
    const json = JSON.stringify({
      top_left: { x: 0.1, y: 0.1 },
      top_right: { x: 0.9, y: 0.1 },
      bottom_right: { x: 0.9, y: 0.9 },
    });
    expect(parseCornerResponse(json)).toBeNull();
  });

  it("returns null for out-of-range coordinates", () => {
    const json = JSON.stringify({
      top_left: { x: -2, y: 0.1 },
      top_right: { x: 0.9, y: 0.1 },
      bottom_right: { x: 0.9, y: 0.9 },
      bottom_left: { x: 0.1, y: 0.9 },
    });
    expect(parseCornerResponse(json)).toBeNull();
  });

  it("returns null for non-JSON text", () => {
    fc.assert(
      fc.property(
        fc.string().filter((s) => !s.includes("{") && !s.includes("}")),
        (text) => {
          expect(parseCornerResponse(text)).toBeNull();
        },
      ),
      { numRuns: 100 },
    );
  });

  it("clamps small overshoot into [0,1]", () => {
    const json = JSON.stringify({
      top_left: { x: -0.02, y: -0.03 },
      top_right: { x: 1.02, y: 0 },
      bottom_right: { x: 1.01, y: 1.02 },
      bottom_left: { x: 0, y: 1.0 },
    });
    const result = parseCornerResponse(json);
    expect(result).not.toBeNull();
    expect(result!.topLeft.x).toBe(0);
    expect(result!.topLeft.y).toBe(0);
    expect(result!.topRight.x).toBe(1);
    expect(result!.bottomRight.y).toBe(1);
  });
});

describe("normalizedToPixelQuad", () => {
  it("scales normalized coords to pixel space and orders corners", () => {
    const quad = normalizedToPixelQuad(
      {
        topLeft: { x: 0, y: 0 },
        topRight: { x: 1, y: 0 },
        bottomRight: { x: 1, y: 1 },
        bottomLeft: { x: 0, y: 1 },
      },
      101,
      51,
    );
    expect(quad).not.toBeNull();
    expect(quad!.topLeft).toEqual({ x: 0, y: 0 });
    expect(quad!.topRight).toEqual({ x: 100, y: 0 });
    expect(quad!.bottomRight).toEqual({ x: 100, y: 50 });
    expect(quad!.bottomLeft).toEqual({ x: 0, y: 50 });
  });
});

describe("quadAreaFraction", () => {
  it("full-image quad covers ~100% of the image", () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 10, max: 2000 }),
        fc.integer({ min: 10, max: 2000 }),
        (w, h) => {
          const frac = quadAreaFraction(fullImageQuad(w, h), w, h);
          expect(frac).toBeGreaterThan(0.99);
          expect(frac).toBeLessThanOrEqual(1.0001);
        },
      ),
      { numRuns: 100 },
    );
  });

  it("a small centred quad reports a small fraction", () => {
    const w = 1000;
    const h = 1000;
    const small = {
      topLeft: { x: 400, y: 400 },
      topRight: { x: 500, y: 400 },
      bottomRight: { x: 500, y: 500 },
      bottomLeft: { x: 400, y: 500 },
    };
    const frac = quadAreaFraction(small, w, h);
    expect(frac).toBeLessThan(0.02);
  });
});
