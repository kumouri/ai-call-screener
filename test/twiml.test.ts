import { describe, it, expect } from "vitest";
import { connectRelay, dial, escapeXml, gate, reject, say } from "../src/twiml";

describe("twiml builders", () => {
  it("dial bridges to the target with a callerId", () => {
    expect(dial("+14155550100", "+14155550111")).toContain(
      '<Dial callerId="+14155550111">+14155550100</Dial>',
    );
  });

  it("reject declines without answering", () => {
    expect(reject()).toContain('<Reject reason="rejected" />');
  });

  it("gate gathers one digit then hangs up on no input", () => {
    const x = gate({ prompt: "Press 1", actionUrl: "https://host/gate" });
    expect(x).toContain("<Gather");
    expect(x).toContain('action="https://host/gate"');
    // The Hangup fallback must come after the Gather so silent robodialers drop.
    expect(x.indexOf("<Gather")).toBeLessThan(x.indexOf("<Hangup"));
  });

  it("connectRelay wires the ConversationRelay websocket url", () => {
    const x = connectRelay({ wsUrl: "wss://host/ws", welcomeGreeting: "hi" });
    expect(x).toContain('<ConversationRelay url="wss://host/ws"');
    expect(x).toContain('welcomeGreeting="hi"');
  });

  it("escapes XML-special characters in dynamic text", () => {
    expect(escapeXml(`a&b<c>"'`)).toBe("a&amp;b&lt;c&gt;&quot;&apos;");
    expect(say("Tom & Jerry")).toContain("Tom &amp; Jerry");
  });
});
