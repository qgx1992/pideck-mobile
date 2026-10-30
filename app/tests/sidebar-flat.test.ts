/**
 * 侧栏扁平列表（活动 / 聊天 / 搜索结果共用）。
 *
 * 与项目分组不同：没有项目头，项目名挂在行上；跨项目列表里「这条消息从哪来」
 * 全靠行上的 projectLabel，所以单独覆盖。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>");
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.HTMLElement = dom.window.HTMLElement;
g.Node = dom.window.Node;
g.Event = dom.window.Event;

const { renderFlatSection } = await import("../src/ui/sidebar-view.ts");

function makeCtx(activeSessionId: string | null = null) {
  const calls = { open: [] as string[], menu: [] as string[] };
  return {
    calls,
    ctx: {
      activeSessionId,
      activeProjectId: null,
      handlers: {
        onToggleProject: () => undefined,
        onOpenSession: (id: string) => calls.open.push(id),
        onCreateSession: () => undefined,
        onSessionMenu: (id: string) => calls.menu.push(id),
        onProjectMenu: () => undefined,
        onToggleSessions: () => undefined,
      },
    },
  };
}

const row = (id: string, extra: Record<string, unknown> = {}) => ({
  session: { id, projectId: "p1", title: `会话 ${id}`, updatedAt: 100, ...extra } as never,
  projectLabel: "alpha",
});

test("标题 + 行 + 项目名", () => {
  const { ctx } = makeCtx();
  const el = renderFlatSection([row("s1"), row("s2")], ctx, { title: "最近会话" })!;
  assert.ok(el);
  assert.equal(el.querySelector(".flat-title")?.textContent, "最近会话");
  const rows = [...el.querySelectorAll(".session")];
  assert.equal(rows.length, 2);
  assert.match(rows[0]!.textContent ?? "", /alpha/);
});

test("激活会话带 is-active，运行中的带 is-running", () => {
  const { ctx } = makeCtx("s2");
  const el = renderFlatSection([row("s1"), { ...row("s2"), running: true }], ctx)!;
  const rows = [...el.querySelectorAll(".session")];
  assert.equal(rows[0]!.classList.contains("is-active"), false);
  assert.equal(rows[1]!.classList.contains("is-active"), true);
  assert.equal(rows[1]!.classList.contains("session--running"), true);
});

test("超过 preview 才折叠，点击回调 onToggle", () => {
  const { ctx } = makeCtx();
  const rows = Array.from({ length: 8 }, (_, i) => row(`s${i}`));

  const collapsed = renderFlatSection(rows, ctx, { preview: 6, onToggle: () => undefined })!;
  assert.equal(collapsed.querySelectorAll(".session").length, 6);
  const more = collapsed.querySelector(".sessions-more");
  assert.ok(more);
  assert.match(more!.textContent ?? "", /展开其余 2 个/);

  let toggled = 0;
  const expanded = renderFlatSection(rows, ctx, {
    preview: 6,
    expanded: true,
    onToggle: () => (toggled += 1),
  })!;
  assert.equal(expanded.querySelectorAll(".session").length, 8);
  (expanded.querySelector(".sessions-more") as unknown as HTMLElement).click();
  assert.equal(toggled, 1);
});

test("没有溢出时不出现展开按钮", () => {
  const { ctx } = makeCtx();
  const el = renderFlatSection([row("s1")], ctx, { preview: 6, onToggle: () => undefined })!;
  assert.equal(el.querySelector(".sessions-more"), null);
});

test("hideWhenEmpty：没数据整块不渲染", () => {
  const { ctx } = makeCtx();
  assert.equal(renderFlatSection([], ctx, { title: "运行中", hideWhenEmpty: true }), null);
});

test("空列表给出空态文案", () => {
  const { ctx } = makeCtx();
  const el = renderFlatSection([], ctx, { empty: "还没有会话" })!;
  assert.equal(el.querySelector(".group-empty")?.textContent, "还没有会话");
});

test("点击行打开会话", () => {
  const { ctx, calls } = makeCtx();
  const el = renderFlatSection([row("s1")], ctx)!;
  (el.querySelector(".session") as unknown as HTMLElement).click();
  assert.deepEqual(calls.open, ["s1"]);
});
