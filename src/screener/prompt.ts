/**
 * System prompt + tool schemas for the stage-4 Claude conversation. Kept as
 * plain data (no SDK import) so it is host-agnostic and unit-testable. The
 * Durable Object wires these into the Anthropic API in M2.
 */
import type { Persona } from "../persona";

/** Anthropic tool schema shape (a subset, enough for our three tools). */
export interface ToolSchema {
  name: string;
  description: string;
  input_schema: {
    type: "object";
    properties: Record<string, unknown>;
    required?: string[];
  };
}

export function buildSystemPrompt(ownerName: string, ownerProfile?: string, persona?: Persona, spokenName?: string): string {
  const identity =
    persona !== undefined
      ? persona.demeanor.replaceAll("{owner}", ownerName)
      : `You are a warm, efficient phone call screener for ${ownerName}.`;
  const profile = ownerProfile !== undefined && ownerProfile.trim() !== "" ? `About ${ownerName}: ${ownerProfile.trim()}` : "";
  const pronounce =
    spokenName !== undefined && spokenName.trim() !== "" && spokenName !== ownerName
      ? `When you say ${ownerName}'s name aloud, write it as "${spokenName}" so it is pronounced correctly.`
      : "";
  return [
    identity,
    `You are screening an unknown caller who just pressed 1 to reach ${ownerName}.`,
    profile,
    pronounce,
    `Speech-to-text sometimes mangles ${ownerName}'s name (hearing "Theresa", "Cerise", or similar); treat any close-sounding variant as ${ownerName}, and never tell a caller that ${ownerName} isn't here or quibble over the name.`,
    `Greet them in character and find out who they are and why they're calling, in as few turns as possible — warm and human, never an interrogation. Don't make promises on ${ownerName}'s behalf.`,
    `Use what you know about ${ownerName} to judge the call, then call exactly one tool:`,
    `- connect_call: someone ${ownerName} would want to talk to now (for example a recruiter about a software role, or a genuine personal or appointment call).`,
    `- take_message: legitimate but it can wait, or you're genuinely unsure — capture a concise message.`,
    `- mark_spam: sales, robocalls, scams, fake "support" or "security" calls, warranty or insurance pitches, or anyone evasive about who they are.`,
    `Keep every reply short, clipped, and low-key — one dry sentence at most. No enthusiasm, no exclamations, no gushing or filler; you are pleasant but distinctly unbothered, like you have somewhere better to be.`,
  ]
    .filter((line) => line !== "")
    .join(" ");
}

export const SCREENER_TOOLS: ToolSchema[] = [
  {
    name: "connect_call",
    description: "Connect this legitimate caller to the owner now.",
    input_schema: {
      type: "object",
      properties: {
        caller_name: { type: "string", description: "Who is calling." },
        reason: { type: "string", description: "Why they are calling, one phrase." },
      },
      required: ["caller_name", "reason"],
    },
  },
  {
    name: "take_message",
    description: "Record a message for the owner to follow up on later.",
    input_schema: {
      type: "object",
      properties: {
        caller_name: { type: "string" },
        summary: { type: "string", description: "Concise summary of the message." },
        callback_number: { type: "string", description: "Callback number if given." },
      },
      required: ["caller_name", "summary"],
    },
  },
  {
    name: "mark_spam",
    description: "Flag this call as spam/robocall/scam and end it.",
    input_schema: {
      type: "object",
      properties: {
        reason: { type: "string", description: "Why this is spam." },
      },
      required: ["reason"],
    },
  },
];
