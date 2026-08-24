import { describe, it, expect } from "bun:test";
import { compareSums } from "./sum-compare.js";

/**
 * Property 7: Sum comparison determines match within tolerance
 *
 * For any two amounts A (user-entered) and B (OCR-recognized), the comparison
 * function SHALL classify them as matching if and only if |A - B| <= 0.01.
 * When B is null, the result SHALL always be "match" (proceed without verification).
 *
 * **Validates: Requirements 5.1, 5.2, 5.3, 5.4**
 */
describe("compareSums — Property 7: Sum comparison determines match within tolerance", () => {
  // Property: same values always match
  it("same values → match", () => {
    const testValues = [0, 1, 100, 999.99, 0.5, 12345.67, 0.01];
    for (const val of testValues) {
      expect(compareSums(val, val)).toEqual({ match: true });
    }
  });

  // Property: difference of exactly 0.01 → match (boundary)
  it("difference of exactly 0.01 → match (boundary)", () => {
    expect(compareSums(100.00, 100.01)).toEqual({ match: true });
    expect(compareSums(100.01, 100.00)).toEqual({ match: true });
    expect(compareSums(0.01, 0.00)).toEqual({ match: true });
    expect(compareSums(0.00, 0.01)).toEqual({ match: true });
    expect(compareSums(999.99, 1000.00)).toEqual({ match: true });
    expect(compareSums(1000.00, 999.99)).toEqual({ match: true });
  });

  // Property: difference of 0.02 → no match
  it("difference of 0.02 → no match", () => {
    expect(compareSums(100.00, 100.02)).toEqual({ match: false });
    expect(compareSums(100.02, 100.00)).toEqual({ match: false });
    expect(compareSums(0.00, 0.02)).toEqual({ match: false });
    expect(compareSums(50.50, 50.52)).toEqual({ match: false });
  });

  // Property: ocrAmount is null → always match
  it("ocrAmount is null → always match", () => {
    const testValues = [0, 1, 100, 999999.99, -5, 0.001];
    for (const val of testValues) {
      expect(compareSums(val, null)).toEqual({ match: true });
    }
  });

  // Property: ocrAmount is undefined → always match
  it("ocrAmount is undefined → always match", () => {
    const testValues = [0, 1, 100, 999999.99, -5, 0.001];
    for (const val of testValues) {
      expect(compareSums(val, undefined)).toEqual({ match: true });
    }
  });

  // Property: large numbers with small difference (< 0.01) → match
  it("large numbers with small difference (< 0.01) → match", () => {
    expect(compareSums(99999.999, 99999.995)).toEqual({ match: true });
    expect(compareSums(50000.005, 50000.001)).toEqual({ match: true });
    expect(compareSums(123456.78, 123456.78)).toEqual({ match: true });
    expect(compareSums(100000.00, 100000.005)).toEqual({ match: true });
  });

  // Property: large numbers with difference > 0.01 → no match
  it("large numbers with difference > 0.01 → no match", () => {
    expect(compareSums(99999.00, 99999.02)).toEqual({ match: false });
    expect(compareSums(50000.00, 50000.05)).toEqual({ match: false });
    expect(compareSums(123456.78, 123456.80)).toEqual({ match: false });
    expect(compareSums(100000.00, 100000.50)).toEqual({ match: false });
  });

  // Property: zero values → match
  it("zero values → match", () => {
    expect(compareSums(0, 0)).toEqual({ match: true });
    expect(compareSums(0.0, 0.0)).toEqual({ match: true });
    expect(compareSums(0.00, 0.01)).toEqual({ match: true });
    expect(compareSums(0.01, 0.00)).toEqual({ match: true });
  });

  // Property: negative difference → still uses abs
  it("negative difference → still uses abs (order of arguments doesn't matter)", () => {
    // When userAmount < ocrAmount, diff is negative before abs
    expect(compareSums(99.99, 100.00)).toEqual({ match: true });
    expect(compareSums(100.00, 99.99)).toEqual({ match: true });
    // Larger gap: 0.02 both directions → no match
    expect(compareSums(99.98, 100.00)).toEqual({ match: false });
    expect(compareSums(100.00, 99.98)).toEqual({ match: false });
  });

  // Property-based: randomized values where |A - B| <= 0.01 always match
  it("property: for any A, B where |A - B| <= 0.01, result is match=true", () => {
    for (let i = 0; i < 100; i++) {
      const base = Math.random() * 100000;
      // Generate offset in [-0.01, 0.01] range
      const offset = (Math.random() * 0.02) - 0.01;
      const a = base;
      const b = base + offset;
      const result = compareSums(a, b);
      expect(result.match).toBe(true);
    }
  });

  // Property-based: randomized values where |A - B| > 0.01 always don't match
  it("property: for any A, B where |A - B| > 0.01, result is match=false", () => {
    for (let i = 0; i < 100; i++) {
      const base = Math.random() * 100000;
      // Generate offset with magnitude > 0.01 (between 0.02 and 100)
      const sign = Math.random() > 0.5 ? 1 : -1;
      const offset = sign * (0.02 + Math.random() * 99.98);
      const a = base;
      const b = base + offset;
      const result = compareSums(a, b);
      expect(result.match).toBe(false);
    }
  });

  // Property-based: null/undefined ocrAmount always matches regardless of userAmount
  it("property: for any userAmount, null/undefined ocrAmount always returns match=true", () => {
    for (let i = 0; i < 50; i++) {
      const userAmount = (Math.random() - 0.5) * 200000; // random including negatives
      expect(compareSums(userAmount, null)).toEqual({ match: true });
      expect(compareSums(userAmount, undefined)).toEqual({ match: true });
    }
  });
});
