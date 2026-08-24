import { GoogleGenerativeAI } from "@google/generative-ai";
import { config } from "../config.js";
import { withRetry } from "../utils/retry.js";

export interface GeminiReceiptData {
  description: string;
  store_name: string;
  gross_amount: number | null;
}

const RECEIPT_PARSE_PROMPT = `You are a receipt/invoice data extraction assistant.
Analyze the following OCR text from a receipt or invoice and extract:

1. "description" — brief summary of purchased items (2-5 words in the language of the receipt, e.g. "Плитка, клей, затирка" or "Farba, pędzle, folia")
2. "store_name" — name of the shop/company/seller
3. "gross_amount" — total BRUTTO amount (the final amount to pay, as a number). If you cannot find a total, return null.

Important:
- The receipt may be in Russian, Polish, Hebrew, or English
- Look for keywords like "ИТОГО", "RAZEM", "TOTAL", "סה״כ" for the total amount
- For store name, look at the header/top of the receipt
- For description, summarize the main item categories (not individual items)
- Return ONLY a valid JSON object, no markdown, no explanation

Return format:
{"description": "...", "store_name": "...", "gross_amount": 123.45}

OCR Text:
`;

/**
 * Parses a raw Gemini response text and extracts valid receipt fields.
 * Handles JSON extraction from plain text or markdown code blocks.
 * Returns null if the response cannot be parsed or required fields are missing.
 */
export function parseGeminiResponse(responseText: string): GeminiReceiptData | null {
  const jsonMatch = responseText.match(/\{[\s\S]*\}/);
  if (!jsonMatch) return null;

  try {
    const parsed = JSON.parse(jsonMatch[0]);

    // Validate required fields
    if (!parsed.description || typeof parsed.description !== "string") return null;
    if (!parsed.store_name || typeof parsed.store_name !== "string") return null;

    return {
      description: parsed.description.trim(),
      store_name: parsed.store_name.trim(),
      gross_amount: typeof parsed.gross_amount === "number" ? parsed.gross_amount : null,
    };
  } catch {
    return null;
  }
}

export async function parseReceipt(ocrText: string): Promise<GeminiReceiptData | null> {
  const genAI = new GoogleGenerativeAI(config.gemini.apiKey);
  const model = genAI.getGenerativeModel({ model: config.gemini.model });

  const result = await withRetry(async () => {
    return await model.generateContent(RECEIPT_PARSE_PROMPT + ocrText);
  });

  const responseText = result.response.text();
  return parseGeminiResponse(responseText);
}
