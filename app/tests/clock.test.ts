/**
 * `clockOf`（`app/src/ui/dom.ts`）：只取「时:分」，不跟系统语言走。
 *
 * 回归背景：原来两处都用 `toLocaleTimeString().slice(0, 5)`，en-US 下拿到的是
 * `3:04:` —— 本地（zh-CN）测不出来，CI 的 en-US runner 一跑就红。
 */

import assert from "node:assert/strict";
import test from "node:test";

import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>");
const globals = globalThis as unknown as Record<string, unknown>;
globals.window = dom.window;
globals.document = dom.window.document;
globals.Node = dom.window.Node;

const { clockOf } = await import("../src/ui/dom.ts");

test("clockOf：补零的 24 小时制，且不带语言尾巴", () => {
  const ms = new Date(2026, 0, 2, 3, 4, 59).getTime();
  assert.equal(clockOf(ms), "03:04");
  // 关键：不能是 "3:04:" / "3:04 AM" 这类切出来的残片
  assert.match(clockOf(ms), /^\d{2}:\d{2}$/);
});

test("clockOf：午夜与 23:59 的边界（12 小时制会在这里露馅）", () => {
  assert.equal(clockOf(new Date(2026, 0, 2, 0, 0).getTime()), "00:00");
  assert.equal(clockOf(new Date(2026, 0, 2, 23, 59).getTime()), "23:59");
  assert.equal(clockOf(new Date(2026, 0, 2, 13, 5).getTime()), "13:05");
});
