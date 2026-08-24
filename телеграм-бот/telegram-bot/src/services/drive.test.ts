import { describe, it, expect } from "bun:test";
import fc from "fast-check";
import { generateFileName, formatPhotoLinks } from "./drive.js";

/**
 * Property 11: Multi-page file naming includes sequential page numbers
 *
 * For any store name, sum, and page count N > 1, the generated filenames SHALL
 * follow the pattern YYYY-MM-DD_HH-MM_<store>_<sum>_page<i>.<ext> for each i in [1..N].
 * For N = 1, the filename SHALL NOT contain a page number suffix.
 *
 * **Validates: Requirements 9.3, 9.4**
 */
describe("Property 11: Multi-page file naming includes sequential page numbers", () => {
  const mimeTypes = ["image/jpeg", "image/png"] as const;
  // Use double with proper constraints for sums (positive values with up to 2 decimal places)
  const sumArb = fc.double({ min: 0.01, max: 999999, noNaN: true, noDefaultInfinity: true });

  it("single page (pageNum=undefined): filename does NOT contain '_page'", () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 1, maxLength: 20 }),
        sumArb,
        fc.constantFrom(...mimeTypes),
        (storeName, sum, mime) => {
          const fileName = generateFileName(storeName, sum, mime);
          expect(fileName).not.toContain("_page");
        },
      ),
      { numRuns: 200 },
    );
  });

  it("multi-page (pageNum=1,2,3...): filename contains '_page<N>' before extension", () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 1, maxLength: 20 }),
        sumArb,
        fc.constantFrom(...mimeTypes),
        fc.integer({ min: 1, max: 10 }),
        (storeName, sum, mime, pageNum) => {
          const fileName = generateFileName(storeName, sum, mime, pageNum);
          const ext = mime === "image/png" ? "png" : "jpg";
          expect(fileName).toContain(`_page${pageNum}.${ext}`);
        },
      ),
      { numRuns: 200 },
    );
  });

  it("multi-page filenames have sequential numbering for each page", () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 1, maxLength: 20 }),
        sumArb,
        fc.constantFrom(...mimeTypes),
        fc.integer({ min: 2, max: 10 }),
        (storeName, sum, mime, pageCount) => {
          const ext = mime === "image/png" ? "png" : "jpg";
          for (let i = 1; i <= pageCount; i++) {
            const fileName = generateFileName(storeName, sum, mime, i);
            expect(fileName).toContain(`_page${i}.${ext}`);
          }
        },
      ),
      { numRuns: 100 },
    );
  });

  it("filename follows date_time_store_sum pattern structure", () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 1, maxLength: 20 }),
        sumArb,
        fc.constantFrom(...mimeTypes),
        (storeName, sum, mime) => {
          const fileName = generateFileName(storeName, sum, mime);
          const ext = mime === "image/png" ? "png" : "jpg";

          // Pattern: YYYY-MM-DD_HH-MM_<store>_<sum>.<ext>
          // Date part: 4digits-2digits-2digits
          const datePattern = /^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}_/;
          expect(fileName).toMatch(datePattern);

          // Ends with correct extension
          expect(fileName).toEndWith(`.${ext}`);

          // Contains the sum value
          expect(fileName).toContain(`_${sum}`);
        },
      ),
      { numRuns: 200 },
    );
  });

  it("multi-page filename follows date_time_store_sum_page<N> pattern structure", () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 1, maxLength: 20 }),
        sumArb,
        fc.constantFrom(...mimeTypes),
        fc.integer({ min: 1, max: 10 }),
        (storeName, sum, mime, pageNum) => {
          const fileName = generateFileName(storeName, sum, mime, pageNum);
          const ext = mime === "image/png" ? "png" : "jpg";

          // Date pattern at start
          const datePattern = /^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}_/;
          expect(fileName).toMatch(datePattern);

          // Ends with _page<N>.<ext>
          expect(fileName).toEndWith(`_page${pageNum}.${ext}`);

          // Contains the sum value before _page
          expect(fileName).toContain(`_${sum}_page`);
        },
      ),
      { numRuns: 200 },
    );
  });

  it("extension is 'png' for image/png and 'jpg' for image/jpeg", () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 1, maxLength: 20 }),
        sumArb,
        (storeName, sum) => {
          const pngFile = generateFileName(storeName, sum, "image/png");
          const jpgFile = generateFileName(storeName, sum, "image/jpeg");

          expect(pngFile).toEndWith(".png");
          expect(jpgFile).toEndWith(".jpg");
        },
      ),
      { numRuns: 100 },
    );
  });
});

/**
 * Property 12: Multi-page photo links concatenation
 *
 * For any array of N photo links [L1, L2, ..., LN], the formatted cell value
 * SHALL contain all N links. For N = 1, it SHALL contain exactly L1.
 *
 * **Validates: Requirements 9.6**
 */
describe("Property 12: Multi-page photo links concatenation", () => {
  const urlArb = fc.webUrl();

  it("single link: output equals the link exactly", () => {
    fc.assert(
      fc.property(urlArb, (link) => {
        const result = formatPhotoLinks([link]);
        expect(result).toBe(link);
      }),
      { numRuns: 200 },
    );
  });

  it("multiple links: all present in output", () => {
    fc.assert(
      fc.property(
        fc.array(urlArb, { minLength: 2, maxLength: 10 }),
        (links) => {
          const result = formatPhotoLinks(links);
          for (const link of links) {
            expect(result).toContain(link);
          }
        },
      ),
      { numRuns: 200 },
    );
  });

  it("multiple links: separated by newlines", () => {
    fc.assert(
      fc.property(
        fc.array(urlArb, { minLength: 2, maxLength: 10 }),
        (links) => {
          const result = formatPhotoLinks(links);
          const parts = result.split("\n");
          expect(parts.length).toBe(links.length);
        },
      ),
      { numRuns: 200 },
    );
  });

  it("order is preserved: links appear in the same sequence", () => {
    fc.assert(
      fc.property(
        fc.array(urlArb, { minLength: 2, maxLength: 10 }),
        (links) => {
          const result = formatPhotoLinks(links);
          const parts = result.split("\n");
          for (let i = 0; i < links.length; i++) {
            expect(parts[i]).toBe(links[i]);
          }
        },
      ),
      { numRuns: 200 },
    );
  });

  it("round-trip: splitting the formatted string by newline recovers original links", () => {
    fc.assert(
      fc.property(
        fc.array(
          fc.string({ minLength: 1, maxLength: 100 }).filter((s) => !s.includes("\n")),
          { minLength: 1, maxLength: 10 },
        ),
        (links) => {
          const result = formatPhotoLinks(links);
          const recovered = result.split("\n");
          expect(recovered).toEqual(links);
        },
      ),
      { numRuns: 200 },
    );
  });
});
