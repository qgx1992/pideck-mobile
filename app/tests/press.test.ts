/** 长按手势：计时、位移取消、长按后抑制 click、右键等价。 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body><button id='b'>x</button></body></html>");
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.HTMLElement = dom.window.HTMLElement;
g.Node = dom.window.Node;
g.MouseEvent = dom.window.MouseEvent;
g.Event = dom.window.Event;

const { attachPressable } = await import("../src/ui/press.ts");

function makeButton() {
  const el = dom.window.document.getElementById("b") as unknown as HTMLElement;
  const el2 = el.cloneNode(true) as HTMLElement;
  el.replaceWith(el2);
  const calls = { tap: 0, long: 0 };
  const detach = attachPressable(el2, {
    onTap: () => {
      calls.tap += 1;
    },
    onLongPress: () => {
      calls.long += 1;
    },
    delay: 20,
  });
  return { el: el2, calls, detach };
}

function mouse(el: HTMLElement, type: string, x = 0, y = 0): void {
  el.dispatchEvent(new dom.window.MouseEvent(type, { bubbles: true, button: 0, clientX: x, clientY: y }));
}

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

test("短按触发 onTap，不触发长按", async () => {
  const { el, calls } = makeButton();
  mouse(el, "mousedown");
  mouse(el, "mouseup");
  mouse(el, "click");
  await wait(40);
  assert.equal(calls.tap, 1);
  assert.equal(calls.long, 0);
});

test("按住超时触发长按，随后的 click 被抑制", async () => {
  const { el, calls } = makeButton();
  mouse(el, "mousedown");
  await wait(50);
  assert.equal(calls.long, 1, "应触发长按");

  mouse(el, "mouseup");
  mouse(el, "click");
  assert.equal(calls.tap, 0, "长按后的 click 不应算点击");
});

test("移动超过阈值会取消长按", async () => {
  const { el, calls } = makeButton();
  mouse(el, "mousedown", 0, 0);
  mouse(el, "mousemove", 30, 0);
  await wait(50);
  assert.equal(calls.long, 0, "滑动列表不应误触发");
});

test("小幅抖动不取消", async () => {
  const { el, calls } = makeButton();
  mouse(el, "mousedown", 0, 0);
  mouse(el, "mousemove", 3, 2);
  await wait(50);
  assert.equal(calls.long, 1);
});

test("右键菜单等价于长按，且阻止默认菜单", async () => {
  const { el, calls } = makeButton();
  const event = new dom.window.MouseEvent("contextmenu", { bubbles: true, cancelable: true });
  el.dispatchEvent(event);
  assert.equal(calls.long, 1);
  assert.equal(event.defaultPrevented, true);
});

test("detach 后不再响应", async () => {
  const { el, calls, detach } = makeButton();
  detach();
  mouse(el, "mousedown");
  await wait(50);
  mouse(el, "click");
  assert.equal(calls.long, 0);
  assert.equal(calls.tap, 0);
});

test("长按后 click 不触发 onTap（避免长按同时打开会话）", async () => {
  const { el, calls } = makeButton();
  mouse(el, "mousedown");
  await wait(50);
  assert.equal(calls.long, 1);
  mouse(el, "mouseup");
  mouse(el, "click");
  assert.equal(calls.tap, 0);
  // 抑制只生效一次：下一次正常点击应恢复
  mouse(el, "click");
  assert.equal(calls.tap, 1, "抑制标记用完应复位");
});
