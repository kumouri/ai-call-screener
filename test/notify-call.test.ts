import { describe, it, expect, vi, afterEach } from "vitest";
import { callsCreateUrl, placeCall } from "../src/notify/call";
import type { Env } from "../src/config";

describe("callsCreateUrl", () => {
  it("builds the Twilio call-create resource URL", () => {
    expect(callsCreateUrl("AC123")).toBe(
      "https://api.twilio.com/2010-04-01/Accounts/AC123/Calls.json",
    );
  });
});

function fakeEnv(): Env {
  return {
    TWILIO_ACCOUNT_SID: "AC123",
    TWILIO_AUTH_TOKEN: "tok",
    TWILIO_NUMBER_E164: "+15550000000",
    USER_CELL_E164: "+15551112222",
  } as unknown as Env;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("placeCall", () => {
  it("POSTs To/From/inline-Twiml and returns the call SID", async () => {
    const spy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ sid: "CA999" }), { status: 201 }),
    );

    const sid = await placeCall(fakeEnv(), "+15551112222", "Ceryce — it's Margo. Take your meds.");
    expect(sid).toBe("CA999");

    const call = spy.mock.calls[0]!;
    expect(call[0]).toBe("https://api.twilio.com/2010-04-01/Accounts/AC123/Calls.json");
    const init = call[1]!;
    const body = (init.body as URLSearchParams);
    expect(body.get("To")).toBe("+15551112222");
    expect(body.get("From")).toBe("+15550000000");
    expect(body.get("Twiml")).toContain("<Say>");
    expect(body.get("Twiml")).toContain("<Hangup />");
    expect((init.headers as Record<string, string>).Authorization).toMatch(/^Basic /);
  });

  it("throws on a non-2xx Twilio response", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("nope", { status: 400 }));
    await expect(placeCall(fakeEnv(), "+15551112222", "hi")).rejects.toThrow(/Twilio call create 400/);
  });
});
