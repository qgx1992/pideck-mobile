/** 启动动画：最短显示、幂等收起、看门狗兜底。 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM(
  '<!doctype html><html><body><div id="splash"><div id="splash-hint">正在启动…</div></div><div id="app"></div></body></html>',
  { pretendToBeVisual: true },
);
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;

const {
  SPLASH_MIN_MS,
  hideSplash,
  isSplashHidden,
  resetSplashStateForTest,
  setSplashHint,
  splashRemainingMs,
} = await import("../src/ui/splash.ts");

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

test("剩余最短显示时间", () => {
  assert.equal(splashRemainingMs(1_000, 1_000, 700), 700);
  assert.equal(splashRemainingMs(1_000, 1_300, 700), 400);
  assert.equal(splashRemainingMs(1_000, 1_700, 700), 0, "刚好到点即可收");
  assert.equal(splashRemainingMs(1_000, 9_000, 700), 0, "早过了");
  assert.equal(splashRemainingMs(Number.NaN, 1_000, 700), 0);
});

test("启动页上的提示文案可更新", () => {
  setSplashHint("正在连接 PiDeck…");
  assert.equal(dom.window.document.getElementById("splash-hint")?.textContent, "正在连接 PiDeck…");
});

test("收起：等最短时间 → 加淡出类 → 从 DOM 移除", async () => {
  resetSplashStateForTest();
  const el = dom.window.document.getElementById("splash")!;
  el.dataset.shownAt = String(Date.now());

  assert.equal(isSplashHidden(), false);
  hideSplash();
  assert.equal(isSplashHidden(), true, "调用后立刻标记为已收起（幂等）");
  assert.equal(el.classList.contains("is-leaving"), false, "还没到最短时间");

  await wait(SPLASH_MIN_MS + 80);
  assert.ok(el.classList.contains("is-leaving"), "到点后开始淡出");

  await wait(380);
  assert.equal(dom.window.document.getElementById("splash"), null, "淡出后从 DOM 移除");
});

test("重复调用只收一次", async () => {
  resetSplashStateForTest();
  dom.window.document.body.insertAdjacentHTML("afterbegin", '<div id="splash"></div>');
  const el = dom.window.document.getElementById("splash")!;
  el.dataset.shownAt = String(Date.now() - 5_000); // 早就过了最短时间

  hideSplash();
  hideSplash();
  hideSplash();
  await wait(420);
  assert.equal(dom.window.document.querySelectorAll("#splash").length, 0);
});

test("没有 splash 元素时静默返回，不抛错", () => {
  resetSplashStateForTest();
  hideSplash();
  assert.equal(isSplashHidden(), true);
});
