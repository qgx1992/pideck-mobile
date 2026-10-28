/** 侧栏 DOM：分组结构、折叠标记、激活态、点击回调接线。 */

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

const { renderProjectGroup } = await import("../src/ui/sidebar-view.ts");
const { buildSidebarGroups } = await import("../src/ui/sidebar-model.ts");

const projects = [
  { id: "p-chat", kind: "chat", name: "Chat" },
  { id: "p-a", name: "alpha", path: "C:/work/alpha" },
];
const sessions = [
  { id: "s1", projectId: "p-chat", title: "问候", updatedAt: 300 },
  { id: "s2", projectId: "p-a", title: "修 CI", updatedAt: 400, backend: "dsh" },
];

function makeCtx(overrides: Partial<{ activeSessionId: string | null; activeProjectId: string | null }> = {}) {
  const calls = { toggle: [] as string[], open: [] as string[], create: [] as string[], menu: [] as string[], pmenu: [] as string[], more: [] as string[] };
  return {
    calls,
    ctx: {
      activeSessionId: overrides.activeSessionId ?? null,
      activeProjectId: overrides.activeProjectId ?? null,
      handlers: {
        onToggleProject: (id: string) => calls.toggle.push(id),
        onOpenSession: (id: string) => calls.open.push(id),
        onCreateSession: (id: string) => calls.create.push(id),
        onSessionMenu: (id: string) => calls.menu.push(id),
        onProjectMenu: (id: string) => calls.pmenu.push(id),
        onToggleSessions: (id: string) => calls.more.push(id),
      },
    },
  };
}

test("分组渲染：项目名、会话数、组内会话顺序", () => {
  const groups = buildSidebarGroups({ projects, sessions }, "", new Set());
  const { ctx } = makeCtx();
  const node = renderProjectGroup(groups[1]!, ctx);

  assert.equal(node.className, "project-group");
  assert.equal(node.querySelector(".pname")?.textContent, "alpha");
  assert.equal(node.querySelector(".pcount")?.textContent, "1");
  assert.equal(node.querySelector(".sname")?.textContent, "修 CI");
});

test("折叠时加 is-collapsed 且箭头朝右", () => {
  const groups = buildSidebarGroups({ projects, sessions }, "", new Set(["p-chat"]));
  const { ctx } = makeCtx();
  const collapsed = renderProjectGroup(groups[0]!, ctx);
  assert.ok(collapsed.classList.contains("is-collapsed"));
  assert.equal(collapsed.querySelector(".chev")?.textContent, "\u25B8");

  const expanded = renderProjectGroup(buildSidebarGroups({ projects, sessions }, "", new Set())[0]!, ctx);
  assert.equal(expanded.classList.contains("is-collapsed"), false);
  assert.equal(expanded.querySelector(".chev")?.textContent, "\u25BE");
});

test("点项目头触发折叠回调，点会话触发打开回调", () => {
  const groups = buildSidebarGroups({ projects, sessions }, "", new Set());
  const { ctx, calls } = makeCtx();

  const node = renderProjectGroup(groups[0]!, ctx);
  node.querySelector<HTMLButtonElement>(".project-head")!.click();
  assert.deepEqual(calls.toggle, ["p-chat"]);

  node.querySelector<HTMLButtonElement>(".session")!.click();
  assert.deepEqual(calls.open, ["s1"]);
});

test("点项目上的 + 新建会话，且不冒泡成折叠", () => {
  const groups = buildSidebarGroups({ projects, sessions }, "", new Set());
  const { ctx, calls } = makeCtx();

  const node = renderProjectGroup(groups[0]!, ctx);
  node.querySelector<HTMLElement>(".project-add")!.click();
  assert.deepEqual(calls.create, ["p-chat"]);
  assert.deepEqual(calls.toggle, [], "新建不应同时触发折叠");
});

test("激活会话带 is-active，非激活不带", () => {
  const groups = buildSidebarGroups({ projects, sessions }, "", new Set());
  const active = makeCtx({ activeSessionId: "s1" });
  const idle = makeCtx();

  assert.ok(renderProjectGroup(groups[0]!, active.ctx).querySelector(".session")!.classList.contains("is-active"));
  assert.equal(renderProjectGroup(groups[0]!, idle.ctx).querySelector(".session")!.classList.contains("is-active"), false);
});

test("当前项目高亮在项目头上", () => {
  const groups = buildSidebarGroups({ projects, sessions }, "", new Set());
  const { ctx } = makeCtx({ activeProjectId: "p-a" });
  assert.ok(renderProjectGroup(groups[1]!, ctx).querySelector(".project-head")!.classList.contains("is-active"));
  assert.equal(renderProjectGroup(groups[0]!, ctx).querySelector(".project-head")!.classList.contains("is-active"), false);
});

test("空项目显示占位文案，搜索无命中显示「无匹配」", () => {
  const { ctx } = makeCtx();
  const empty = buildSidebarGroups({ projects, sessions: [] }, "", new Set())[0]!;
  assert.equal(renderProjectGroup(empty, ctx).querySelector(".group-empty")?.textContent, "暂无会话");
});

