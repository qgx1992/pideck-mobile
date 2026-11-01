/** 流式文本：只做增量、不丢滚动位置。 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { setStreamText } from "../src/ui/live-text.ts";

interface Metrics {
  scrollHeight: number;
  clientHeight: number;
  scrollTop: number;
}

function makeBox(initial: Metrics): { el: HTMLElement; metrics: Metrics } {
  const dom = new JSDOM("<!doctype html><body><div id=\"box\"></div></body>");
  const el = dom.window.document.getElementById("box") as HTMLElement;
  const metrics: Metrics = { ...initial };
  Object.defineProperty(el, "scrollHeight", { get: () => metrics.scrollHeight, configurable: true });
  Object.defineProperty(el, "clientHeight", { get: () => metrics.clientHeight, configurable: true });
  Object.defineProperty(el, "scrollTop", {
    get: () => metrics.scrollTop,
    set: (value: number) => {
      metrics.scrollTop = value;
    },
    configurable: true,
  });
  return { el, metrics };
}

test("文本没变就什么都不做（不动 DOM）", () => {
  const { el } = makeBox({ scrollHeight: 100, clientHeight: 100, scrollTop: 0 });
  el.textContent = "abc";
  const first = el.firstChild;
  setStreamText(el, "abc");
  assert.equal(el.firstChild, first, "节点没有被重建");
});

test("纯追加只 append 差量，原有文本节点保持不变", () => {
  const { el } = makeBox({ scrollHeight: 100, clientHeight: 100, scrollTop: 0 });
  el.textContent = "abc";
  const first = el.firstChild;
  setStreamText(el, "abcdef");
  assert.equal(el.textContent, "abcdef");
  assert.equal(el.firstChild, first, "旧文本节点被复用了，内部滚动位置才不会被清零");
});

test("贴底时继续跟随最新输出", () => {
  const { el, metrics } = makeBox({ scrollHeight: 1000, clientHeight: 200, scrollTop: 800 });
  el.textContent = "第一段";
  setStreamText(el, "第一段第二段");
  assert.equal(metrics.scrollTop, 1000, "写之前贴底，写之后还在底部");
});

test("用户停在中间：追加文本不会把他拽回开头", () => {
  const { el, metrics } = makeBox({ scrollHeight: 1000, clientHeight: 200, scrollTop: 300 });
  el.textContent = "第一段";
  setStreamText(el, "第一段第二段");
  assert.equal(el.textContent, "第一段第二段");
  assert.equal(metrics.scrollTop, 300, "读到一半的位置必须保住");
});

test("内容整体替换（思考被回退/重排）也保留 scrollTop", () => {
  const { el, metrics } = makeBox({ scrollHeight: 1000, clientHeight: 200, scrollTop: 420 });
  el.textContent = "旧的思考";
  setStreamText(el, "完全不同的新内容");
  assert.equal(el.textContent, "完全不同的新内容");
  assert.equal(metrics.scrollTop, 420);
});

test("内容不足以滚动时不去碰 scrollTop", () => {
  const { el, metrics } = makeBox({ scrollHeight: 80, clientHeight: 200, scrollTop: 0 });
  el.textContent = "短";
  setStreamText(el, "短文本");
  assert.equal(metrics.scrollTop, 0);
});

test("followBottom=false 时不贴底（由调用方自己控制滚动）", () => {
  const { el, metrics } = makeBox({ scrollHeight: 1000, clientHeight: 200, scrollTop: 800 });
  el.textContent = "a";
  setStreamText(el, "ab", { followBottom: false });
  assert.equal(metrics.scrollTop, 800);
});

test("从空到有也不报错", () => {
  const { el } = makeBox({ scrollHeight: 0, clientHeight: 0, scrollTop: 0 });
  setStreamText(el, "第一段");
  assert.equal(el.textContent, "第一段");
});
