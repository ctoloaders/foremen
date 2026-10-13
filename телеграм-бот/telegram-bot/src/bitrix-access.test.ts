import { describe, it, expect } from "bun:test";
import { computeDesiredAccess, type AccessWorker } from "./bitrix-event.js";

describe("computeDesiredAccess — one row per person per project", () => {
  it("collapses multiple roles for the same user into a single primary role", () => {
    const workers: AccessWorker[] = [
      { userId: "11", name: "Biuro ALX", role: "estimator" },
      { userId: "11", name: "Biuro ALX", role: "sales" },
    ];
    const d = computeDesiredAccess(workers);
    expect(d.size).toBe(1);
    expect(d.get("11")).toEqual({ name: "Biuro ALX", role: "estimator" }); // estimator > sales
  });

  it("prefers pm over foreman over estimator over sales", () => {
    const workers: AccessWorker[] = [
      { userId: "7", name: "X", role: "sales" },
      { userId: "7", name: "X", role: "pm" },
      { userId: "7", name: "X", role: "foreman" },
    ];
    expect(computeDesiredAccess(workers).get("7")).toEqual({ name: "X", role: "pm" });
  });

  it("keeps distinct users separate", () => {
    const workers: AccessWorker[] = [
      { userId: "1", name: "A", role: "foreman" },
      { userId: "2", name: "B", role: "estimator" },
      { userId: "3", name: "C", role: "pm" },
    ];
    const d = computeDesiredAccess(workers);
    expect(d.size).toBe(3);
    expect(d.get("1")!.role).toBe("foreman");
    expect(d.get("2")!.role).toBe("estimator");
    expect(d.get("3")!.role).toBe("pm");
  });

  it("drops unmanaged roles (e.g. 'other')", () => {
    const workers: AccessWorker[] = [
      { userId: "5", name: "Z", role: "other" },
      { userId: "6", name: "Y", role: "sales" },
    ];
    const d = computeDesiredAccess(workers);
    expect(d.has("5")).toBe(false);
    expect(d.has("6")).toBe(true);
  });

  it("empty input yields empty set", () => {
    expect(computeDesiredAccess([]).size).toBe(0);
  });

  it("same user appearing many times still yields exactly one entry", () => {
    const workers: AccessWorker[] = Array.from({ length: 10 }, () => ({
      userId: "99",
      name: "Dup",
      role: "sales",
    }));
    const d = computeDesiredAccess(workers);
    expect(d.size).toBe(1);
    expect(d.get("99")).toEqual({ name: "Dup", role: "sales" });
  });
});
