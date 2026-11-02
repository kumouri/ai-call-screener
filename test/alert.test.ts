import { describe, it, expect, vi, afterEach } from "vitest";
import { alertGreeting, ALERT_LISTENING_LINE, isAlertAck, looksLikeVoicemail } from "../src/relay/alert";
import { alertTalkCallTwiml, placeAlertTalkCall, talkCallTwiml } from "../src/notify/call";
import { buildOwnerSystemPrompt } from "../src/relay/owner-prompt";
import { alertTalkAllowed } from "../src/index";
import { MARGO } from "../src/persona";
import type { Env } from "../src/config";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("looksLikeVoicemail", () => {
  it("recognises carrier and personal voicemail greetings", () => {
    expect(looksLikeVoicemail("Hi, you've reached Ceryce, please leave a message after the tone.")).toBe(true);
    expect(looksLikeVoicemail("The person you are calling is not available.")).toBe(true);
    expect(looksLikeVoicemail("The number you have dialed has a voice mailbox that is full.")).toBe(true);
    expect(looksLikeVoicemail("Please record your message.")).toBe(true);
  });

  it("recognises a call-screening assistant", () => {
    expect(looksLikeVoicemail("Hi, the person you're calling is using a screening service from Google. Go ahead and say your name.")).toBe(true);
    expect(looksLikeVoicemail("Please state your name and why you're calling.")).toBe(true);
  });

  it("does not mistake her ordinary replies for a voicemail", () => {
    for (const reply of ["Hello?", "Okay, thanks Margo.", "On my way.", "How many minutes?", "Got it", "Yeah I'm heading down now"]) {
      expect(looksLikeVoicemail(reply)).toBe(false);
    }
  });

  it("is false for empty speech", () => {
    expect(looksLikeVoicemail("   ")).toBe(false);
  });
});

describe("isAlertAck", () => {
  it("counts anything she says", () => {
    expect(isAlertAck("Hello?")).toBe(true);
    expect(isAlertAck("ok")).toBe(true);
  });

  it("never counts a voicemail greeting or silence", () => {
    expect(isAlertAck("Please leave a message after the beep.")).toBe(false);
    expect(isAlertAck("")).toBe(false);
  });
});

describe("alertGreeting", () => {
  it("speaks the alert first, then the listening line", () => {
    const g = alertGreeting("The bunnies are 5 layers from the swap.");
    expect(g.startsWith("The bunnies are 5 layers from the swap.")).toBe(true);
    expect(g.endsWith(ALERT_LISTENING_LINE)).toBe(true);
    expect(g).toContain("press 1");
  });
});

describe("alertTalkCallTwiml", () => {
  const twiml = alertTalkCallTwiml("https://host", "esc-1-1", "Swap in 5 layers & counting.");

  it("is a ConversationRelay call into the RelaySession named by the session id", () => {
    expect(twiml).toContain("<ConversationRelay");
    expect(twiml).toContain('url="wss://host/ws?s=esc-1-1"');
  });

  it("opens with the alert line (XML-escaped) as the welcome greeting", () => {
    expect(twiml).toContain('welcomeGreeting="Swap in 5 layers &amp; counting.');
  });

  it("can't have the alert cut off by her hello, and forwards key presses", () => {
    expect(twiml).toContain('welcomeGreetingInterruptible="none"');
    expect(twiml).toContain('dtmfDetection="true"');
  });

  it("carries the mode=owner parameter the two-signal owner check needs", () => {
    expect(twiml).toContain('<Parameter name="mode" value="owner" />');
  });

  it("never hangs up on its own — no <Hangup> or <Say> script", () => {
    expect(twiml).not.toContain("<Hangup");
    expect(twiml).not.toContain("<Say");
  });

  it("leaves the ordinary talk call unchanged (no DTMF, interruptible greeting)", () => {
    const talk = talkCallTwiml("https://host", "s1");
    expect(talk).not.toContain("dtmfDetection");
    expect(talk).not.toContain("welcomeGreetingInterruptible");
  });
});

describe("placeAlertTalkCall", () => {
  it("rings only USER_CELL_E164", async () => {
    const spy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ sid: "CA1" }), { status: 201 }));
    const env = { TWILIO_ACCOUNT_SID: "AC1", TWILIO_AUTH_TOKEN: "t", TWILIO_NUMBER_E164: "+15550000000", USER_CELL_E164: "+15551112222" } as unknown as Env;
    expect(await placeAlertTalkCall(env, "https://host", "s", "hi")).toBe("CA1");
    const body = spy.mock.calls[0]![1]!.body as URLSearchParams;
    expect(body.get("To")).toBe("+15551112222");
  });
});

describe("alertTalkAllowed", () => {
  it("allows talk only when asked and the call goes to her own cell", () => {
    expect(alertTalkAllowed(true, "+15551112222", "+15551112222")).toBe(true);
  });

  it("refuses a to-override, an unset cell, or a non-boolean flag", () => {
    expect(alertTalkAllowed(true, "+15559998888", "+15551112222")).toBe(false);
    expect(alertTalkAllowed(true, "", "")).toBe(false);
    expect(alertTalkAllowed(true, undefined, undefined)).toBe(false);
    expect(alertTalkAllowed("true", "+15551112222", "+15551112222")).toBe(false);
    expect(alertTalkAllowed(undefined, "+15551112222", "+15551112222")).toBe(false);
  });
});

describe("buildOwnerSystemPrompt — alert calls", () => {
  it("tells the model what it already said, and to hang up on a voicemail", () => {
    const p = buildOwnerSystemPrompt("Ceryce", MARGO, "Cerise", "", "The print pauses in 5 layers.");
    expect(p).toContain('you already said it as the call connected: "The print pauses in 5 layers."');
    expect(p).toContain("voicemail");
    expect(p).toContain("end_call");
  });

  it("adds nothing for an ordinary talk call", () => {
    expect(buildOwnerSystemPrompt("Ceryce", MARGO)).not.toContain("already said it");
  });
});
