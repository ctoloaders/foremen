import { describe, it, expect } from "bun:test";
import { buildCellUpdates } from "./session-log.js";

const NOW = "2026-10-21T10:00:00.000Z";

describe("buildCellUpdates", () => {
  it("writes only the patched cells plus last_activity_at", () => {
    const u = buildCellUpdates("session_log", 7, { step: "await_photo", projectName: "Pokorna 4" }, NOW);
    expect(u).toEqual([
      { range: "session_log!G7", values: [["await_photo"]] },
      { range: "session_log!H7", values: [["Pokorna 4"]] },
      { range: "session_log!F7", values: [[NOW]] },
    ]);
  });

  it("always refreshes last_activity_at (column F) even for an empty patch", () => {
    expect(buildCellUpdates("s", 3, {}, NOW)).toEqual([{ range: "s!F3", values: [[NOW]] }]);
  });

  it("an explicit lastActivityAt in the patch is overridden by 'now'", () => {
    const u = buildCellUpdates("s", 2, { lastActivityAt: "old" }, NOW);
    expect(u).toEqual([{ range: "s!F2", values: [[NOW]] }]);
  });

  it("serializes arrays as JSON and clears them when empty", () => {
    const u = buildCellUpdates("s", 4, { photoFileIds: ["a", "b"], photoLinks: [] }, NOW);
    const byRange = Object.fromEntries(u.map((x) => [x.range, x.values[0][0]]));
    expect(byRange["s!K4"]).toBe(JSON.stringify(["a", "b"]));
    expect(byRange["s!L4"]).toBe("");
  });

  it("serializes sum as text and an explicit undefined as an empty cell", () => {
    const u = buildCellUpdates("s", 5, { sum: 477.27, description: undefined }, NOW);
    const byRange = Object.fromEntries(u.map((x) => [x.range, x.values[0][0]]));
    expect(byRange["s!M5"]).toBe("477.27");
    expect(byRange["s!N5"]).toBe("");
  });

  it("maps status and errorTrace to P and Q", () => {
    const u = buildCellUpdates("s", 9, { status: "failed", errorTrace: "trace" }, NOW);
    const byRange = Object.fromEntries(u.map((x) => [x.range, x.values[0][0]]));
    expect(byRange["s!P9"]).toBe("failed");
    expect(byRange["s!Q9"]).toBe("trace");
  });

  it("ignores unknown keys", () => {
    const u = buildCellUpdates("s", 2, { bogus: 1 } as any, NOW);
    expect(u).toEqual([{ range: "s!F2", values: [[NOW]] }]);
  });
});
