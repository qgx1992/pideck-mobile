/** 跳转定位：长消息对齐顶部、短消息不滚过头、超远距离不平滑滚。 */

import test from "node:test";
import assert from "node:assert/strict";
import { ANCHOR_GAP, jumpBehavior, topAlignedScrollTop } from "../src/ui/scroll-anchor.ts";

const CLIENT = 800;          // 一屏 800px
const SCROLL_HEIGHT = 20_000; // 长会话

test("长消息：对齐到消息顶部，而不是居中", () => {
  // 目标当前在视口下方 5000px 处，容器视口 top=0，已经滚到 2000
  const next = topAlignedScrollTop(5000, 0, 2000, SCROLL_HEIGHT, CLIENT);
  assert.equal(next, 5000 + 2000 - ANCHOR_GAP);
  // 跳到之后目标顶部回到视口顶部（含 gap），正文第一行可见
  assert.equal(5000 + 2000 - next, ANCHOR_GAP);
});

test("容器视口不在屏幕顶端时（顶栏占了 60px）也按相对位置算", () => {
  const next = topAlignedScrollTop(2060, 60, 0, SCROLL_HEIGHT, CLIENT);
  assert.equal(next, 2000 - ANCHOR_GAP, "视口偏移要被减掉，否则会多滚一条顶栏的高度");
});

test("靠近文末：不越过底部，退化成贴底", () => {
  // 文末只剩 300px，目标顶部在 19900，想对齐到 19900 但最大值是 19200
  const next = topAlignedScrollTop(19_900, 0, 0, SCROLL_HEIGHT, CLIENT);
  assert.equal(next, SCROLL_HEIGHT - CLIENT);
});

test("顶部消息：不会滚成负数", () => {
  assert.equal(topAlignedScrollTop(-2_000, 0, 0, SCROLL_HEIGHT, CLIENT), 0);
  assert.equal(topAlignedScrollTop(5, 0, 0, SCROLL_HEIGHT, CLIENT), 0, "5px 减去 gap 后为负 → 0");
});

test("没有布局信息（jsdom / scrollHeight=0）时返回 0，不崩", () => {
  assert.equal(topAlignedScrollTop(0, 0, 0, 0, 0), 0);
  assert.equal(topAlignedScrollTop(Number.NaN, 0, 0, SCROLL_HEIGHT, CLIENT), 0);
  assert.equal(topAlignedScrollTop(100, 0, 0, 500, 800), 0, "内容比视口还短 → 0");
});

test("三屏以内平滑滚，超过就瞬间定位", () => {
  assert.equal(jumpBehavior(CLIENT * 2, CLIENT), "smooth");
  assert.equal(jumpBehavior(-CLIENT * 2, CLIENT), "smooth", "往上跳同样按距离判断");
  assert.equal(jumpBehavior(CLIENT * 4, CLIENT), "auto");
  assert.equal(jumpBehavior(0, CLIENT), "smooth");
});

test("没有布局信息时不平滑（避免动画跑在 0 高度上）", () => {
  assert.equal(jumpBehavior(500, 0), "auto");
  assert.equal(jumpBehavior(Number.NaN, CLIENT), "auto");
});
