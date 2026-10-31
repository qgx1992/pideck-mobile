/**
 * 计划模式的发送载荷。
 *
 * 这是与桌面端内置扩展的**协议**，标记或指令文本写错一点，扩展就识别不到，
 * 标记会原样进 prompt（比不加计划模式更糟）。所以逐字钉住。
 */

import test from "node:test";
import assert from "node:assert/strict";

const { PI_DECK_PLAN_MODE_MARKER, buildPlanSubmission, shouldAutoExitPlan } = await import(
  "../src/ui/plan-mode.ts"
);

test("标记必须与桌面端 composerModeMarkers 一致", () => {
  assert.equal(PI_DECK_PLAN_MODE_MARKER, "__PI_DECK_PLAN_MODE__");
});

test("普通模式：原样返回，不带 agentMessage", () => {
  const submission = buildPlanSubmission("帮我看下这个 bug", false);
  assert.equal(submission.message, "帮我看下这个 bug");
  assert.equal(submission.planMode, false);
  assert.equal(submission.agentMessage, undefined);
});

test("计划模式：可见消息保持原文，标记只在 agentMessage 里", () => {
  const submission = buildPlanSubmission("重构侧栏", true);
  assert.equal(submission.message, "重构侧栏", "时间线上显示的必须是原文");
  assert.equal(submission.planMode, true);
  assert.ok(submission.agentMessage);
  assert.ok(submission.agentMessage!.startsWith(PI_DECK_PLAN_MODE_MARKER), "标记要在最前面");
  assert.ok(!submission.message.includes(PI_DECK_PLAN_MODE_MARKER), "标记不能泄漏到可见消息");
});

test("计划模式的 agentMessage 结构与桌面端一致", () => {
  const lines = buildPlanSubmission("重构侧栏", true).agentMessage!.split("\n");
  assert.equal(lines[0], PI_DECK_PLAN_MODE_MARKER);
  assert.equal(lines[1], "重构侧栏");
  assert.equal(lines[2], "");
  assert.match(lines[3]!, /只做只读分析/);
  assert.equal(lines[4], "Plan:");
  assert.equal(lines[5], "1. 第一步");
  assert.equal(lines[6], "2. 第二步");
});

test("计划模式下空输入：给一句兜底指令，可见消息仍为空", () => {
  const submission = buildPlanSubmission("   ", true);
  assert.equal(submission.message.trim(), "");
  assert.match(submission.agentMessage!, /先制定实施计划/);
});

test("斜线命令不包标记（/plan off 这类要在计划模式下也能用）", () => {
  const submission = buildPlanSubmission("/plan off", true);
  assert.equal(submission.planMode, false);
  assert.equal(submission.agentMessage, undefined);
  assert.equal(submission.message, "/plan off");
});

test("计划模式是一次性的：发送后应自动关", () => {
  assert.equal(shouldAutoExitPlan(true), true);
  assert.equal(shouldAutoExitPlan(false), false);
});
