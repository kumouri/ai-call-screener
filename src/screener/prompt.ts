/**
 * System prompt + tool schemas for the stage-4 Claude conversation. Kept as
 * plain data (no SDK import) so it is host-agnostic and unit-testable. The
 * Durable Object wires these into the Anthropic API in M2.
 */

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

export function buildSystemPrompt(ownerName: string): string {
  return [
    `You are a friendly, efficient phone call screener for ${ownerName}.`,
    `You are speaking with an unknown caller who just pressed 1 to reach ${ownerName}.`,
    `Your job: in as few turns as possible, find out (1) who is calling and (2) the reason.`,
    `Be warm but brief — one short sentence per turn. Do not make promises on ${ownerName}'s behalf.`,
    `As soon as you know who it is and why they're calling, call exactly one tool:`,
    `- connect_call: a legitimate caller ${ownerName} would want to speak with now.`,
    `- take_message: legitimate but ${ownerName} can call back; capture a concise message.`,
    `- mark_spam: sales, robocall, scam, or refuses to identify themselves.`,
    `If the caller is evasive or pushy about personal/financial info, lean toward mark_spam.`,
  ].join(" ");
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
