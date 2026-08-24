import { describe, it, expect } from "bun:test";
import { serializePhotoIds, deserializePhotoIds } from "./store.js";

describe("serializePhotoIds", () => {
  it("returns empty string for undefined", () => {
    expect(serializePhotoIds(undefined)).toBe("");
  });

  it("returns empty string for empty array", () => {
    expect(serializePhotoIds([])).toBe("");
  });

  it("serializes a single-element array to JSON", () => {
    expect(serializePhotoIds(["abc123"])).toBe('["abc123"]');
  });

  it("serializes multi-element array to JSON", () => {
    const result = serializePhotoIds(["id1", "id2", "id3"]);
    expect(JSON.parse(result)).toEqual(["id1", "id2", "id3"]);
  });
});

describe("deserializePhotoIds", () => {
  it("returns undefined for empty string", () => {
    expect(deserializePhotoIds("")).toBeUndefined();
  });

  it("returns undefined for falsy input", () => {
    expect(deserializePhotoIds("")).toBeUndefined();
  });

  it("parses a valid JSON array", () => {
    expect(deserializePhotoIds('["id1","id2"]')).toEqual(["id1", "id2"]);
  });

  it("parses a single-element JSON array", () => {
    expect(deserializePhotoIds('["abc123"]')).toEqual(["abc123"]);
  });

  it("backward compat: treats non-JSON string as single file_id", () => {
    expect(deserializePhotoIds("AgACAgIAAxkBAAIB")).toEqual(["AgACAgIAAxkBAAIB"]);
  });

  it("backward compat: treats invalid JSON as single file_id", () => {
    expect(deserializePhotoIds("{not-json")).toEqual(["{not-json"]);
  });

  it("backward compat: treats JSON object (not array) as single file_id", () => {
    // A JSON object is not an array, so it falls through to backward compat
    expect(deserializePhotoIds('{"key":"val"}')).toEqual(['{"key":"val"}']);
  });

  it("round-trip: serialize then deserialize preserves array", () => {
    const original = ["file1", "file2", "file3"];
    const serialized = serializePhotoIds(original);
    const deserialized = deserializePhotoIds(serialized);
    expect(deserialized).toEqual(original);
  });
});