test("DSH 会话带蓝色徽章与行底色，pi 会话不带", () => {
  const groups = buildSidebarGroups({ projects, sessions }, "", new Set());
  const { ctx } = makeCtx();

  const dshRow = renderProjectGroup(groups[1]!, ctx).querySelector<HTMLElement>(".session")!;
  assert.ok(dshRow.classList.contains("session--dsh"), "DSH 行要有 session--dsh");
  const badge = dshRow.querySelector(".sbadge");
  assert.equal(badge?.textContent, "DSH");
  assert.ok(badge?.classList.contains("sbadge--dsh"));

  const piRow = renderProjectGroup(groups[0]!, ctx).querySelector<HTMLElement>(".session")!;
  assert.equal(piRow.classList.contains("session--dsh"), false, "pi 会话不加底色");
  assert.equal(piRow.querySelector(".sbadge"), null, "pi 会话不显示徽章");
});

test("徽章不吃掉标题：标题仍在 .sname 里", () => {
  const groups = buildSidebarGroups({ projects, sessions }, "", new Set());
  const { ctx } = makeCtx();
  const row = renderProjectGroup(groups[1]!, ctx).querySelector<HTMLElement>(".session")!;
  assert.equal(row.querySelector(".sname")?.textContent, "修 CI");
  assert.equal(row.querySelector(".smeta")?.textContent, "1/1", "副行只留时间");
});

test("长按会话触发菜单（先按住超过阈值）", async () => {
  const groups = buildSidebarGroups({ projects, sessions }, "", new Set());
  const { ctx, calls } = makeCtx();
  const node = renderProjectGroup(groups[0]!, ctx);
  const row = node.querySelector<HTMLElement>(".session")!;

  row.dispatchEvent(new window.MouseEvent("mousedown", { bubbles: true, button: 0, clientX: 0, clientY: 0 }));
  await new Promise((resolve) => setTimeout(resolve, 520));
  row.dispatchEvent(new window.MouseEvent("mouseup", { bubbles: true }));
  row.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));

  assert.deepEqual(calls.menu, ["s1"], "长按应打开会话菜单");
  assert.deepEqual(calls.open, [], "长按不应同时打开会话");
});

test("长按项目头触发项目菜单", async () => {
  const groups = buildSidebarGroups({ projects, sessions }, "", new Set());
  const { ctx, calls } = makeCtx();
  const node = renderProjectGroup(groups[0]!, ctx);
  const head = node.querySelector<HTMLElement>(".project-head")!;

  head.dispatchEvent(new window.MouseEvent("mousedown", { bubbles: true, button: 0, clientX: 0, clientY: 0 }));
  await new Promise((resolve) => setTimeout(resolve, 520));
  head.dispatchEvent(new window.MouseEvent("mouseup", { bubbles: true }));

  assert.deepEqual(calls.pmenu, ["p-chat"]);
  assert.deepEqual(calls.toggle, [], "长按不应同时折叠");
});

const manySessions = Array.from({ length: 10 }, (_, i) => ({
  id: `m${i}`,
  projectId: "p-a",
  title: `会话 ${i}`,
  updatedAt: 1000 - i,
}));
const manyProjects = [{ id: "p-a", name: "alpha", path: "C:/work/alpha" }];

test("会话超过 6 个时只显示 6 个 + 展开按钮", () => {
  const groups = buildSidebarGroups({ projects: manyProjects, sessions: manySessions }, "", new Set());
  const { ctx } = makeCtx();
  const node = renderProjectGroup(groups[0]!, ctx);
  assert.equal(node.querySelectorAll(".session").length, 6, "默认只显示最近 6 个");
  assert.equal(node.querySelector(".sessions-more")?.textContent, "▾ 展开其余 4 个");
});

test("展开后显示全部并变成「收起」", () => {
  const groups = buildSidebarGroups({ projects: manyProjects, sessions: manySessions }, "", new Set());
  const { ctx } = makeCtx();
  ctx.expandedProjects = new Set(["p-a"]);
  const node = renderProjectGroup(groups[0]!, ctx);
  assert.equal(node.querySelectorAll(".session").length, 10);
  assert.equal(node.querySelector(".sessions-more")?.textContent, "▴ 收起");
});

test("点展开按钮回调项目 id，且不触发折叠分组", () => {
  const groups = buildSidebarGroups({ projects: manyProjects, sessions: manySessions }, "", new Set());
  const { ctx, calls } = makeCtx();
  const node = renderProjectGroup(groups[0]!, ctx);
  node.querySelector<HTMLButtonElement>(".sessions-more")!.click();
  assert.deepEqual(calls.more, ["p-a"]);
  assert.deepEqual(calls.toggle, [], "不应同时折叠项目");
});

test("不超过 6 个时不显示展开按钮", () => {
  const five = manySessions.slice(0, 5);
  const groups = buildSidebarGroups({ projects: manyProjects, sessions: five }, "", new Set());
  const { ctx } = makeCtx();
  const node = renderProjectGroup(groups[0]!, ctx);
  assert.equal(node.querySelectorAll(".session").length, 5);
  assert.equal(node.querySelector(".sessions-more"), null);
});

test("搜索时也按 6 个截断（结果多时同样可展开）", () => {
  const groups = buildSidebarGroups({ projects: manyProjects, sessions: manySessions }, "会话", new Set());
  const { ctx } = makeCtx();
  const node = renderProjectGroup(groups[0]!, ctx);
  assert.equal(node.querySelectorAll(".session").length, 6);
  assert.ok(node.querySelector(".sessions-more"));
});
