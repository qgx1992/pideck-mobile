/** 执行过程折叠：摘要统计、标题文案、DOM 结构。 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>");
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.HTMLElement = dom.window.HTMLElement;
g.Node = dom.window.Node;

const { summarizeProcess, processTitle, createProcessGroup, isProcessBlock } = await import(
  "../src/ui/process-group.ts"
);

test("统计工具与思考数量，识别失败工具", () => {
  const summary = summarizeProcess([
    { kind: "reasoning" },
    { kind: "tool", state: "ok" } as never,
    { kind: "tool", state: "error" } as never,
  ]);
  assert.deepEqual(summary, { tools: 2, thinkings: 1, failures: 1 });
});

test("标题按顺序拼接，失败要显式标出", () => {
  assert.equal(processTitle({ tools: 3, thinkings: 2, failures: 0 }), "3 个工具 · 2 段思考");
  assert.equal(processTitle({ tools: 3, thinkings: 0, failures: 0 }), "3 个工具");
  assert.equal(processTitle({ tools: 0, thinkings: 4, failures: 0 }), "4 段思考");
  assert.equal(processTitle({ tools: 1, thinkings: 1, failures: 1 }), "1 个工具 · 1 段思考（1 个失败）");
  assert.equal(processTitle({ tools: 0, thinkings: 0, failures: 0 }), "执行过程");
});

test("折叠块默认收起，刷新后标题与失败态同步", () => {
  const group = createProcessGroup();
  assert.equal(group.el.tagName, "DETAILS");
  assert.equal(group.el.hasAttribute("open"), false, "默认收起，避免占位");

  group.refresh({ tools: 5, thinkings: 2, failures: 0 });
  assert.equal(group.el.querySelector(".process-title")?.textContent, "5 个工具 · 2 段思考");
  assert.equal(group.el.classList.contains("has-failure"), false);

  group.refresh({ tools: 5, thinkings: 2, failures: 2 });
  assert.ok(group.el.querySelector(".process-title")?.textContent?.includes("2 个失败"));
  assert.equal(group.el.classList.contains("has-failure"), true);
});

test("运行中可指定展开", () => {
  const group = createProcessGroup({ open: true });
  assert.equal(group.el.hasAttribute("open"), true);
});

test("body 是条目挂载点", () => {
  const group = createProcessGroup();
  const item = dom.window.document.createElement("div");
  item.className = "tool";
  group.body.append(item);
  assert.equal(group.el.querySelector(".process-body .tool"), item);
});

test("isProcessBlock 只认 tool / reasoning", () => {
  assert.equal(isProcessBlock({ kind: "tool" } as never), true);
  assert.equal(isProcessBlock({ kind: "reasoning" } as never), true);
  assert.equal(isProcessBlock({ kind: "text" } as never), false);
});
