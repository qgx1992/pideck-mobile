/**
 * 子代理活动条：默认收起、手动折叠、钉在顶栏下方的位置约定。
 *
 * 「默认收起」是产品决定（对齐 Web 端）：一次派几个子代理时，展开态会顶掉
 * 半个消息区，而子代理是只读信息，需要时才点开。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>");
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.Node = dom.window.Node;
g.HTMLElement = dom.window.HTMLElement;

const { createSubagentStrip } = await import("../src/ui/subagent-strip.ts");
const { normalizeSubagents } = await import("../src/ui/subagent-model.ts");

const RAW = [
  { id: "s1", type: "reviewer", description: "审查 auth 模块", status: "running" },
  { id: "s2", type: "researcher", description: "查 hook 实现", status: "completed", result: "结论 A" },
];

function mount() {
  const strip = createSubagentStrip();
  document.body.append(strip.el);
  return strip;
}

test("默认收起：只显示一行头部，列表不渲染", () => {
  const strip = mount();
  const list = strip.el.querySelector(".subagent-list") as HTMLElement;
  assert.ok(strip.el.classList.contains("is-collapsed"));
  assert.equal(list.style.display, "none");
  const chev = strip.el.querySelector(".chev-icon");
  assert.equal(chev?.classList.contains("is-expanded"), false, "收起态箭头不旋转");
  strip.el.remove();
});

test("点头部展开，再点收起", () => {
  const strip = mount();
  strip.update(normalizeSubagents(RAW));
  const head = strip.el.querySelector(".subagent-head") as HTMLElement;
  const list = strip.el.querySelector(".subagent-list") as HTMLElement;

  assert.equal(list.style.display, "none", "有子代理在跑也不自动展开");

  head.click();
  assert.ok(!strip.el.classList.contains("is-collapsed"));
  assert.equal(list.style.display, "");
  assert.ok(strip.el.querySelector(".chev-icon")?.classList.contains("is-expanded"));

  head.click();
  assert.ok(strip.el.classList.contains("is-collapsed"));
  assert.equal(list.style.display, "none");
  strip.el.remove();
});

test("update 传 collapsed 覆盖当前状态（用于切会话恢复记忆）", () => {
  const strip = mount();
  strip.update(normalizeSubagents(RAW), { collapsed: false });
  assert.ok(!strip.el.classList.contains("is-collapsed"));
  strip.update(normalizeSubagents(RAW), { collapsed: true });
  assert.ok(strip.el.classList.contains("is-collapsed"));
  strip.el.remove();
});

test("没有子代理时整条隐藏", () => {
  const strip = mount();
  strip.update(normalizeSubagents([]));
  assert.ok(strip.el.classList.contains("is-hidden"));
  strip.el.remove();
});

test("头部摘要与运行中呼吸态", () => {
  const strip = mount();
  strip.update(normalizeSubagents(RAW));
  assert.ok(!strip.el.classList.contains("is-hidden"));
  assert.ok(strip.el.classList.contains("has-active"));
  assert.equal(strip.el.querySelector(".subagent-summary")?.textContent, "2 个子代理 · 1 运行中");
  strip.el.remove();
});

test("条目渲染类型、描述与状态", () => {
  const strip = mount();
  strip.update(normalizeSubagents(RAW), { collapsed: false });
  const rows = strip.el.querySelectorAll(".subagent-item");
  assert.equal(rows.length, 2);
  assert.equal(rows[0]?.querySelector(".subagent-type")?.textContent, "reviewer");
  assert.equal(rows[0]?.querySelector(".subagent-desc")?.textContent, "审查 auth 模块");
  assert.equal(rows[0]?.querySelector(".subagent-status")?.textContent, "运行中");
  assert.ok(rows[1]?.querySelector(".subagent-detail"), "有结果时展开正文");
  strip.el.remove();
});
