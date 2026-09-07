import type { Conversation, ConversationMode } from "./types";

export const DRAFT_CONVERSATION_ID = "";

export function createConversationDraft(
  mode: ConversationMode,
  updatedAt = Date.now(),
): Conversation {
  return {
    id: DRAFT_CONVERSATION_ID,
    title: "新对话",
    mode,
    messageCount: 0,
    updatedAt,
  };
}

export function isPersistedConversation(
  conversation: Conversation | null | undefined,
): boolean {
  return String(conversation?.id ?? "").trim().length > 0;
}
