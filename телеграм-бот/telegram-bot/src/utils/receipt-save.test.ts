import { describe, it, expect } from "bun:test";
import fc from "fast-check";
import { getSumToSave } from "./receipt-save.js";

/**
 * Property 8: Saved receipt always uses user-entered sum
 *
 * For any completed receipt (both OCR and manual flows), the sum written to
 * the spreadsheet SHALL equal the user-entered sum value, regardless of the
 * OCR gross_amount value.
 *
 * **Validates: Requirements 5.7, 9.1**
 */
describe("Property 8: Saved receipt always uses user-entered sum", () => {
  it("for any user sum and any OCR amount, saved sum equals user sum", () => {
    fc.assert(
      fc.property(
        fc.double({ min: 0.01, max: 1_000_000, noNaN: true, noDefaultInfinity: true }),
        fc.option(fc.double({ min: 0.01, max: 1_000_000, noNaN: true, noDefaultInfinity: true }), { nil: null }),
        (userSum, ocrAmount) => {
          const result = getSumToSave(userSum, ocrAmount);
          expect(result).toBe(userSum);
        }
      ),
      { numRuns: 200 }
    );
  });

  it("OCR amount has no influence on saved sum", () => {
    fc.assert(
      fc.property(
        fc.double({ min: 0.01, max: 1_000_000, noNaN: true, noDefaultInfinity: true }),
        fc.double({ min: 0.01, max: 1_000_000, noNaN: true, noDefaultInfinity: true }),
        (userSum, differentOcrAmount) => {
          const result = getSumToSave(userSum, differentOcrAmount);
          expect(result).toBe(userSum);
        }
      ),
      { numRuns: 200 }
    );
  });

  it("null/undefined OCR amount still uses user sum", () => {
    fc.assert(
      fc.property(
        fc.double({ min: 0.01, max: 1_000_000, noNaN: true, noDefaultInfinity: true }),
        (userSum) => {
          expect(getSumToSave(userSum, null)).toBe(userSum);
          expect(getSumToSave(userSum, undefined)).toBe(userSum);
        }
      ),
      { numRuns: 100 }
    );
  });
});
