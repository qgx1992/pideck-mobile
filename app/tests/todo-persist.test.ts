/**
 * 待办历史与「已收口」标记的本地持久化。
 *
 * 用户反馈：清除掉的清单在重启 App 后又出现 —— 根因就是「已收口」只存在内存里。
 * 这里锁住落盘行为（含脏数据容错与按会话隔离）。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/" });
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.localStorage = dom.window.localStorage;

const { loadDismissedTodo, saveDismissedTodo, clearDismissedTodo, loadTodoHistory, saveTodoHistory } =
  await import("../src/platform/todo-history.ts");

test("已收口标记能落盘：重启后（新建 Map）仍读得到", () => {
  saveDismissedTodo("http://a::s1", { planId: "7", signature: "sig-7" });
  assert.deepEqual(loadDismissedTodo("http://a::s1"), { planId: "7", signature: "sig-7" });
});

test("按会话隔离：另一台设备/另一个会话互不影响", () => {
  saveDismissedTodo("http://a::s1", { planId: "7", signature: "sig-7" });
  assert.equal(loadDismissedTodo("http://b::s1"), null);
  assert.equal(loadDismissedTodo("http://a::s2"), null);
});

test("清除 / 脏数据都返回 null，不炸", () => {
  saveDismissedTodo("http://a::s1", { planId: "7", signature: "sig-7" });
  saveDismissedTodo("http://a::s1", null);
  assert.equal(loadDismissedTodo("http://a::s1"), null);

  localStorage.setItem("pideck-todo-dismissed-v1-http://a::bad", "{oops");
  assert.equal(loadDismissedTodo("http://a::bad"), null);
  localStorage.setItem("pideck-todo-dismissed-v1-http://a::arr", "[]");
  assert.equal(loadDismissedTodo("http://a::arr"), null);
  localStorage.setItem("pideck-todo-dismissed-v1-http://a::empty", JSON.stringify({ planId: "", signature: "" }));
  assert.equal(loadDismissedTodo("http://a::empty"), null, "空身份等于没有");
  clearDismissedTodo("http://a::none");
});

test("历史与已收口各用各的键，互不覆盖", () => {
  const scope = "http://a::s9";
  saveDismissedTodo(scope, { planId: "9", signature: "sig-9" });
  saveTodoHistory(scope, [
    {
      id: "9@1000",
      planId: "9",
      items: [{ id: "0", text: "任务", status: "completed" }],
      total: 1,
      done: 1,
      active: 0,
      firstSeenAt: 1_000,
      lastChangedAt: 1_000,
      finishedAt: 2_000,
      sealed: true,
    } as never,
  ]);
  assert.equal(loadTodoHistory(scope).length, 1);
  assert.deepEqual(loadDismissedTodo(scope), { planId: "9", signature: "sig-9" });
});
