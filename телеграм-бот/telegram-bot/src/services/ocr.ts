import vision from "@google-cloud/vision";
import { config } from "../config.js";
import { withRetry } from "../utils/retry.js";

export interface OcrResult {
  text: string;
  confidence: number;
}

export async function extractText(imageBuffer: Buffer): Promise<OcrResult> {
  const client = new vision.ImageAnnotatorClient({
    credentials: config.google.serviceAccountKey as Record<string, unknown>,
  });

  const [result] = await client.documentTextDetection({
    image: { content: imageBuffer.toString("base64") },
  });

  const fullText = result.fullTextAnnotation?.text || "";
  const confidence = result.fullTextAnnotation?.pages?.[0]?.confidence || 0;

  return { text: fullText, confidence };
}

/**
 * Pure function that concatenates page texts with page separators.
 * Returns empty string if all pages are empty/whitespace-only.
 */
export function concatenatePageTexts(texts: string[]): string {
  if (texts.every((t) => t.trim() === "")) return "";
  return texts.map((text, i) => `--- Page ${i + 1} ---\n${text}`).join("\n");
}

export async function extractTextFromPages(
  imageBuffers: Buffer[]
): Promise<string> {
  const results: string[] = [];

  for (let i = 0; i < imageBuffers.length; i++) {
    const { text } = await withRetry(() => extractText(imageBuffers[i]));
    results.push(text);
  }

  return concatenatePageTexts(results);
}
