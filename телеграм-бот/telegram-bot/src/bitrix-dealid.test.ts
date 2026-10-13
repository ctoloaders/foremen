import { describe, it, expect } from "bun:test";
import { extractDealId } from "./bitrix-event.js";

describe("extractDealId", () => {
  it("nested object data.FIELDS.ID (standard Bitrix outgoing webhook)", () => {
    expect(extractDealId({ event: "ONCRMDEALUPDATE", data: { FIELDS: { ID: "6045" } } })).toBe("6045");
    expect(extractDealId({ data: { FIELDS: { ID: 6045 } } })).toBe("6045");
  });

  it("lowercase nested data.fields.id", () => {
    expect(extractDealId({ data: { fields: { id: "123" } } })).toBe("123");
  });

  it("flattened key data[FIELDS][ID]", () => {
    expect(extractDealId({ "data[FIELDS][ID]": "777" })).toBe("777");
  });

  it("document_id string forms", () => {
    expect(extractDealId({ document_id: "DEAL_36" })).toBe("36");
    expect(extractDealId({ document_id: "crm,CCrmDocumentDeal,DEAL_6045" })).toBe("6045");
    expect(extractDealId({ document_id: ["crm", "CCrmDocumentDeal", "DEAL_42"] })).toBe("42");
  });

  it("deal_id / ID / id plain keys", () => {
    expect(extractDealId({ deal_id: "11" })).toBe("11");
    expect(extractDealId({ ID: "12" })).toBe("12");
    expect(extractDealId({ id: 13 })).toBe("13");
  });

  it("JSON string body", () => {
    expect(extractDealId(JSON.stringify({ data: { FIELDS: { ID: "900" } } }))).toBe("900");
  });

  it("form-encoded string body", () => {
    expect(extractDealId("document_id=crm%2CCCrmDocumentDeal%2CDEAL_6045&ts=1")).toBe("6045");
    expect(extractDealId("data[FIELDS][ID]=321&event=ONCRMDEALUPDATE")).toBe("321");
  });

  it("returns null when no id present (e.g. flattened [object Object])", () => {
    expect(extractDealId({ event: "ONCRMDEALUPDATE", data: "[object Object]", auth: "[object Object]" })).toBeNull();
    expect(extractDealId("event=ONCRMDEALUPDATE&data=%5Bobject+Object%5D&auth=x")).toBeNull();
    expect(extractDealId(null)).toBeNull();
    expect(extractDealId(undefined)).toBeNull();
    expect(extractDealId("")).toBeNull();
  });

  it("prefers nested FIELDS.ID over document_id when both present", () => {
    expect(extractDealId({ data: { FIELDS: { ID: "100" } }, document_id: "DEAL_200" })).toBe("100");
  });
});
