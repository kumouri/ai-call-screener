import { describe, it, expect } from "vitest";
import { parsePushSms } from "../src/index";

const CELL = "+15125551234";

describe("parsePushSms", () => {
  it("accepts a valid body and defaults the recipient to the owner cell", () => {
    const r = parsePushSms({ text: "Morning brief: 2 things need you." }, CELL);
    expect(r).toEqual({ ok: true, msg: { to: CELL, text: "Morning brief: 2 things need you." } });
  });

  it("honors an explicit E.164 recipient and trims the text", () => {
    const r = parsePushSms({ text: "  hi  ", to: "+447700900123" }, CELL);
    expect(r).toEqual({ ok: true, msg: { to: "+447700900123", text: "hi" } });
  });

  it("rejects a missing or empty text", () => {
    expect(parsePushSms({}, CELL)).toEqual({ ok: false, error: "missing 'text'" });
    expect(parsePushSms({ text: "   " }, CELL)).toEqual({ ok: false, error: "missing 'text'" });
  });

  it("rejects an over-long text", () => {
    const r = parsePushSms({ text: "x".repeat(1201) }, CELL);
    expect(r).toEqual({ ok: false, error: "'text' too long (max 1200 chars)" });
  });

  it("rejects a non-E.164 recipient", () => {
    const r = parsePushSms({ text: "hi", to: "5125551234" }, CELL);
    expect(r).toEqual({ ok: false, error: "'to' must be E.164 (start with +)" });
  });

  it("rejects a non-object body", () => {
    expect(parsePushSms("nope", CELL)).toEqual({ ok: false, error: "body must be a JSON object" });
    expect(parsePushSms(null, CELL)).toEqual({ ok: false, error: "body must be a JSON object" });
  });
});
