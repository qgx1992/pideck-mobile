/**
 * 空态提示词 + 「选择工作区」的排序规则。
 *
 * 提示词是产品文案，但「Chat 项目里不该出现审查 diff」这种规则值得钉住；
 * 排序规则决定用户新建会话时第一个看到哪个项目。
 */

import test from "node:test";
import assert from "node:assert/strict";

const {
  CHAT_SUGGESTIONS,
  PROJECT_SUGGESTIONS,
  emptyStateSubtitle,
  emptyStateTitle,
  suggestionContext,
  suggestionsFor,
} = await import("../src/ui/empty-suggestions.ts");
const { sessionCountByProject, sortProjectsForPicker } = await import("../src/ui/sidebar-model.ts");

test("项目场景就是图上的三条", () => {
  assert.deepEqual([...PROJECT_SUGGESTIONS], [
    "介绍一下这个项目的整体情况",
    "运行测试并修复失败的部分",
    "审查我尚未提交的改动",
  ]);
  assert.equal(suggestionsFor("project"), PROJECT_SUGGESTIONS);
});

test("Chat 场景换一套（没有 git 可审）", () => {
  assert.equal(suggestionsFor("chat"), CHAT_SUGGESTIONS);
  assert.equal(CHAT_SUGGESTIONS.length, 3);
  assert.ok(!CHAT_SUGGESTIONS.some((item) => item.includes("提交")));
});

test("项目类型推断", () => {
  assert.equal(suggestionContext({ kind: "chat" }), "chat");
  assert.equal(suggestionContext({ kind: "project" }), "project");
  assert.equal(suggestionContext(undefined), "project");
  assert.equal(suggestionContext(null), "project");
});

test("标题与副标题", () => {
  assert.match(emptyStateTitle(), /PiDeck/);
  assert.match(emptyStateSubtitle("project"), /发送一条消息/);
  assert.match(emptyStateSubtitle("chat"), /发送一条消息/);
});

/* ------------------------------ 工作区排序 ------------------------------ */

const project = (id: string, extra: Record<string, unknown> = {}) => ({
  id,
  name: id,
  ...extra,
});
const session = (projectId: string, updatedAt: number) => ({ id: `s-${projectId}-${updatedAt}`, projectId, updatedAt });

test("内置 Chat 永远置顶", () => {
  const sorted = sortProjectsForPicker(
    [project("p-a"), project("p-chat", { kind: "chat" }), project("p-b")] as never,
    [session("p-a", 9_999)] as never,
  );
  assert.equal(sorted[0]!.id, "p-chat");
});

test("其余按最近会话时间倒序", () => {
  const sorted = sortProjectsForPicker(
    [project("p-a"), project("p-b"), project("p-c")] as never,
    [session("p-a", 100), session("p-c", 900), session("p-b", 500)] as never,
  );
  assert.deepEqual(sorted.map((item) => item.id), ["p-c", "p-b", "p-a"]);
});

test("都没会话时按名字排", () => {
  const sorted = sortProjectsForPicker(
    [project("p-b", { name: "beta" }), project("p-a", { name: "alpha" })] as never,
    [] as never,
  );
  assert.deepEqual(sorted.map((item) => item.name), ["alpha", "beta"]);
});

test("不修改入参数组", () => {
  const input = [project("p-a"), project("p-chat", { kind: "chat" })] as never[];
  sortProjectsForPicker(input as never, [] as never);
  assert.equal(input[0]!.id, "p-a");
});

test("会话数统计", () => {
  const counts = sessionCountByProject([session("p-a", 1), session("p-a", 2), session("p-b", 3)] as never);
  assert.equal(counts.get("p-a"), 2);
  assert.equal(counts.get("p-b"), 1);
  assert.equal(counts.get("p-z"), undefined);
});
