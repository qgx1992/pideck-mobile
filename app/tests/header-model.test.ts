/** 顶部栏视图模型：标题、状态、运行标记、文档标题。 */

import test from "node:test";
import assert from "node:assert/strict";
import { buildHeaderView, projectLabelOf } from "../src/ui/header-model.ts";

const projects = [
  { id: "p-chat", kind: "chat", name: "Chat" },
  { id: "p-a", name: "alpha", path: "C:/work/alpha" },
];

const sessions = [
  { id: "s1", projectId: "p-a", title: "修 CI", status: "active", model: { provider: "magpie", modelId: "auto/x", modelName: "Deepseek-V4.1-Flash" }, thinkingLevel: "high" },
  { id: "s2", projectId: "p-a", title: "空会话", status: "draft" },
];

function state(runtimes: unknown[] = []) {
  return { projects, sessions, runtimes, pendingUiRequests: [] } as never;
}

test("标题行右侧是「项目 · 状态」，第二行只留模型与思考", () => {
  const view = buildHeaderView(state(), "s1", "p-a");
  assert.equal(view.title, "修 CI");
  assert.equal(view.meta, "alpha · 已就绪");
  assert.equal(view.subtitle, "Deepseek-V4.1-Flash · 思考 high");
  assert.equal(view.docTitle, "修 CI · PiDeck");
});

test("没有会话时回落到项目名，两行都为空", () => {
  const view = buildHeaderView(state(), null, "p-a");
  assert.equal(view.title, "alpha");
  assert.equal(view.meta, "");
  assert.equal(view.subtitle, "");
  assert.equal(view.docTitle, "PiDeck");
});

test("都没有时回落到 PiDeck", () => {
  const view = buildHeaderView(state(), null, null);
  assert.equal(view.title, "PiDeck");
  assert.equal(view.isRunning, false);
});

test("运行时 running 优先于 session.status", () => {
  const runtimes = [{ sessionId: "s1", agentId: "a", runtimeGeneration: 1, status: "running" }];
  const view = buildHeaderView(state(runtimes), "s1", "p-a");
  assert.equal(view.statusLabel, "运行中");
  assert.equal(view.isRunning, true);
  assert.ok(view.meta.includes("运行中"), `meta 应含状态，实际：${view.meta}`);
});

test("运行时 idle 时不标记 running", () => {
  const runtimes = [{ sessionId: "s1", agentId: "a", runtimeGeneration: 1, status: "idle" }];
  const view = buildHeaderView(state(runtimes), "s1", "p-a");
  assert.equal(view.statusLabel, "空闲");
  assert.equal(view.isRunning, false);
});

test("没有运行时用 session.status 兜底（draft）", () => {
  const view = buildHeaderView(state(), "s2", "p-a");
  assert.equal(view.statusLabel, "草稿");
});

test("未知状态码原样展示", () => {
  const runtimes = [{ sessionId: "s1", agentId: "a", runtimeGeneration: 1, status: "weird-state" }];
  assert.equal(buildHeaderView(state(runtimes), "s1", "p-a").statusLabel, "weird-state");
});

test("切换会话时标题/副标题必须不同（回归：之前不刷新）", () => {
  const first = buildHeaderView(state(), "s1", "p-a");
  const second = buildHeaderView(state(), "s2", "p-a");
  assert.notEqual(first.title, second.title);
  assert.notEqual(first.docTitle, second.docTitle);
});

test("projectLabelOf 规则", () => {
  assert.equal(projectLabelOf({ id: "x", kind: "chat" }), "Chat");
  assert.equal(projectLabelOf({ id: "x", path: "D:/repos/beta/" }), "beta");
  assert.equal(projectLabelOf(undefined), undefined);
});
