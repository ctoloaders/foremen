/**
 * Perspective (projective) transform utilities operating on raw RGBA pixel data.
 *
 * These are pure functions with no I/O so they can be unit/property tested in isolation.
 * The reprocessing service (services/reprocess.ts) wires sharp decode/encode around them.
 *
 * A document photographed at an angle is a quadrilateral in the source image. To turn it
 * into a clean rectangle we:
 *   1. compute the homography H that maps the *destination* rectangle corners back to the
 *      *source* quadrilateral corners (inverse mapping), then
 *   2. for every destination pixel, project it through H into source space and sample the
 *      source with bilinear interpolation.
 *
 * Using the inverse mapping (dest -> src) avoids holes in the output that a forward map
 * would produce.
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

export type Matrix3x3 = [number, number, number, number, number, number, number, number, number];

/**
 * Solves the linear system A x = b for a square matrix A (n x n) using Gaussian
 * elimination with partial pivoting. Returns null if the system is singular.
 * `a` is row-major and is mutated in place; `b` is mutated in place.
 */
export function solveLinearSystem(a: number[][], b: number[]): number[] | null {
  const n = b.length;

  for (let col = 0; col < n; col++) {
    // Partial pivot: find the row with the largest absolute value in this column.
    let pivotRow = col;
    let maxAbs = Math.abs(a[col][col]);
    for (let r = col + 1; r < n; r++) {
      const v = Math.abs(a[r][col]);
      if (v > maxAbs) {
        maxAbs = v;
        pivotRow = r;
      }
    }
    if (maxAbs < 1e-12) return null; // singular

    if (pivotRow !== col) {
      [a[col], a[pivotRow]] = [a[pivotRow], a[col]];
      [b[col], b[pivotRow]] = [b[pivotRow], b[col]];
    }

    // Eliminate below.
    for (let r = col + 1; r < n; r++) {
      const factor = a[r][col] / a[col][col];
      if (factor === 0) continue;
      for (let c = col; c < n; c++) {
        a[r][c] -= factor * a[col][c];
      }
      b[r] -= factor * b[col];
    }
  }

  // Back-substitution.
  const x = new Array<number>(n).fill(0);
  for (let row = n - 1; row >= 0; row--) {
    let sum = b[row];
    for (let c = row + 1; c < n; c++) {
      sum -= a[row][c] * x[c];
    }
    x[row] = sum / a[row][row];
  }
  return x;
}

/**
 * Computes the 3x3 homography H mapping four source points to four destination points,
 * such that dst ~ H * src (in homogeneous coordinates), with H[8] fixed to 1.
 * Returns null if the point configuration is degenerate.
 *
 * Order of both arrays must correspond: src[i] maps to dst[i].
 */
export function computeHomography(src: Point[], dst: Point[]): Matrix3x3 | null {
  if (src.length !== 4 || dst.length !== 4) return null;

  // Each correspondence contributes two rows to an 8x8 system solving for
  // h = [h0..h7] with h8 = 1.
  const a: number[][] = [];
  const b: number[] = [];

  for (let i = 0; i < 4; i++) {
    const { x, y } = src[i];
    const { x: X, y: Y } = dst[i];
    a.push([x, y, 1, 0, 0, 0, -X * x, -X * y]);
    b.push(X);
    a.push([0, 0, 0, x, y, 1, -Y * x, -Y * y]);
    b.push(Y);
  }

  const h = solveLinearSystem(a, b);
  if (!h) return null;

  return [h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1];
}

/** Applies a 3x3 homography to a point, returning the de-homogenised result, or null if w≈0. */
export function applyHomography(h: Matrix3x3, p: Point): Point | null {
  const x = h[0] * p.x + h[1] * p.y + h[2];
  const y = h[3] * p.x + h[4] * p.y + h[5];
  const w = h[6] * p.x + h[7] * p.y + h[8];
  if (Math.abs(w) < 1e-12) return null;
  return { x: x / w, y: y / w };
}

/** Euclidean distance between two points. */
export function distance(a: Point, b: Point): number {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  return Math.sqrt(dx * dx + dy * dy);
}

/**
 * Derives the output rectangle size for a warped quad. Width is the max of the two
 * horizontal edge lengths, height the max of the two vertical edges. Result is clamped
 * to at least 1x1 and rounded to integers.
 */
