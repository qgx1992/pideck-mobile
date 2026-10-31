/**
 * 轻提示：位置与去重。
 *
 * 用户反馈：圆角太大（胶囊）且位置太低，正好挡住输入框。
 * 这里钉住「偏移量按输入框实际高度算」这条规则 —— 它是这次修复的核心。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM(
  '<!doctype html><html><body><div id="app"><section class="chat"><div class="composer"></div></section></div></body></html>',
);
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.HTMLElement = dom.window.HTMLElement;
g.Node = dom.window.Node;

const { toast } = await import("../src/ui/dom.ts");

/** jsdom 不做布局，手工给 rect。 */
function stubRect(el: Element, rect: { top: number; bottom: number; height: number }): void {
  (el as unknown as { getBoundingClientRect: () => DOMRect }).getBoundingClientRect = () =>
    ({ top: rect.top, bottom: rect.bottom, height: rect.height, left: 0, right: 0, width: 0, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
}

function reset(): void {
  for (const el of Array.from(dom.window.document.querySelectorAll(".toast"))) el.remove();
}

test("挂在 #app 内（不依赖 fixed 定位）", () => {
  reset();
  toast("已复制");
  const el = dom.window.document.querySelector("#app > .toast");
  assert.ok(el, "toast 必须是 #app 的直接子节点");
  assert.equal(el!.textContent, "已复制");
});

test("有输入框时：偏移量 = 输入框占的高度 + 12px", () => {
  reset();
  const host = dom.window.document.getElementById("app")!;
  const composer = dom.window.document.querySelector(".composer")!;
  // #app 占满 800 高的视口，输入框顶边在 640 → 占掉 160
  stubRect(host, { top: 0, bottom: 800, height: 800 });
  stubRect(composer, { top: 640, bottom: 800, height: 160 });

  toast("已插入输入框");
  const el = dom.window.document.querySelector(".toast") as unknown as HTMLElement;
  assert.equal(el.style.getPropertyValue("--toast-offset"), "172px", "160 + 12");
});

test("输入框更高（附件行/多行输入）时偏移跟着长", () => {
  reset();
  const host = dom.window.document.getElementById("app")!;
  const composer = dom.window.document.querySelector(".composer")!;
  stubRect(host, { top: 0, bottom: 800, height: 800 });
  stubRect(composer, { top: 520, bottom: 800, height: 280 });

  toast("图片已添加");
  const el = dom.window.document.querySelector(".toast") as unknown as HTMLElement;
  assert.equal(el.style.getPropertyValue("--toast-offset"), "292px");
});

test("没有输入框（配对页 / 设置页）时用默认位置", () => {
  reset();
  const composer = dom.window.document.querySelector(".composer")!;
  composer.remove();

  toast("连接成功");
  const el = dom.window.document.querySelector(".toast") as unknown as HTMLElement;
  assert.equal(el.style.getPropertyValue("--toast-offset"), "", "不写变量 → CSS 用兜底 28px");
});

test("同一时刻只留一条，连点不叠成一坨", () => {
  reset();
  toast("第一条");
  toast("第二条");
  toast("第三条");
  const all = dom.window.document.querySelectorAll(".toast");
  assert.equal(all.length, 1);
  assert.equal(all[0]!.textContent, "第三条");
});
