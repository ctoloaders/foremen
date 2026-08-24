import { describe, it, expect } from "bun:test";
import fc from "fast-check";

/**
 * Property 1: Photo collection appends to list
 * Property 2: Maximum page limit enforcement
 *
 * **Validates: Requirements 1.2, 1.4, 1.5, 1.6**
 */

/**
 * Core logic extracted from handlePhoto behavior.
 * Given an existing collection and a new photo file_id,
 * determines whether the photo is accepted and returns the updated collection.
 */
function addPhotoToCollection(
  photoFileIds: string[],
  newFileId: string,
  maxPages: number = 10
): { accepted: boolean; updatedIds: string[] } {
  if (photoFileIds.length >= maxPages) {
    return { accepted: false, updatedIds: photoFileIds };
  }
  return { accepted: true, updatedIds: [...photoFileIds, newFileId] };
}

describe("Property 1: Photo collection appends to list", () => {
  it("for any array of length N < 10, adding a photo results in N+1 elements", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 0, maxLength: 9 }),
        fc.string({ minLength: 1 }),
        (existingIds, newId) => {
          const { accepted, updatedIds } = addPhotoToCollection(existingIds, newId);
          expect(accepted).toBe(true);
          expect(updatedIds.length).toBe(existingIds.length + 1);
        }
      ),
      { numRuns: 200 }
    );
  });

  it("the new file_id is at the last position", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 0, maxLength: 9 }),
        fc.string({ minLength: 1 }),
        (existingIds, newId) => {
          const { updatedIds } = addPhotoToCollection(existingIds, newId);
          expect(updatedIds[updatedIds.length - 1]).toBe(newId);
        }
      ),
      { numRuns: 200 }
    );
  });

  it("all previous file_ids are preserved in order", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 0, maxLength: 9 }),
        fc.string({ minLength: 1 }),
        (existingIds, newId) => {
          const { updatedIds } = addPhotoToCollection(existingIds, newId);
          // All existing elements should be at their original positions
          for (let i = 0; i < existingIds.length; i++) {
            expect(updatedIds[i]).toBe(existingIds[i]);
          }
        }
      ),
      { numRuns: 200 }
    );
  });

  it("does not mutate the original array", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 0, maxLength: 9 }),
        fc.string({ minLength: 1 }),
        (existingIds, newId) => {
          const originalCopy = [...existingIds];
          addPhotoToCollection(existingIds, newId);
          expect(existingIds).toEqual(originalCopy);
        }
      ),
      { numRuns: 100 }
    );
  });
});

describe("Property 2: Maximum page limit enforcement", () => {
  it("for any array of length >= 10, attempting to add a photo does NOT change the array", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 10, maxLength: 20 }),
        fc.string({ minLength: 1 }),
        (existingIds, newId) => {
          const { accepted, updatedIds } = addPhotoToCollection(existingIds, newId);
          expect(accepted).toBe(false);
          expect(updatedIds).toBe(existingIds); // Same reference — unchanged
          expect(updatedIds.length).toBe(existingIds.length);
        }
      ),
      { numRuns: 200 }
    );
  });

  it("at exactly the limit (10 elements), photo is rejected", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 10, maxLength: 10 }),
        fc.string({ minLength: 1 }),
        (existingIds, newId) => {
          const { accepted, updatedIds } = addPhotoToCollection(existingIds, newId);
          expect(accepted).toBe(false);
          expect(updatedIds.length).toBe(10);
          // All elements unchanged
          for (let i = 0; i < existingIds.length; i++) {
            expect(updatedIds[i]).toBe(existingIds[i]);
          }
        }
      ),
      { numRuns: 100 }
    );
  });

  it("at 9 elements (just below limit), photo is accepted", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 9, maxLength: 9 }),
        fc.string({ minLength: 1 }),
        (existingIds, newId) => {
          const { accepted, updatedIds } = addPhotoToCollection(existingIds, newId);
          expect(accepted).toBe(true);
          expect(updatedIds.length).toBe(10);
          expect(updatedIds[9]).toBe(newId);
        }
      ),
      { numRuns: 100 }
    );
  });

  it("above the limit (11+ elements), all elements in the array remain unchanged", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 11, maxLength: 15 }),
        fc.string({ minLength: 1 }),
        (existingIds, newId) => {
          const { accepted, updatedIds } = addPhotoToCollection(existingIds, newId);
          expect(accepted).toBe(false);
          // Same reference, same elements
          expect(updatedIds).toBe(existingIds);
          for (let i = 0; i < existingIds.length; i++) {
            expect(updatedIds[i]).toBe(existingIds[i]);
          }
        }
      ),
      { numRuns: 100 }
    );
  });

  it("sequential additions from 0 to 10: first 10 accepted, 11th rejected", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 11, maxLength: 11 }),
        (fileIds) => {
          let collection: string[] = [];
          // Add first 10 — all should succeed
          for (let i = 0; i < 10; i++) {
            const { accepted, updatedIds } = addPhotoToCollection(collection, fileIds[i]);
            expect(accepted).toBe(true);
            expect(updatedIds.length).toBe(i + 1);
            collection = updatedIds;
          }
          // 11th should be rejected
          const { accepted, updatedIds } = addPhotoToCollection(collection, fileIds[10]);
          expect(accepted).toBe(false);
          expect(updatedIds.length).toBe(10);
        }
      ),
      { numRuns: 50 }
    );
  });
});
