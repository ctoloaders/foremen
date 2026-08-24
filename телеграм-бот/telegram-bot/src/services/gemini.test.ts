import { describe, it, expect } from "bun:test";
import fc from "fast-check";
import { parseGeminiResponse } from "./gemini.js";

/**
 * Property 5: Gemini response parsing extracts valid fields
 * **Validates: Requirements 3.3, 3.4**
 *
 * For any valid JSON string containing `description` (non-empty string) and `store_name`
 * (non-empty string) fields, the parseGeminiResponse function SHALL return a GeminiReceiptData
 * object with those fields. For any JSON missing either required field, it SHALL return null.
 */
describe("parseGeminiResponse — Property 5: Gemini response parsing extracts valid fields", () => {
  // Arbitrary for non-empty strings (at least 1 non-whitespace char)
  const nonEmptyString = fc.string({ minLength: 1 }).filter((s) => s.trim().length > 0);

  it("valid JSON with description and store_name → returns GeminiReceiptData", () => {
    fc.assert(
      fc.property(
        nonEmptyString,
        nonEmptyString,
        fc.option(fc.double({ min: 0, max: 1_000_000, noNaN: true, noDefaultInfinity: true }), { nil: undefined }),
        (description, storeName, grossAmount) => {
          const json: Record<string, unknown> = { description, store_name: storeName };
          if (grossAmount !== undefined) {
            json.gross_amount = grossAmount;
          }
          const responseText = JSON.stringify(json);
          const result = parseGeminiResponse(responseText);

          expect(result).not.toBeNull();
          expect(result!.description).toBe(description.trim());
          expect(result!.store_name).toBe(storeName.trim());
          if (grossAmount !== undefined) {
            expect(result!.gross_amount).toBe(grossAmount);
          } else {
            expect(result!.gross_amount).toBeNull();
          }
        },
      ),
      { numRuns: 200 },
    );
  });

  it("valid JSON wrapped in markdown code block → still extracts correctly", () => {
    fc.assert(
      fc.property(nonEmptyString, nonEmptyString, (description, storeName) => {
        const json = JSON.stringify({ description, store_name: storeName, gross_amount: 100 });
        const responseText = "```json\n" + json + "\n```";
        const result = parseGeminiResponse(responseText);

        expect(result).not.toBeNull();
        expect(result!.description).toBe(description.trim());
        expect(result!.store_name).toBe(storeName.trim());
        expect(result!.gross_amount).toBe(100);
      }),
      { numRuns: 100 },
    );
  });

  it("missing description → returns null", () => {
    fc.assert(
      fc.property(nonEmptyString, (storeName) => {
        const json = JSON.stringify({ store_name: storeName, gross_amount: 50 });
        const result = parseGeminiResponse(json);
        expect(result).toBeNull();
      }),
      { numRuns: 100 },
    );
  });

  it("missing store_name → returns null", () => {
    fc.assert(
      fc.property(nonEmptyString, (description) => {
        const json = JSON.stringify({ description, gross_amount: 50 });
        const result = parseGeminiResponse(json);
        expect(result).toBeNull();
      }),
      { numRuns: 100 },
    );
  });

  it("empty description string → returns null", () => {
    fc.assert(
      fc.property(nonEmptyString, (storeName) => {
        const json = JSON.stringify({ description: "", store_name: storeName });
        const result = parseGeminiResponse(json);
        expect(result).toBeNull();
      }),
      { numRuns: 50 },
    );
  });

  it("empty store_name string → returns null", () => {
    fc.assert(
      fc.property(nonEmptyString, (description) => {
        const json = JSON.stringify({ description, store_name: "" });
        const result = parseGeminiResponse(json);
        expect(result).toBeNull();
      }),
      { numRuns: 50 },
    );
  });

  it("gross_amount as number → preserved", () => {
    fc.assert(
      fc.property(
        nonEmptyString,
        nonEmptyString,
        fc.double({ min: 0, max: 1_000_000, noNaN: true, noDefaultInfinity: true }),
        (description, storeName, amount) => {
          const json = JSON.stringify({ description, store_name: storeName, gross_amount: amount });
          const result = parseGeminiResponse(json);

          expect(result).not.toBeNull();
          expect(result!.gross_amount).toBe(amount);
        },
      ),
      { numRuns: 100 },
    );
  });

  it("gross_amount as string → returns null for that field", () => {
    fc.assert(
      fc.property(nonEmptyString, nonEmptyString, fc.string(), (description, storeName, amountStr) => {
        const json = JSON.stringify({ description, store_name: storeName, gross_amount: amountStr });
        const result = parseGeminiResponse(json);

        expect(result).not.toBeNull();
        expect(result!.gross_amount).toBeNull();
      }),
      { numRuns: 100 },
    );
  });

  it("gross_amount missing → returns null for that field", () => {
    fc.assert(
      fc.property(nonEmptyString, nonEmptyString, (description, storeName) => {
        const json = JSON.stringify({ description, store_name: storeName });
        const result = parseGeminiResponse(json);

        expect(result).not.toBeNull();
        expect(result!.gross_amount).toBeNull();
      }),
      { numRuns: 100 },
    );
  });

  it("invalid JSON → returns null", () => {
    fc.assert(
      fc.property(
        fc.string().filter((s) => {
          // Ensure the string looks like it has braces but isn't valid JSON
          try {
            if (!s.includes("{")) return false;
            JSON.parse(s.match(/\{[\s\S]*\}/)?.[0] || "");
            return false; // valid JSON, skip
          } catch {
            return true; // invalid JSON
          }
        }),
        (invalidInput) => {
          const result = parseGeminiResponse(invalidInput);
          expect(result).toBeNull();
        },
      ),
      { numRuns: 100 },
    );
  });

  it("no JSON in response → returns null", () => {
    fc.assert(
      fc.property(
        fc.string().filter((s) => !s.includes("{") && !s.includes("}")),
        (plainText) => {
          const result = parseGeminiResponse(plainText);
          expect(result).toBeNull();
        },
      ),
      { numRuns: 100 },
    );
  });

  it("whitespace in description/store_name → trimmed", () => {
    fc.assert(
      fc.property(nonEmptyString, nonEmptyString, (description, storeName) => {
        // Pad with spaces
        const paddedDesc = "  " + description + "  ";
        const paddedStore = "  " + storeName + "  ";
        const json = JSON.stringify({ description: paddedDesc, store_name: paddedStore });
        const result = parseGeminiResponse(json);

        expect(result).not.toBeNull();
        expect(result!.description).toBe(paddedDesc.trim());
        expect(result!.store_name).toBe(paddedStore.trim());
      }),
      { numRuns: 100 },
    );
  });
});
