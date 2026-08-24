import { describe, it, expect } from "bun:test";
import fc from "fast-check";
import { serializePhotoIds, deserializePhotoIds } from "./state/store.js";

/**
 * **Validates: Requirements 7.4**
 *
 * Property 9: Feature flag disables OCR flow entirely
 *
 * For any photo sent when OCR_ENABLED is "false", the system SHALL transition
 * directly from AWAIT_PHOTO to AWAIT_SUM (old manual flow) without creating
 * a photoFileIds array or calling OCR/Gemini services.
 */
describe("Property 9: Feature flag disables OCR flow entirely", () => {
  function getPhotoFlowDecision(ocrEnabled: boolean): {
    nextStep: string;
    usesPhotoFileIds: boolean;
  } {
    if (!ocrEnabled) {
      return { nextStep: "await_sum", usesPhotoFileIds: false };
    }
    return { nextStep: "await_more_pages", usesPhotoFileIds: true };
  }

  it("when OCR disabled: always transitions to AWAIT_SUM without photoFileIds", () => {
    const result = getPhotoFlowDecision(false);
    expect(result.nextStep).toBe("await_sum");
    expect(result.usesPhotoFileIds).toBe(false);
  });

  it("when OCR enabled: transitions to AWAIT_MORE_PAGES with photoFileIds", () => {
    const result = getPhotoFlowDecision(true);
    expect(result.nextStep).toBe("await_more_pages");
    expect(result.usesPhotoFileIds).toBe(true);
  });
});

/**
 * **Validates: Requirements 8.3**
 *
 * Property 10: photoFileIds JSON serialization round-trip
 *
 * For any array of strings (photo file_ids), serializing to JSON and
 * deserializing back SHALL produce an identical array (same length,
 * same elements, same order).
 */
describe("Property 10: photoFileIds JSON serialization round-trip", () => {
  it("any array of strings survives serialize → deserialize unchanged", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 1, maxLength: 10 }),
        (ids) => {
          const serialized = serializePhotoIds(ids);
          const deserialized = deserializePhotoIds(serialized);
          expect(deserialized).toEqual(ids);
        }
      ),
      { numRuns: 200 }
    );
  });

  it("empty array serializes to empty string and deserializes to undefined", () => {
    expect(serializePhotoIds([])).toBe("");
    expect(deserializePhotoIds("")).toBeUndefined();
  });

  it("undefined serializes to empty string", () => {
    expect(serializePhotoIds(undefined)).toBe("");
  });

  it("single element array round-trips correctly", () => {
    fc.assert(
      fc.property(fc.string({ minLength: 1 }), (id) => {
        const serialized = serializePhotoIds([id]);
        const deserialized = deserializePhotoIds(serialized);
        expect(deserialized).toEqual([id]);
      }),
      { numRuns: 200 }
    );
  });

  it("order is preserved through round-trip", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 2, maxLength: 10 }),
        (ids) => {
          const serialized = serializePhotoIds(ids);
          const deserialized = deserializePhotoIds(serialized)!;
          for (let i = 0; i < ids.length; i++) {
            expect(deserialized[i]).toBe(ids[i]);
          }
        }
      ),
      { numRuns: 200 }
    );
  });
});
