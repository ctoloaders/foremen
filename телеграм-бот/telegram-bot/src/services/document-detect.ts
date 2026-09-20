import { GoogleGenerativeAI } from "@google/generative-ai";
import { config } from "../config.js";
import { withRetry } from "../utils/retry.js";
import { logger } from "../utils/logger.js";
import { orderCorners, fullImageQuad, type Point, type Quad } from "../utils/perspective.js";

/**
 * Document boundary detection (Variant A): ask a Gemini vision model for the four corners
 * of the receipt/invoice within a photo. Coordinates are requested as normalized [0,1] values
 * so the prompt is resolution-independent; we scale them to pixels afterwards.
 *
 * If detection fails for any reason (model error, unparseable output, degenerate quad),
 * callers fall back to the full-image quad — the photo is used as-is.
 */

const CORNER_DETECT_PROMPT = `You are a document boundary detector.
The image contains a paper receipt or invoice, usually photographed at an angle on a background.
Find the four outer corners of the document (the sheet of paper), not the text.

Return ONLY a valid JSON object with normalized coordinates in the range 0..1, where
x is the fraction of the image width (left=0, right=1) and y is the fraction of the image
height (top=0, bottom=1). Use this exact shape:

{"top_left":{"x":0.10,"y":0.08},"top_right":{"x":0.90,"y":0.12},"bottom_right":{"x":0.88,"y":0.95},"bottom_left":{"x":0.08,"y":0.92}}

Rules:
- Return the outer edge of the paper, including a small margin if the edge is unclear.
- If the document fills the whole frame, use the image corners (0,0),(1,0),(1,1),(0,1).
- Return ONLY the JSON, no markdown, no explanation.`;

export interface NormalizedQuad {
  topLeft: Point;
  topRight: Point;
  bottomRight: Point;
  bottomLeft: Point;
}

function isNormalizedPoint(p: unknown): p is Point {
  if (typeof p !== "object" || p === null) return false;
  const { x, y } = p as Record<string, unknown>;
  return (
    typeof x === "number" &&
    typeof y === "number" &&
    Number.isFinite(x) &&
    Number.isFinite(y) &&
    x >= -0.05 &&
    x <= 1.05 &&
    y >= -0.05 &&
    y <= 1.05
  );
}

/**
 * Parses a raw Gemini response into a normalized quad. Handles JSON wrapped in markdown
 * fences or surrounded by prose. Returns null if the four corner points are missing or
 * out of the expected [0,1] range. Pure function — unit/property testable.
 */
export function parseCornerResponse(responseText: string): NormalizedQuad | null {
  const jsonMatch = responseText.match(/\{[\s\S]*\}/);
  if (!jsonMatch) return null;

  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(jsonMatch[0]);
  } catch {
    return null;
  }

  const tl = parsed.top_left;
  const tr = parsed.top_right;
  const br = parsed.bottom_right;
  const bl = parsed.bottom_left;

  if (!isNormalizedPoint(tl) || !isNormalizedPoint(tr) || !isNormalizedPoint(br) || !isNormalizedPoint(bl)) {
    return null;
  }

  // Clamp into [0,1] to tolerate small model overshoot.
  const clamp = (p: Point): Point => ({
    x: Math.min(Math.max(p.x, 0), 1),
    y: Math.min(Math.max(p.y, 0), 1),
  });

  return {
    topLeft: clamp(tl),
    topRight: clamp(tr),
    bottomRight: clamp(br),
    bottomLeft: clamp(bl),
  };
}

/**
 * Scales a normalized quad ([0,1] coordinates) to absolute pixel coordinates for an image
 * of the given size, then reorders the corners into a canonical TL/TR/BR/BL quad.
 * Returns null if the resulting quad is degenerate.
 */
export function normalizedToPixelQuad(
  norm: NormalizedQuad,
  width: number,
  height: number,
): Quad | null {
  const toPixel = (p: Point): Point => ({
    x: p.x * (width - 1),
    y: p.y * (height - 1),
  });
  return orderCorners([
    toPixel(norm.topLeft),
    toPixel(norm.topRight),
    toPixel(norm.bottomRight),
    toPixel(norm.bottomLeft),
  ]);
}

/**
 * Rejects a detected quad that is too small to be a real document (e.g. the model returned
 * a tiny box). If the quad covers less than `minAreaFraction` of the image, we prefer the
 * full-image fallback. Uses the shoelace formula for polygon area.
 */
export function quadAreaFraction(quad: Quad, width: number, height: number): number {
  const pts = [quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft];
  let area = 0;
  for (let i = 0; i < pts.length; i++) {
    const a = pts[i];
    const b = pts[(i + 1) % pts.length];
    area += a.x * b.y - b.x * a.y;
  }
  const imageArea = Math.max(1, (width - 1) * (height - 1));
  return Math.abs(area) / 2 / imageArea;
}

const MIN_AREA_FRACTION = 0.15;

/**
 * Detects the document quad in an image via Gemini vision.
 * Always resolves to a usable Quad: on any failure it returns the full-image quad
 * (used = false) so the caller can proceed with the untransformed photo.
 */
export async function detectDocumentQuad(
  imageBuffer: Buffer,
  mimeType: string,
  width: number,
  height: number,
): Promise<{ quad: Quad; detected: boolean }> {
  const fallback = { quad: fullImageQuad(width, height), detected: false };

  if (!config.gemini.apiKey) {
    return fallback;
  }

  try {
    const genAI = new GoogleGenerativeAI(config.gemini.apiKey);
    const model = genAI.getGenerativeModel({ model: config.gemini.model });

    const result = await withRetry(async () =>
      model.generateContent([
        CORNER_DETECT_PROMPT,
        {
          inlineData: {
            data: imageBuffer.toString("base64"),
            mimeType,
          },
        },
      ]),
    );

    const responseText = result.response.text();
    const norm = parseCornerResponse(responseText);
    if (!norm) {
      logger.info("detectDocumentQuad: unparseable response, using full image");
      return fallback;
    }

    const quad = normalizedToPixelQuad(norm, width, height);
    if (!quad) {
      logger.info("detectDocumentQuad: degenerate quad, using full image");
      return fallback;
    }

    if (quadAreaFraction(quad, width, height) < MIN_AREA_FRACTION) {
      logger.info("detectDocumentQuad: quad too small, using full image");
      return fallback;
    }

    return { quad, detected: true };
  } catch (err: any) {
    logger.info("detectDocumentQuad: detection error, using full image", { error: err?.message });
    return fallback;
  }
}
