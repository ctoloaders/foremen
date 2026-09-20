/**
 * Geometry helpers for receipt document detection and cropping.
 *
 * The bot detects the four corners of a receipt (via Gemini) and then CROPS the original
 * photo to the axis-aligned bounding box of those corners — no perspective warp, no resizing,
 * no DPI change. These are pure functions so they can be unit/property tested in isolation.
 */

export interface Point {
  x: number;
  y: number;
}

/** Four corners of a quadrilateral in a fixed order: TL, TR, BR, BL. */
export interface Quad {
  topLeft: Point;
  topRight: Point;
  bottomRight: Point;
  bottomLeft: Point;
}

/** An axis-aligned integer crop rectangle within an image. */
export interface CropRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * Computes the axis-aligned bounding box of a quad, clamped to the image bounds and rounded
 * to integer pixels. Used to CROP the receipt out of the photo without any perspective warp,
 * resizing, or DPI change — the original pixels inside the box are preserved as-is.
 * Returns null if the resulting rectangle is degenerate (zero width or height).
 */
export function quadBoundingBox(quad: Quad, width: number, height: number): CropRect | null {
  const xs = [quad.topLeft.x, quad.topRight.x, quad.bottomRight.x, quad.bottomLeft.x];
  const ys = [quad.topLeft.y, quad.topRight.y, quad.bottomRight.y, quad.bottomLeft.y];

  const left = Math.max(0, Math.floor(Math.min(...xs)));
  const top = Math.max(0, Math.floor(Math.min(...ys)));
  const right = Math.min(width, Math.ceil(Math.max(...xs)));
  const bottom = Math.min(height, Math.ceil(Math.max(...ys)));

  const w = right - left;
  const h = bottom - top;
  if (w < 1 || h < 1) return null;

  return { left, top, width: w, height: h };
}

/**
 * Orders four arbitrary points into a consistent TL, TR, BR, BL quad.
 * TL has the smallest x+y sum, BR the largest; TR has the smallest y-x, BL the largest.
 * This is robust to the order corners arrive in from a detector.
 */
export function orderCorners(points: Point[]): Quad | null {
  if (points.length !== 4) return null;

  const bySum = [...points].sort((a, b) => a.x + a.y - (b.x + b.y));
  const topLeft = bySum[0];
  const bottomRight = bySum[3];

  const byDiff = [...points].sort((a, b) => a.y - a.x - (b.y - b.x));
  const topRight = byDiff[0];
  const bottomLeft = byDiff[3];

  // Guard against a degenerate assignment where the same point is chosen twice.
  const chosen = new Set([topLeft, topRight, bottomRight, bottomLeft]);
  if (chosen.size !== 4) return null;

  return { topLeft, topRight, bottomRight, bottomLeft };
}

/**
 * Builds an SVG document (sized to the image) containing a single white-filled polygon for
 * the receipt quad on a transparent background. Used as an alpha mask: compositing it over the
 * original with a "dest-in" blend keeps only the pixels inside the quad and makes everything
 * outside transparent (which is then flattened to white). The receipt pixels are untouched.
 */
export function quadMaskSvg(quad: Quad, width: number, height: number): string {
  const pts = [quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft]
    .map((p) => `${p.x.toFixed(2)},${p.y.toFixed(2)}`)
    .join(" ");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><polygon points="${pts}" fill="#ffffff"/></svg>`;
}

/** The full-image quad (used as the fallback when corner detection fails). */
export function fullImageQuad(width: number, height: number): Quad {
  return {
    topLeft: { x: 0, y: 0 },
    topRight: { x: width - 1, y: 0 },
    bottomRight: { x: width - 1, y: height - 1 },
    bottomLeft: { x: 0, y: height - 1 },
  };
}
