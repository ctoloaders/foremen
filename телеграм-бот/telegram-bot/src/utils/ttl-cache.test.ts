import { describe, it, expect, beforeEach } from "bun:test";
import { cached, invalidateCache, registryCacheTtlMs } from "./ttl-cache.js";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe("ttl-cache", () => {
  beforeEach(() => invalidateCache());

  it("serves the cached value within the TTL (loader runs once)", async () => {
    let calls = 0;
    const loader = async () => ++calls;
    expect(await cached("k", loader, 1000)).toBe(1);
    expect(await cached("k", loader, 1000)).toBe(1);
    expect(calls).toBe(1);
  });

  it("reloads after the TTL expires", async () => {
    let calls = 0;
    const loader = async () => ++calls;
    expect(await cached("k", loader, 20)).toBe(1);
    await sleep(40);
    expect(await cached("k", loader, 20)).toBe(2);
  });

  it("deduplicates concurrent loads for the same key", async () => {
    let calls = 0;
    const loader = async () => {
      calls++;
      await sleep(30);
      return "v";
    };
    const results = await Promise.all([
      cached("k", loader, 1000),
      cached("k", loader, 1000),
      cached("k", loader, 1000),
    ]);
    expect(results).toEqual(["v", "v", "v"]);
    expect(calls).toBe(1);
  });

  it("keeps separate keys independent", async () => {
    expect(await cached("a", async () => 1, 1000)).toBe(1);
    expect(await cached("b", async () => 2, 1000)).toBe(2);
  });

  it("invalidateCache(key) forces a reload", async () => {
    let calls = 0;
    const loader = async () => ++calls;
    await cached("k", loader, 1000);
    invalidateCache("k");
    expect(await cached("k", loader, 1000)).toBe(2);
  });

  it("ttl <= 0 disables caching", async () => {
    let calls = 0;
    const loader = async () => ++calls;
    await cached("k", loader, 0);
    await cached("k", loader, 0);
    expect(calls).toBe(2);
  });

  it("serves the stale value when a refresh fails (e.g. Sheets quota)", async () => {
    let fail = false;
    const loader = async () => {
      if (fail) throw new Error("429");
      return "good";
    };
    expect(await cached("k", loader, 20)).toBe("good");
    await sleep(40);
    fail = true;
    expect(await cached("k", loader, 20)).toBe("good");
  });

  it("propagates the error when there is no previous value", async () => {
    await expect(cached("k", async () => { throw new Error("boom"); }, 1000)).rejects.toThrow("boom");
    // a failed first load must not poison the key
    expect(await cached("k", async () => "ok", 1000)).toBe("ok");
  });

  it("registryCacheTtlMs reads REGISTRY_CACHE_TTL_MS with a sane default", () => {
    const prev = process.env.REGISTRY_CACHE_TTL_MS;
    try {
      delete process.env.REGISTRY_CACHE_TTL_MS;
      expect(registryCacheTtlMs()).toBe(60_000);
      process.env.REGISTRY_CACHE_TTL_MS = "5000";
      expect(registryCacheTtlMs()).toBe(5000);
      process.env.REGISTRY_CACHE_TTL_MS = "0";
      expect(registryCacheTtlMs()).toBe(0);
      process.env.REGISTRY_CACHE_TTL_MS = "garbage";
      expect(registryCacheTtlMs()).toBe(60_000);
    } finally {
      if (prev === undefined) delete process.env.REGISTRY_CACHE_TTL_MS;
      else process.env.REGISTRY_CACHE_TTL_MS = prev;
    }
  });
});
