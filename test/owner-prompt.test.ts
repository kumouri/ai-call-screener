import { describe, it, expect } from "vitest";
import { buildOwnerSystemPrompt, OWNER_GREETING, OWNER_TOOLS } from "../src/relay/owner-prompt";
import { MARGO } from "../src/persona";

describe("buildOwnerSystemPrompt", () => {
  it("uses the owner (To Ceryce) demeanor, not the screener (To outsiders) one", () => {
    const prompt = buildOwnerSystemPrompt("Ceryce", MARGO);
    expect(prompt).toContain("You lead with what matters");
    expect(prompt).not.toContain("razor-sharp gatekeeper");
  });

  it("substitutes the owner name into the placeholder", () => {
    const prompt = buildOwnerSystemPrompt("Ceryce", MARGO);
    expect(prompt).toContain("You are Margo, Ceryce's assistant, and you are speaking with Ceryce herself");
    expect(prompt).not.toContain("{owner}");
  });

  it("says this is a real conversation, not a screening call", () => {
    const prompt = buildOwnerSystemPrompt("Ceryce", MARGO);
    expect(prompt).toContain("not a screening call");
  });

  it("tells the model to call end_call when she says goodbye", () => {
    const prompt = buildOwnerSystemPrompt("Ceryce", MARGO);
    expect(prompt).toContain("end_call");
  });

  it("includes the spoken-name pronunciation hint when given", () => {
    const prompt = buildOwnerSystemPrompt("Ceryce", MARGO, "Cerise");
    expect(prompt).toContain('write it as "Cerise"');
  });

  it("omits the pronunciation hint when the spoken name matches the written name", () => {
    const prompt = buildOwnerSystemPrompt("Ceryce", MARGO, "Ceryce");
    expect(prompt).not.toContain("pronounced correctly");
  });

  it("folds in a context snapshot when given, but tells the model never to recite it unasked", () => {
    const prompt = buildOwnerSystemPrompt("Ceryce", MARGO, "Cerise", "Today's calendar:\n- 3pm dentist");
    expect(prompt).toContain("Today's calendar");
    expect(prompt).toContain("3pm dentist");
    expect(prompt).toContain("never recite it unasked");
  });

  it("omits the context section entirely when no snapshot is given", () => {
    const withoutContext = buildOwnerSystemPrompt("Ceryce", MARGO);
    expect(withoutContext).not.toContain("snapshot");
  });

  it("never contains the screener's owner-auth password bit — she IS the owner, nothing to prove", () => {
    // Incident 2026-09-26: the first live talk call ran the screener prompt (which DOES challenge
    // for a password, see prompt.test.ts) instead of this one. Guards against that regressing.
    const prompt = buildOwnerSystemPrompt("Ceryce", MARGO, "Cerise", "some context").toLowerCase();
    expect(prompt).not.toContain("password");
  });
});

describe("OWNER_TOOLS", () => {
  it("exposes only end_call — no screener triage tools", () => {
    expect(OWNER_TOOLS).toHaveLength(1);
    expect(OWNER_TOOLS[0]?.name).toBe("end_call");
  });
});

describe("OWNER_GREETING", () => {
  it("is a short, non-empty spoken line", () => {
    expect(OWNER_GREETING.length).toBeGreaterThan(0);
    expect(OWNER_GREETING.length).toBeLessThan(80);
  });
});
