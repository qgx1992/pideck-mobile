/**
 * 扫码遮罩（jsdom）。
 *
 * 关键行为是「页面上只剩这一个元素可见」：
 * 原生 startScan 把相机画面铺在 WebView 下面，靠 body.barcode-scanner-active + CSS
 * 把页面藏起来，所以这个 class 的增删必须跟遮罩同生共死。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body><div id=\"app\"></div></body></html>");
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;

const { createScanOverlay } = await import("../src/ui/scan-overlay.ts");

test("创建时挂到 body 并加上 barcode-scanner-active", () => {
  const overlay = createScanOverlay();
  assert.equal(dom.window.document.body.classList.contains("barcode-scanner-active"), true);
  assert.equal(overlay.el.parentElement, dom.window.document.body);
  assert.ok(overlay.el.classList.contains("scan-overlay"));
  overlay.destroy();
  assert.equal(dom.window.document.body.classList.contains("barcode-scanner-active"), false);
  assert.equal(overlay.el.parentElement, null);
});

test("取景框有四个角", () => {
  const overlay = createScanOverlay();
  assert.equal(overlay.el.querySelectorAll(".scan-corner").length, 4);
  overlay.destroy();
});

test("setHint 切换文案与色调", () => {
  const overlay = createScanOverlay();
  const hint = overlay.el.querySelector(".scan-hint");
  assert.equal(hint?.textContent, "把二维码放进框里");
  overlay.setHint("没读到二维码", "error");
  assert.equal(hint?.textContent, "没读到二维码");
  assert.ok(hint?.className.includes("scan-hint--error"));
  overlay.destroy();
});

test("手电筒按钮默认隐藏，可用后点击回调切换状态", () => {
  const calls: boolean[] = [];
  const overlay = createScanOverlay({ onTorch: (on) => calls.push(on) });
  const btn = overlay.el.querySelector(".scan-btn--ghost") as unknown as HTMLButtonElement;

  assert.equal(btn.hidden, true, "没确认可用前不显示");

  overlay.setTorchAvailable(true);
  assert.equal(btn.hidden, false);

  btn.click();
  assert.deepEqual(calls, [true]);
  assert.ok(btn.classList.contains("is-on"));

  btn.click();
  assert.deepEqual(calls, [true, false]);
  assert.equal(btn.classList.contains("is-on"), false);

  overlay.setTorchAvailable(false);
  assert.equal(btn.hidden, true);
  assert.equal(btn.classList.contains("is-on"), false, "收回可用性时状态一起清");
  overlay.destroy();
});

test("外部 setTorch 可回滚按钮状态（原生调用失败时用）", () => {
  const overlay = createScanOverlay();
  const btn = overlay.el.querySelector(".scan-btn--ghost") as unknown as HTMLButtonElement;
  overlay.setTorchAvailable(true);
  overlay.setTorch(true);
  assert.ok(btn.classList.contains("is-on"));
  overlay.setTorch(false);
  assert.equal(btn.classList.contains("is-on"), false);
  overlay.destroy();
});

test("取消按钮触发 onCancel", () => {
  let cancelled = 0;
  const overlay = createScanOverlay({ onCancel: () => (cancelled += 1) });
  const cancel = [...overlay.el.querySelectorAll(".scan-btn")].find(
    (el) => el.textContent === "取消",
  ) as unknown as HTMLButtonElement;
  cancel.click();
  assert.equal(cancelled, 1);
  overlay.destroy();
});
