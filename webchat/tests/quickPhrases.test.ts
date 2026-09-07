import assert from "node:assert/strict";
import test from "node:test";
import { normalizeQuickPhrases } from "../src/quickPhrases.ts";

test("normalizeQuickPhrases filters disabled and empty phrases, then sorts by sortOrder", () => {
  const phrases = normalizeQuickPhrases([
    { id: "b", title: "润色", content: "请润色这段文字", sortOrder: 2 },
    { id: "a", title: "续写", content: "请续写", isEnabled: false, sortOrder: 1 },
    { id: "c", title: "  ", content: "   ", sortOrder: 3 },
    { id: "d", title: "总结", content: "请总结全文", sortOrder: 1 },
  ]);

  assert.deepEqual(phrases.map((phrase) => phrase.id), ["d", "b"]);
  assert.equal(phrases[0].title, "总结");
});

test("normalizeQuickPhrases tolerates missing fields and dirty data", () => {
  const phrases = normalizeQuickPhrases([
    null,
    { content: "无标题短语" },
    { id: "x", title: "带说明", content: "正文", description: "  说明文字  " },
  ]);

  assert.equal(phrases.length, 2);
  assert.equal(phrases[0].id, "phrase-1");
  assert.equal(phrases[0].title, "无标题短语");
  assert.equal(phrases[0].description, undefined);
  assert.equal(phrases[1].description, "说明文字");
  assert.deepEqual(normalizeQuickPhrases(undefined), []);
  assert.deepEqual(normalizeQuickPhrases("nope"), []);
});
