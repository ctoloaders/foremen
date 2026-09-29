import { describe, it, expect } from "bun:test";
import { parseExpenseCategoryRows } from "./sheets.js";

describe("parseExpenseCategoryRows", () => {
  it("maps category + extra_prompt rows", () => {
    const rows = [
      ["Материалы", ""],
      ["Инструмент", "Что именно?"],
      ["Транспорт", "Куда ехали?"],
    ];
    const result = parseExpenseCategoryRows(rows);
    expect(result).toEqual([
      { category: "Материалы", extraPrompt: undefined },
      { category: "Инструмент", extraPrompt: "Что именно?" },
      { category: "Транспорт", extraPrompt: "Куда ехали?" },
    ]);
  });

  it("skips rows with empty or whitespace-only category", () => {
    const rows = [
      ["", "ignored"],
      ["   ", "ignored"],
      ["Аренда", ""],
      [null as any, "ignored"],
      [undefined as any, "ignored"],
    ];
    const result = parseExpenseCategoryRows(rows);
    expect(result).toEqual([{ category: "Аренда", extraPrompt: undefined }]);
  });

  it("treats missing/blank column B as no follow-up question", () => {
    const rows = [["Прочее"], ["Питание", "   "]];
    const result = parseExpenseCategoryRows(rows);
    expect(result).toEqual([
      { category: "Прочее", extraPrompt: undefined },
      { category: "Питание", extraPrompt: undefined },
    ]);
  });

  it("trims category and extra_prompt", () => {
    const rows = [["  Материалы  ", "  Опишите  "]];
    const result = parseExpenseCategoryRows(rows);
    expect(result).toEqual([{ category: "Материалы", extraPrompt: "Опишите" }]);
  });

  it("handles empty input", () => {
    expect(parseExpenseCategoryRows([])).toEqual([]);
  });
});
