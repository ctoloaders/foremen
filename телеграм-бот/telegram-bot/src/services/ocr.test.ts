import { describe, it, expect } from "bun:test";
import { concatenatePageTexts } from "./ocr.js";

/**
 * Property 4: OCR text concatenation preserves page order
 * Validates: Requirements 2.3
 *
 * For any array of N page texts [t1, t2, ..., tN], the concatenated output
 * SHALL contain t1 before t2, t2 before t3, etc., and each page's text
 * SHALL appear exactly once in the output.
 */
describe("Property 4: OCR text concatenation preserves page order", () => {
  // Helper: generate random non-empty text strings with unique content
  function generateUniqueTexts(count: number): string[] {
    return Array.from({ length: count }, (_, i) => `UniqueContent_${i}_${Math.random().toString(36).slice(2, 10)}`);
  }

  it("single page: output contains the page text exactly once", () => {
    for (let trial = 0; trial < 50; trial++) {
      const texts = generateUniqueTexts(1);
      const result = concatenatePageTexts(texts);

      // Text appears in the output
      expect(result).toContain(texts[0]);
      // Appears exactly once
      const firstIdx = result.indexOf(texts[0]);
      const lastIdx = result.lastIndexOf(texts[0]);
      expect(firstIdx).toBe(lastIdx);
    }
  });

  it("multiple pages: each text appears exactly once and in order", () => {
    for (let trial = 0; trial < 50; trial++) {
      const pageCount = 2 + Math.floor(Math.random() * 9); // 2..10 pages
      const texts = generateUniqueTexts(pageCount);
      const result = concatenatePageTexts(texts);

      // Each page text appears exactly once
      for (const text of texts) {
        expect(result).toContain(text);
        const firstIdx = result.indexOf(text);
        const lastIdx = result.lastIndexOf(text);
        expect(firstIdx).toBe(lastIdx);
      }

      // Order is preserved: indexOf(t_i) < indexOf(t_{i+1})
      for (let i = 0; i < texts.length - 1; i++) {
        const posA = result.indexOf(texts[i]);
        const posB = result.indexOf(texts[i + 1]);
        expect(posA).toBeLessThan(posB);
      }
    }
  });

  it("page separators contain correct page numbers in sequential order", () => {
    for (let trial = 0; trial < 30; trial++) {
      const pageCount = 1 + Math.floor(Math.random() * 10); // 1..10 pages
      const texts = generateUniqueTexts(pageCount);
      const result = concatenatePageTexts(texts);

      // Each page separator appears and in order
      for (let i = 0; i < pageCount; i++) {
        const separator = `--- Page ${i + 1} ---`;
        expect(result).toContain(separator);
      }

      // Separators are ordered
      for (let i = 0; i < pageCount - 1; i++) {
        const sepA = result.indexOf(`--- Page ${i + 1} ---`);
        const sepB = result.indexOf(`--- Page ${i + 2} ---`);
        expect(sepA).toBeLessThan(sepB);
      }
    }
  });

  it("all empty pages return empty string", () => {
    const emptyVariants = ["", "  ", "\t", "\n", "  \n  "];
    for (let trial = 0; trial < 30; trial++) {
      const pageCount = 1 + Math.floor(Math.random() * 10);
      const texts = Array.from({ length: pageCount }, () =>
        emptyVariants[Math.floor(Math.random() * emptyVariants.length)]
      );
      const result = concatenatePageTexts(texts);
      expect(result).toBe("");
    }
  });

  it("mixed empty and non-empty pages: non-empty texts are preserved in order", () => {
    for (let trial = 0; trial < 30; trial++) {
      const pageCount = 3 + Math.floor(Math.random() * 8); // 3..10 pages
      const texts: string[] = [];
      const nonEmptyIndices: number[] = [];

      for (let i = 0; i < pageCount; i++) {
        if (Math.random() > 0.3) {
          const content = `Content_${i}_${Math.random().toString(36).slice(2, 8)}`;
          texts.push(content);
          nonEmptyIndices.push(i);
        } else {
          texts.push(""); // empty page
        }
      }

      // Ensure at least one non-empty page
      if (nonEmptyIndices.length === 0) {
        texts[0] = "FallbackContent_" + Math.random().toString(36).slice(2, 8);
        nonEmptyIndices.push(0);
      }

      const result = concatenatePageTexts(texts);

      // Non-empty texts appear in order
      const nonEmptyTexts = nonEmptyIndices.map((i) => texts[i]);
      for (let i = 0; i < nonEmptyTexts.length - 1; i++) {
        const posA = result.indexOf(nonEmptyTexts[i]);
        const posB = result.indexOf(nonEmptyTexts[i + 1]);
        expect(posA).toBeLessThan(posB);
      }

      // Each non-empty text appears exactly once
      for (const text of nonEmptyTexts) {
        const firstIdx = result.indexOf(text);
        const lastIdx = result.lastIndexOf(text);
        expect(firstIdx).not.toBe(-1);
        expect(firstIdx).toBe(lastIdx);
      }
    }
  });

  it("output length grows proportionally with input (no data loss or duplication)", () => {
    for (let trial = 0; trial < 30; trial++) {
      const pageCount = 2 + Math.floor(Math.random() * 9);
      const texts = generateUniqueTexts(pageCount);
      const result = concatenatePageTexts(texts);

      // Each text is fully contained in the result
      const totalTextLength = texts.reduce((sum, t) => sum + t.length, 0);
      // Result should be at least as long as all texts combined (plus separators)
      expect(result.length).toBeGreaterThanOrEqual(totalTextLength);
    }
  });
});
