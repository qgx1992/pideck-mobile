/**
 * 分支家族推导 + 单条消息能做什么（都是纯函数）。
 *
 * 这两个模型直接决定 UI 行为：家族长度 ≤1 时不显示分支条，
 * 工具/系统消息不该弹出菜单。所以值得单独覆盖。
 */

import test from "node:test";
import assert from "node:assert/strict";

const { branchNodes, deriveBranchFamily, shortBranchTitle, shouldShowBranchBar } = await import(
  "../src/ui/branch-model.ts"
);
const { actionsForRole, confirmTextFor, forkEntryIdOf, hasActions, messageIdOf } = await import(
  "../src/ui/message-actions-model.ts"
);

type S = {
  id: string;
  projectId: string;
  title: string;
  status: string;
  parentSessionId?: string | null;
  forked?: boolean;
  createdAt?: number;
};

const session = (id: string, createdAt: number, parentSessionId?: string): S => ({
  id,
  projectId: "p1",
  title: `会话 ${id}`,
  status: "active",
  createdAt,
  ...(parentSessionId ? { parentSessionId, forked: true } : {}),
});

test("没有分叉时家族只有自己", () => {
  const sessions = [session("a", 1), session("b", 2)] as never[];
  const family = deriveBranchFamily(sessions, "a");
  assert.deepEqual(family.map((s) => s.id), ["a"]);
  assert.equal(shouldShowBranchBar(branchNodes(sessions, "a")), false);
});

test("从子会话也能找到根并收集全部兄弟", () => {
  const sessions = [session("root", 1), session("a", 2, "root"), session("b", 3, "root")] as never[];
  assert.deepEqual(deriveBranchFamily(sessions, "a").map((s) => s.id), ["root", "a", "b"]);
  assert.deepEqual(deriveBranchFamily(sessions, "root").map((s) => s.id), ["root", "a", "b"]);
  assert.equal(shouldShowBranchBar(branchNodes(sessions, "a")), true);
});

test("多层分叉也按 createdAt 升序展开", () => {
  const sessions = [
    session("root", 10),
    session("a", 20, "root"),
    session("a1", 30, "a"),
    session("b", 25, "root"),
  ] as never[];
  assert.deepEqual(deriveBranchFamily(sessions, "a1").map((s) => s.id), ["root", "a", "b", "a1"]);
});

test("脏数据（互相指父）不会死循环", () => {
  const sessions = [session("x", 1, "y"), session("y", 2, "x")] as never[];
  const family = deriveBranchFamily(sessions, "x");
  assert.ok(family.length >= 1 && family.length <= 2);
});

test("未知会话返回空", () => {
  assert.deepEqual(deriveBranchFamily([], "nope"), []);
  assert.deepEqual(branchNodes([], null), []);
});

test("分支 chip 的短标题会截断", () => {
  assert.equal(shortBranchTitle("短标题"), "短标题");
  assert.equal(shortBranchTitle("一二三四五六七八九十一二三"), "一二三四五六七八九十一二…");
  assert.equal(shortBranchTitle("  "), "");
});

test("用户消息有五个动作，助手只有复制和分叉", () => {
  const user = actionsForRole("user").map((a) => a.id);
  assert.deepEqual(user, ["copy", "edit", "resend", "fork", "delete"]);
  const assistant = actionsForRole("assistant").map((a) => a.id);
  assert.deepEqual(assistant, ["copy", "fork"]);
  // 只有删除是危险操作
  assert.deepEqual(
    actionsForRole("user").filter((a) => a.danger).map((a) => a.id),
    ["delete"],
  );
});

test("工具/系统/未知角色不给菜单", () => {
  for (const role of ["tool", "system", "weird"]) {
    assert.deepEqual(actionsForRole(role), []);
    assert.equal(hasActions(role), false);
  }
});

test("消息 id 的兜底顺序：id → entryId → meta.entryId", () => {
  assert.equal(messageIdOf({ id: "m1" }), "m1");
  assert.equal(messageIdOf({ entryId: "e1" }), "e1");
  assert.equal(messageIdOf({ meta: { entryId: "me1" } }), "me1");
  assert.equal(messageIdOf({}), null);
  assert.equal(messageIdOf({ id: "   " }), null);
});

test("fork 优先用 entryId", () => {
  assert.equal(forkEntryIdOf({ id: "m1", entryId: "e1" }), "e1");
  assert.equal(forkEntryIdOf({ id: "m1" }), "m1");
  assert.equal(forkEntryIdOf({}), null);
});

test("删除需要二次确认文案", () => {
  assert.match(confirmTextFor("delete"), /不可恢复/);
  assert.equal(confirmTextFor("edit"), "");
});
