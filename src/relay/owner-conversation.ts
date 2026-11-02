/**
 * Talk-mode conversation control — the owner-mode sibling of `screener/conversation.ts`. There is no
 * triage to conclude (connect/message/spam): the call just runs until she says goodbye (the model
 * calls `end_call`) or a turn cap is hit, so a stuck model can't run up ConversationRelay minutes
 * indefinitely on Ceryce's own line either.
 *
 * Pure aside from the injected `LlmClient`, so it is fully unit-testable.
 */
import type { LlmClient, LlmTurn } from "../screener/brain";
import type { ToolSchema } from "../screener/prompt";

export interface OwnerTurnOutcome {
  /** Assistant text to speak back, when the call is still going. */
  reply?: string;
  /** True once the call should end (she said goodbye, or the turn cap was hit). */
  ended?: boolean;
  /** Updated history to carry into the next turn. */
  history: LlmTurn[];
}

export async function runOwnerTurn(
  client: LlmClient,
  system: string,
  tools: ToolSchema[],
  history: LlmTurn[],
  herText: string,
  maxTurns: number,
): Promise<OwnerTurnOutcome> {
  const withHer: LlmTurn[] = [...history, { role: "user", content: herText }];
  const result = await client.respond(system, tools, withHer);

  if (result.toolUse?.name === "end_call") {
    return { ended: true, history: withHer };
  }

  const text = result.text ?? "";
  const assistantTurns = withHer.filter((t) => t.role === "assistant").length;
  if (assistantTurns >= maxTurns) {
    return { ended: true, history: withHer };
  }

  return { reply: text, history: [...withHer, { role: "assistant", content: text }] };
}
