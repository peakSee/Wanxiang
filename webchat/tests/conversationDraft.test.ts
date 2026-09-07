import assert from "node:assert/strict";
import test from "node:test";
import {
  createConversationDraft,
  isPersistedConversation,
} from "../src/conversationDraft.ts";

test("creating a new WanXiang conversation starts as a local draft", () => {
  const draft = createConversationDraft("normal", 1234);

  assert.equal(draft.id, "");
  assert.equal(draft.mode, "normal");
  assert.equal(draft.updatedAt, 1234);
  assert.equal(isPersistedConversation(draft), false);
});

test("a non-empty WanXiang session id is considered persisted", () => {
  assert.equal(isPersistedConversation({ id: "8da73f50-c83c-4eca-a735-e52a2328848d", mode: "normal" }), true);
  assert.equal(isPersistedConversation({ id: "", mode: "normal" }), false);
  assert.equal(isPersistedConversation(null), false);
});
