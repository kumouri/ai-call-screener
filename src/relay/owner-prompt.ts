/**
 * System prompt + tool schema for a talk-mode call: Margo phoning Ceryce herself for a live
 * conversation, not screening a caller on her behalf. Kept separate from `screener/prompt.ts` —
 * different persona register (`Persona.ownerDemeanor`, the "To Ceryce" bullet), different shape
 * (no connect/message/spam triage, just talk until she's done), different tool.
 */
import type { Persona } from "../persona";
import type { ToolSchema } from "../screener/prompt";

/** Spoken the instant the call connects, before ConversationRelay sends the first `prompt`. */
export const OWNER_GREETING = "Hey — it's Margo. Got a minute?";

export function buildOwnerSystemPrompt(
  ownerName: string,
  persona: Persona,
  spokenName?: string,
  contextSnapshot?: string,
  alert?: string,
): string {
  const identity = `You are Margo, ${ownerName}'s assistant, and you are speaking with ${ownerName} herself, live, on the phone. ${persona.ownerDemeanor.replaceAll("{owner}", ownerName)}`;
  const pronounce =
    spokenName !== undefined && spokenName.trim() !== "" && spokenName !== ownerName
      ? `When you say ${ownerName}'s name aloud, write it as "${spokenName}" so it is pronounced correctly.`
      : "";
  const context =
    contextSnapshot !== undefined && contextSnapshot.trim() !== ""
      ? `Here is a snapshot of ${ownerName}'s day (calendar, pending reminders, open loops) — draw on it if it's relevant, but never recite it unasked: ${contextSnapshot.trim()}`
      : "";
  // An alert call (`./alert.ts`): the line was already spoken as the call connected, so the model
  // must not repeat it unprompted, and a voicemail greeting must never get a conversation.
  const alertLines =
    alert !== undefined && alert.trim() !== ""
      ? [
          `You placed this call to tell ${ownerName} something specific, and you already said it as the call connected: "${alert.trim()}"`,
          `She may answer with a question, a plan, or just "okay" — reply to what she says; repeat the alert only if she asks or clearly missed it.`,
          `If what you hear is a voicemail greeting or an automated call-screening message rather than ${ownerName}, call end_call right away.`,
        ]
      : [];
  return [
    identity,
    pronounce,
    context,
    ...alertLines,
    `This is a real conversation, not a screening call — there is nobody to screen and nothing to decide about who ${ownerName} is.`,
    `Keep replies short and conversational, the way a real phone call sounds — a sentence or two per turn, never a briefing or a monologue.`,
    `When the conversation is naturally over — she says bye, thanks, talk later, or anything like it — call end_call rather than trailing off or waiting to be asked again.`,
  ]
    .filter((line) => line !== "")
    .join(" ");
}

export const OWNER_TOOLS: ToolSchema[] = [
  {
    name: "end_call",
    description: "End the call because the conversation is naturally over (she said goodbye or similar).",
    input_schema: {
      type: "object",
      properties: {},
    },
  },
];
