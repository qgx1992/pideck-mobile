/** AI 提问：选项切分、批量提问、多选、序列化。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  answeredCount,
  isAnswered,
  normalizeBatchQuestions,
  serializeBatchAnswers,
  splitAskOption,
  toggleSelection,
} from "../src/ui/ask-model.ts";

test("选项带 em dash 描述时被切开，值保留原文", () => {
  const option = splitAskOption("允许 — 只对本次生效");
  assert.equal(option.label, "允许");
  assert.equal(option.description, "只对本次生效");
  assert.equal(option.value, "允许 — 只对本次生效", "回传给服务端的是原文");
});

test("支持连字符、中文冒号、换行等分隔写法", () => {
  assert.deepEqual(splitAskOption("A - 说明"), { label: "A", description: "说明", value: "A - 说明" });
  assert.deepEqual(splitAskOption("B：说明"), { label: "B", description: "说明", value: "B：说明" });
  const newline = splitAskOption("C\n说明");
  assert.equal(newline.label, "C");
  assert.equal(newline.description, "说明");
});

test("没有描述时整串作为标签", () => {
  const option = splitAskOption("就这么办");
  assert.equal(option.label, "就这么办");
  assert.equal(option.description, "");
  assert.equal(option.value, "就这么办");
});

test("单个连字符（如 fix-bug）不被误切", () => {
  const option = splitAskOption("fix-bug");
  assert.equal(option.label, "fix-bug");
  assert.equal(option.description, "");
});

test("normalizeBatchQuestions 补默认 id 并解析选项", () => {
  const questions = normalizeBatchQuestions([
    { question: "第一题", options: ["是 — 确认", "否"] },
    { question: "第二题", multiple: true },
    null,
  ]);
  assert.equal(questions.length, 2);
  assert.equal(questions[0]?.id, "q0");
  assert.equal(questions[0]?.options.length, 2);
  assert.equal(questions[0]?.options[0]?.description, "确认");
  assert.equal(questions[1]?.multiple, true);
});

test("isAnswered 覆盖字符串/数组/空值", () => {
  const q = { id: "q0", question: "", options: [], multiple: false, placeholder: "", prefill: "" };
  assert.equal(isAnswered(q, undefined), false);
  assert.equal(isAnswered(q, "  "), false);
  assert.equal(isAnswered(q, "x"), true);
  assert.equal(isAnswered(q, []), false);
  assert.equal(isAnswered(q, ["a"]), true);
});

test("answeredCount 统计已答数量", () => {
  const questions = normalizeBatchQuestions([{ question: "a" }, { question: "b" }]);
  assert.equal(answeredCount(questions, {}), 0);
  assert.equal(answeredCount(questions, { q0: "x" }), 1);
  assert.equal(answeredCount(questions, { q0: "x", q1: ["y", "z"] }), 2);
});

test("serializeBatchAnswers 按题序拼成「问题：回答」", () => {
  const questions = normalizeBatchQuestions([{ question: "用哪个模型" }, { question: "要几个" }]);
  const text = serializeBatchAnswers(questions, { q0: "V4", q1: ["1", "2"] });
  assert.equal(text, "用哪个模型：V4\n要几个：1、2");
});

test("多选切换：加入与移除", () => {
  assert.deepEqual(toggleSelection([], "a"), ["a"]);
  assert.deepEqual(toggleSelection(["a"], "b"), ["a", "b"]);
  assert.deepEqual(toggleSelection(["a", "b"], "a"), ["b"]);
});