export function outputSizeForQuad(quad: Quad): { width: number; height: number } {
  const widthTop = distance(quad.topLeft, quad.topRight);
  const widthBottom = distance(quad.bottomLeft, quad.bottomRight);
  const heightLeft = distance(quad.topLeft, quad.bottomLeft);
  const heightRight = distance(quad.topRight, quad.bottomRight);

  const width = Math.max(1, Math.round(Math.max(widthTop, widthBottom)));
  const height = Math.max(1, Math.round(Math.max(heightLeft, heightRight)));
  return { width, height };
}

export interface RawImage {
  data: Uint8Array | Buffer;
  width: number;
  height: number;
  /** Number of channels per pixel; expected 4 (RGBA). */
  channels: number;
}

/**
 * Bilinearly samples a raw image at fractional coordinates (fx, fy). Out-of-bounds
 * coordinates are clamped to the edge. Writes the sampled channels into `out` at `outOffset`.
 */
function sampleBilinear(
  img: RawImage,
  fx: number,
  fy: number,
  out: Uint8Array,
  outOffset: number,
): void {
  const { data, width, height, channels } = img;

  const clampedX = Math.min(Math.max(fx, 0), width - 1);
  const clampedY = Math.min(Math.max(fy, 0), height - 1);

  const x0 = Math.floor(clampedX);
  const y0 = Math.floor(clampedY);
  const x1 = Math.min(x0 + 1, width - 1);
  const y1 = Math.min(y0 + 1, height - 1);

  const dx = clampedX - x0;
  const dy = clampedY - y0;

  const i00 = (y0 * width + x0) * channels;
  const i10 = (y0 * width + x1) * channels;
  const i01 = (y1 * width + x0) * channels;
  const i11 = (y1 * width + x1) * channels;

  for (let c = 0; c < channels; c++) {
    const top = data[i00 + c] * (1 - dx) + data[i10 + c] * dx;
    const bottom = data[i01 + c] * (1 - dx) + data[i11 + c] * dx;
    out[outOffset + c] = Math.round(top * (1 - dy) + bottom * dy);
  }
}

/**
 * Warps the quadrilateral region of `src` into a straight rectangle of the given output size.
 *
 * Returns a new RawImage of size (outWidth x outHeight) with the same channel count as the input.
 * Uses inverse mapping (destination -> source) with bilinear sampling.
 */
export function warpQuadToRect(
  src: RawImage,
  quad: Quad,
  outWidth: number,
  outHeight: number,
): RawImage {
  const channels = src.channels;
  const out = new Uint8Array(outWidth * outHeight * channels);

  // Destination rectangle corners (TL, TR, BR, BL) in dst pixel space.
  const dstCorners: Point[] = [
    { x: 0, y: 0 },
    { x: outWidth - 1, y: 0 },
    { x: outWidth - 1, y: outHeight - 1 },
    { x: 0, y: outHeight - 1 },
  ];
  const srcCorners: Point[] = [
    quad.topLeft,
    quad.topRight,
    quad.bottomRight,
    quad.bottomLeft,
  ];

  // Homography mapping destination coords back into source coords.
  const hInv = computeHomography(dstCorners, srcCorners);
  if (!hInv) {
    // Degenerate quad: return a straight copy (letterboxed) of the source clamped to output.
    for (let y = 0; y < outHeight; y++) {
      for (let x = 0; x < outWidth; x++) {
        sampleBilinear(src, x, y, out, (y * outWidth + x) * channels);
      }
    }
    return { data: out, width: outWidth, height: outHeight, channels };
  }

  for (let y = 0; y < outHeight; y++) {
    for (let x = 0; x < outWidth; x++) {
      const srcPt = applyHomography(hInv, { x, y });
      const outOffset = (y * outWidth + x) * channels;
      if (!srcPt) {
        // Fill with white for undefined projections.
        for (let c = 0; c < channels; c++) out[outOffset + c] = 255;
        continue;
      }
      sampleBilinear(src, srcPt.x, srcPt.y, out, outOffset);
    }
  }

  return { data: out, width: outWidth, height: outHeight, channels };
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

/** The full-image quad (used as the fallback when corner detection fails). */
export function fullImageQuad(width: number, height: number): Quad {
  return {
    topLeft: { x: 0, y: 0 },
    topRight: { x: width - 1, y: 0 },
    bottomRight: { x: width - 1, y: height - 1 },
    bottomLeft: { x: 0, y: height - 1 },
  };
}
