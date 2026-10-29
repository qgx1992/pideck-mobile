/** 任务清单：解析、进度、折叠、渲染。 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>");
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.Node = dom.window.Node;
g.HTMLElement = dom.window.HTMLElement;

const { parseSessionTodo, shouldAutoExpand, todoProgressLabel, todoTitle } = await import(
  "../src/ui/todo-model.ts"
);
const { createTodoStrip } = await import("../src/ui/todo-strip.ts");

const realWorld = {
  planId: 8,
  todos: [
    { id: 47, text: "读 composer 结构", status: "completed" },
    { id: 48, text: "把 @ / 移进输入框", status: "completed" },
    { id: 49, text: "模型面板加搜索", status: "in_progress" },
    { id: 50, text: "加上下文按钮", status: "pending" },
  ],
};

test("解析真实返回形状（planId + todos）", () => {
  const view = parseSessionTodo(realWorld);
  assert.ok(view);
  assert.equal(view?.planId, "8");
  assert.equal(view?.total, 4);
  assert.equal(view?.done, 2);
  assert.equal(view?.active, 1);
  assert.equal(view?.unfinished, true);
  assert.equal(todoProgressLabel(view!), "2/4");
});

test("也接受外面多包一层 { todo } 的写法", () => {
  const view = parseSessionTodo({ todo: realWorld });
  assert.equal(view?.total, 4);
});

test("空清单 / 非法输入返回 null（不显示条带）", () => {
  assert.equal(parseSessionTodo(null), null);
  assert.equal(parseSessionTodo({}), null);
  assert.equal(parseSessionTodo({ planId: 1, todos: [] }), null);
  assert.equal(parseSessionTodo({ todos: [{ id: 1, text: "   " }] }), null);
});

test("status 变体都能归一化，未知值当 pending", () => {
  const view = parseSessionTodo({
    todos: [
      { id: 1, text: "a", status: "done" },
      { id: 2, text: "b", status: "running" },
      { id: 3, text: "c", status: "weird" },
    ],
  });
  assert.deepEqual(view?.items.map((item) => item.status), ["completed", "in_progress", "pending"]);
});

test("有进行中才自动展开", () => {
  assert.equal(shouldAutoExpand(parseSessionTodo(realWorld)), true);
  assert.equal(shouldAutoExpand(parseSessionTodo({ todos: [{ id: 1, text: "a", status: "pending" }] })), false);
  assert.equal(shouldAutoExpand(null), false);
});

test("全部完成时 title 说明已完成", () => {
  const view = parseSessionTodo({ todos: [{ id: 1, text: "a", status: "completed" }] })!;
  assert.equal(view.unfinished, false);
  assert.ok(todoTitle(view).includes("已全部完成"));
});

test("渲染：条带结构、状态图标、进度", () => {
  const strip = createTodoStrip();
  strip.update(parseSessionTodo(realWorld), { collapsed: false });

  assert.equal(strip.el.classList.contains("is-hidden"), false);
  assert.equal(strip.el.querySelector(".todo-progress")?.textContent, "2/4");
  const items = strip.el.querySelectorAll(".todo-item");
  assert.equal(items.length, 4);
  assert.equal(items[0]?.classList.contains("is-completed"), true);
  assert.equal(items[2]?.classList.contains("is-in_progress"), true);
  assert.equal(items[3]?.classList.contains("is-pending"), true);
  assert.ok(items[0]?.querySelector(".todo-check"), "完成项有对勾");
  assert.ok(items[2]?.querySelector(".todo-running"), "进行中有脉冲点");
  assert.ok(items[3]?.querySelector(".todo-dot"), "待办是空心圈");
  assert.equal(strip.el.classList.contains("has-active"), true);
});

test("折叠 / 展开 + 隐藏", () => {
  const strip = createTodoStrip();
  strip.update(parseSessionTodo(realWorld), { collapsed: false });
  const list = strip.el.querySelector(".todo-list") as HTMLElement;
  assert.equal(list.style.display, "");

  strip.update(parseSessionTodo(realWorld), { collapsed: true });
  assert.equal(list.style.display, "none");
  assert.equal(strip.el.querySelector(".chev-icon")?.classList.contains("is-expanded"), false);

  strip.update(null);
  assert.equal(strip.el.classList.contains("is-hidden"), true);
  assert.equal(strip.el.querySelector(".todo-progress")?.textContent, "", "隐藏时别留上一个会话的进度");
  assert.equal(strip.el.querySelectorAll(".todo-item").length, 0);
});

test("点头部能就地折叠，不依赖外部重渲染", () => {
  const strip = createTodoStrip();
  strip.update(parseSessionTodo(realWorld), { collapsed: false });
  const list = strip.el.querySelector(".todo-list") as HTMLElement;
  (strip.el.querySelector(".todo-head") as HTMLElement).click();
  assert.equal(list.style.display, "none");
  (strip.el.querySelector(".todo-head") as HTMLElement).click();
  assert.equal(list.style.display, "");
});
