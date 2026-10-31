/**
 * 「提示词」面板的视图模型：只列已导入的模板（`/runtime/commands` 里 source=prompt 的那些）。
 *
 * 这条用例守的就是「别再把 XuePrompt 中文商店库当成提示词库」这个改错方向的坑。
 */

import test from "node:test";
import assert from "node:assert/strict";

const {
  importedPrompts,
  isPromptCommand,
  filterPromptEntries,
  promptInsertText,
  promptCommandSource,
  PROMPT_EMPTY_HINT,
} = await import("../src/ui/prompt-picker-model.ts");

const commands = [
  {
    name: "todo-comment-debt-tracker",
    description: "Scans a codebase for TODO / FIXME",
    source: "prompt",
  },
  { name: "web-app-security-code-review", description: "OWASP review", source: "prompt" },
  { name: "goal", description: "目标模式", source: "extension" },
  { name: "skill:image-gen", description: "画图", source: "skill" },
  { name: "compact", description: "压缩上下文" },
];

test("只留 source=prompt 的条目，扩展/技能/无来源一律剔除", () => {
  const items = importedPrompts(commands);
  assert.deepEqual(items.map((item) => item.name), [
    "todo-comment-debt-tracker",
    "web-app-security-code-review",
  ]);
  assert.equal(items[0]!.description, "Scans a codebase for TODO / FIXME");
  assert.equal(isPromptCommand({ name: "x", source: "Prompt" }), true);
  assert.equal(isPromptCommand({ name: "x" }), false);
});

test("按名字排序（localeCompare），与电脑端模板面板一致", () => {
  const items = importedPrompts([
    { name: "zeta", source: "prompt" },
    { name: "Alpha", source: "prompt" },
    { name: "beta", source: "prompt" },
  ]);
  assert.deepEqual(items.map((item) => item.name), ["Alpha", "beta", "zeta"]);
});

test("同名去重、去掉前导斜线、脏数据丢弃", () => {
  const items = importedPrompts([
    { name: "/dup", source: "prompt" },
    { name: "dup", source: "prompt" },
    { name: "   ", source: "prompt" },
    { name: undefined as unknown as string, source: "prompt" },
    { name: "keep", source: "prompt", description: 42 as unknown as string },
  ]);
  assert.deepEqual(items.map((item) => item.name), ["dup", "keep"]);
  // 描述不是字符串时按空串处理，别把数字塞进 DOM
  assert.equal(items[1]!.description, "");
});

test("非法输入不炸", () => {
  assert.deepEqual(importedPrompts([]), []);
  assert.deepEqual(importedPrompts(null as unknown as []), []);
});

test("本地过滤：名字与描述都能命中，空查询返回全部", () => {
  const items = importedPrompts(commands);
  assert.deepEqual(filterPromptEntries(items, "owasp").map((item) => item.name), [
    "web-app-security-code-review",
  ]);
  assert.deepEqual(filterPromptEntries(items, "TODO").map((item) => item.name), [
    "todo-comment-debt-tracker",
  ]);
  assert.equal(filterPromptEntries(items, "  ").length, 2);
  assert.deepEqual(filterPromptEntries(items, "zzz"), []);
});

test("插入文本是 /名字 + 空格", () => {
  assert.equal(promptInsertText({ name: "review", description: "" }), "/review ");
});

test("当前会话没运行实例时借一个（优先 idle），一个都没有才返回 null", () => {
  const runtimes = [
    { sessionId: "a", status: "running" },
    { sessionId: "b", status: "idle" },
  ];
  // 当前会话有运行实例就用它
  assert.equal(promptCommandSource(runtimes, "a"), "a");
  // 当前会话不在运行时表里（新建会话/草稿）→ 借 idle 的
  assert.equal(promptCommandSource(runtimes, "c"), "b");
  assert.equal(promptCommandSource(runtimes, null), "b");
  // 全是 running 时退回第一个
  assert.equal(promptCommandSource([{ sessionId: "x", status: "running" }], "c"), "x");
  assert.equal(promptCommandSource([], "c"), null);
});

test("空态文案指向电脑端的提示词库（导入）而不是下载", () => {
  assert.match(PROMPT_EMPTY_HINT, /提示词库/);
  assert.match(PROMPT_EMPTY_HINT, /~\/\.pi\/agent\/prompts/);
  assert.doesNotMatch(PROMPT_EMPTY_HINT, /下载/);
});
