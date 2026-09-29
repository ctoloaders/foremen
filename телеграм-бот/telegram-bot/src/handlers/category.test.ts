import { describe, it, expect } from "bun:test";
import fc from "fast-check";
import { receiptRowFields } from "./category.js";

describe("receiptRowFields", () => {
  it("Материал branch: fills Projekt, empty Kategoria, description from OCR/manual", () => {
    const out = receiptRowFields({
      isMaterial: true,
      projectName: "Pokorna 4",
      description: "Плитка, клей",
      categoryName: undefined,
    });
    expect(out).toEqual({ project: "Pokorna 4", category: "", description: "Плитка, клей" });
  });

  it("Материал branch with missing project/description yields empty strings", () => {
    const out = receiptRowFields({ isMaterial: true });
    expect(out).toEqual({ project: "", category: "", description: "" });
  });

  it("non-material: empty Projekt, Kategoria = category, description from OCR when no follow-up", () => {
    const out = receiptRowFields({
      isMaterial: false,
      categoryName: "Инструмент",
      description: "Дрель Bosch",
    });
    expect(out).toEqual({ project: "", category: "Инструмент", description: "Дрель Bosch" });
  });

  it("non-material with follow-up answer: description = the answer (overrides OCR)", () => {
    const out = receiptRowFields({
      isMaterial: false,
      categoryName: "Топливо",
      categoryDetail: "WX 12345",
      description: "some OCR text",
    });
    expect(out).toEqual({ project: "", category: "Топливо", description: "WX 12345" });
  });

  it("non-material: blank/whitespace follow-up answer falls back to OCR description", () => {
    const out = receiptRowFields({
      isMaterial: false,
      categoryName: "Прочие",
      categoryDetail: "   ",
      description: "OCR desc",
    });
    expect(out).toEqual({ project: "", category: "Прочие", description: "OCR desc" });
  });

  it("property: material => category always empty and project == projectName", () => {
    fc.assert(
      fc.property(
        fc.string(),
        fc.string(),
        (projectName, description) => {
          const out = receiptRowFields({ isMaterial: true, projectName, description });
          expect(out.category).toBe("");
          expect(out.project).toBe(projectName);
        },
      ),
      { numRuns: 100 },
    );
  });

  it("property: non-material => project always empty and category == categoryName", () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 1 }),
        fc.string(),
        (categoryName, description) => {
          const out = receiptRowFields({ isMaterial: false, categoryName, description });
          expect(out.project).toBe("");
          expect(out.category).toBe(categoryName);
        },
      ),
      { numRuns: 100 },
    );
  });
});
