/**
 * 侧栏三分段（活动 / 聊天 / 项目）的数据模型。
 *
 * 对齐桌面端 SidebarContent 的分段语义：
 *   活动 = 活着的运行时绑定的会话 + 最近会话
 *   聊天 = 只显示内置 Chat 项目
 *   项目 = 只显示工作区项目
 */

import test from "node:test";
import assert from "node:assert/strict";

const {
  ACTIVITY_RECENT_LIMIT,
  SIDEBAR_TABS,
  buildActivityView,
  chatProject,
  chatSessions,
  isChatProject,
  parseSidebarTab,
  searchSessions,
  sidebarTabLabel,
  tabCounts,
  workspaceProjects,
} = await import("../src/ui/sidebar-tabs-model.ts");

const projects = [
  { id: "p-chat", kind: "chat", name: "Chat" },
  { id: "p-a", name: "alpha", path: "C:/work/alpha" },
  { id: "p-b", name: "beta", path: "C:/work/beta" },
];

const sessions = [
  { id: "s1", projectId: "p-chat", title: "问候", updatedAt: 100 },
  { id: "s2", projectId: "p-a", title: "修 CI", updatedAt: 500 },
  { id: "s3", projectId: "p-b", title: "写文档", updatedAt: 300 },
  { id: "s4", projectId: "p-a", title: "重构侧栏", updatedAt: 400 },
];

const state = (runtimes: unknown[] = []) =>
  ({ projects, sessions, runtimes }) as never;

test("分段列表与解析", () => {
  assert.deepEqual(SIDEBAR_TABS.map((t) => t.id), ["active", "chats", "projects"]);
  assert.equal(sidebarTabLabel("active"), "活动");
  assert.equal(sidebarTabLabel("chats"), "聊天");
  assert.equal(sidebarTabLabel("projects"), "项目");
  assert.equal(parseSidebarTab("chats"), "chats");
  assert.equal(parseSidebarTab("nope"), null);
  assert.equal(parseSidebarTab(null), null);
});

test("内置 Chat 项目从「项目」分段里摘出去", () => {
  assert.equal(isChatProject(projects[0]), true);
  assert.equal(isChatProject(projects[1]), false);
  assert.deepEqual(workspaceProjects(projects as never).map((p) => p.id), ["p-a", "p-b"]);
  assert.equal(chatProject(projects as never)?.id, "p-chat");
});

test("聊天分段只列内置 Chat 的会话，按更新时间倒序", () => {
  assert.deepEqual(chatSessions(state()).map((s) => s.id), ["s1"]);
  // 没有 chat 项目时为空
  assert.deepEqual(chatSessions({ projects: [], sessions } as never), []);
});

test("活动分段：活着的运行时进上半区，其余进最近会话", () => {
  const view = buildActivityView(
    state([
      { sessionId: "s2", status: "running" },
      { sessionId: "s4", status: "idle" },
      { sessionId: "s3", status: "stopped" },
    ]),
  );
  assert.deepEqual(view.live.map((r) => r.session.id), ["s2", "s4"]);
  assert.equal(view.live[0]!.running, true, "running 才带脉冲点");
  assert.equal(view.live[1]!.running, false, "挂着但没在跑");
  assert.equal(view.live[0]!.projectLabel, "alpha");
  // s3 的 runtime 已 stopped → 退回最近会话
  assert.deepEqual(view.recent.map((r) => r.session.id), ["s3", "s1"]);
});

test("最近会话有条数上限", () => {
  const many = Array.from({ length: 30 }, (_, index) => ({
    id: `x${index}`,
    projectId: "p-a",
    title: `会话 ${index}`,
    updatedAt: index,
  }));
  const view = buildActivityView({ projects, sessions: many, runtimes: [] } as never);
  assert.equal(view.recent.length, ACTIVITY_RECENT_LIMIT);
  // 倒序：最新的在前
  assert.equal(view.recent[0]!.session.id, "x29");
});

test("没有任何 runtime 时上半区为空", () => {
  const view = buildActivityView(state());
  assert.deepEqual(view.live, []);
  assert.equal(view.recent.length, 4);
});

test("搜索跨项目匹配标题或项目名", () => {
  assert.deepEqual(searchSessions(state(), "ci").map((r) => r.session.id), ["s2"]);
  assert.deepEqual(searchSessions(state(), "beta").map((r) => r.session.id), ["s3"]);
  // 空查询不返回结果（由调用方决定显示分段）
  assert.deepEqual(searchSessions(state(), "   "), []);
  // 行上带项目名，窄屏扁平列表才看得出消息从哪来
  assert.equal(searchSessions(state(), "重构")[0]!.projectLabel, "alpha");
});

test("分段计数用于提示有没有东西", () => {
  const counts = tabCounts(state([{ sessionId: "s2", status: "running" }]));
  assert.equal(counts.active, 4);
  assert.equal(counts.chats, 1);
  assert.equal(counts.projects, 2);
});
