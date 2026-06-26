import { describe, it, expect } from "vitest";
import { buildSystemPrompt, SCREENER_TOOLS } from "../src/screener/prompt";

describe("buildSystemPrompt", () => {
  it("includes the owner name", () => {
    expect(buildSystemPrompt("Ceryce")).toContain("Ceryce");
  });

  it("injects the owner profile when provided", () => {
    const p = buildSystemPrompt("Ceryce", "is a software developer who wants recruiter calls");
    expect(p).toContain("About Ceryce:");
    expect(p).toContain("software developer who wants recruiter calls");
  });

  it("omits the profile line when not provided or blank", () => {
    expect(buildSystemPrompt("Ceryce")).not.toContain("About Ceryce:");
    expect(buildSystemPrompt("Ceryce", "   ")).not.toContain("About Ceryce:");
  });
});

describe("SCREENER_TOOLS", () => {
  it("exposes the three screening tools", () => {
    expect(SCREENER_TOOLS.map((t) => t.name).sort()).toEqual(["connect_call", "mark_spam", "take_message"]);
  });
});
