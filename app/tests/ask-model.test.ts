/** AI 提问：选项切分、批量提问、多选、序列化。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  answeredCount,
  isAnswered,
  normalizeBatchQuestions,
  serializeBatchAnswers,
  splitAskOption,
  toAskOption,
  toAskOptions,
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

test("isAnswered：区分「没碰过」和「主动留空」", () => {
  const q = { id: "q0", question: "", options: [], multiple: false, placeholder: "", prefill: "", kind: "input" };
  assert.equal(isAnswered(q, undefined), false, "没碰过 = 未答");
  assert.equal(isAnswered(q, ""), true, "主动留空/跳过 = 已答（PiDeck 只判 value !== null）");
  assert.equal(isAnswered(q, "  "), true);
  assert.equal(isAnswered(q, "x"), true);
  assert.equal(isAnswered(q, []), false, "多选空数组仍算未答");
  assert.equal(isAnswered(q, ["a"]), true);
});

test("answeredCount 统计已答数量", () => {
  const questions = normalizeBatchQuestions([{ question: "a" }, { question: "b" }]);
  assert.equal(answeredCount(questions, {}), 0);
  assert.equal(answeredCount(questions, { q0: "x" }), 1);
  assert.equal(answeredCount(questions, { q0: "x", q1: ["y", "z"] }), 2);
});

test("多选切换：加入与移除", () => {
  assert.deepEqual(toggleSelection([], "a"), ["a"]);
  assert.deepEqual(toggleSelection(["a"], "b"), ["a", "b"]);
  assert.deepEqual(toggleSelection(["a", "b"], "a"), ["b"]);
});

// ---- 对象选项（ask_question 工具的真实结构）----

test("对象选项 {label,value,description} 被正确解析（回归：之前整批被过滤掉）", () => {
  const option = toAskOption({ label: "允许", value: "allow", description: "只对本次生效" });
  assert.deepEqual(option, { label: "允许", description: "只对本次生效", value: "allow" });
});

test("对象选项缺 value 时回落 label", () => {
  assert.equal(toAskOption({ label: "拒绝" })?.value, "拒绝");
});

test("对象选项缺 label 时回落 value", () => {
  const option = toAskOption({ value: "x" });
  assert.equal(option?.label, "x");
});

test("空对象/坏值被丢弃", () => {
  assert.equal(toAskOption({}), null);
  assert.equal(toAskOption(null), null);
  assert.equal(toAskOption(42), null);
  assert.equal(toAskOptions([{ label: "A" }, null, "B", 3]).length, 2);
});

test("batch_ask 的 questions 用对象选项时，每题都有选项", () => {
  // 结构与 pi-deck-ask-question.ts 的 envelope 一致
  const questions = normalizeBatchQuestions([
    {
      id: "q1",
      type: "select",
      question: "部署到哪个环境？",
      options: [
        { label: "测试", value: "test", description: "不影响线上" },
        { label: "生产", value: "prod", description: "谨慎操作" },
      ],
      allowOther: true,
    },
  ]);

  assert.equal(questions.length, 1);
  assert.equal(questions[0]?.options.length, 2, "两个选项都要在");
  assert.equal(questions[0]?.options[0]?.label, "测试");
  assert.equal(questions[0]?.options[0]?.value, "test");
  assert.equal(questions[0]?.options[0]?.description, "不影响线上");
});

test("字符串与对象选项混用也能解析", () => {
  const options = toAskOptions(["简单字符串", { label: "对象" }]);
  assert.equal(options.length, 2);
  assert.equal(options[0]?.label, "简单字符串");
  assert.equal(options[1]?.label, "对象");
});
