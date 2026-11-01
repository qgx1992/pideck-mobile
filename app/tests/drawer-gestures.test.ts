/** 抽屉手势：方向→计划、行程换算、完成判定。 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import {
  attachDrawerGestures,
  drawerPercent,
  hasHorizontalScrollerAncestor,
  horizontalScrollRange,
  isHorizontalScroller,
  isInteractiveTarget,
  isReversePastOrigin,
  isScrollRoot,
  planSwipe,
  progressForAction,
  shouldFinish,
  shouldTrackGesture,
  swipeProgress,
  travelFor,
  velocityAlong,
} from "../src/ui/drawer-gestures.ts";
import type { SwipePlan } from "../src/ui/drawer-gestures.ts";

test("都没开：右滑开左抽屉，左滑开右抽屉", () => {
  assert.deepEqual(planSwipe("right", "none"), { drawer: "left", action: "open" });
  assert.deepEqual(planSwipe("left", "none"), { drawer: "right", action: "open" });
});

test("左抽屉已开：左滑关闭它，右滑不接管（不会去开右抽屉）", () => {
  assert.deepEqual(planSwipe("left", "left"), { drawer: "left", action: "close" });
  assert.equal(planSwipe("right", "left"), null, "禁止同时开两个");
});

test("右抽屉已开：右滑关闭它，左滑不接管", () => {
  assert.deepEqual(planSwipe("right", "right"), { drawer: "right", action: "close" });
  assert.equal(planSwipe("left", "right"), null);
});

test("行程换算：四种计划各归其位", () => {
  const cases: Array<[SwipePlan, number, number]> = [
    [{ drawer: "left", action: "open" }, 120, 120],
    [{ drawer: "left", action: "close" }, -120, 120],
    [{ drawer: "right", action: "open" }, -120, 120],
    [{ drawer: "right", action: "close" }, 120, 120],
  ];
  for (const [plan, dx, expected] of cases) {
    assert.equal(travelFor(dx, plan), expected, JSON.stringify({ plan, dx }));
  }
});

test("打开手势从 0 走到 1，关闭手势从 1 回到 0", () => {
  const W = 320;
  assert.equal(progressForAction(0, W, "open"), 0);
  assert.equal(progressForAction(W, W, "open"), 1);
  assert.equal(progressForAction(0, W, "close"), 1, "关闭起点是全开");
  assert.equal(progressForAction(W, W, "close"), 0, "关闭终点是全关");
});

test("进度 → 位移百分比：左 -100→0，右 +100→0", () => {
  assert.equal(drawerPercent(0, "left"), -100);
  assert.equal(drawerPercent(1, "left"), 0);
  assert.equal(drawerPercent(0, "right"), 100);
  assert.equal(drawerPercent(1, "right"), 0);
  assert.equal(drawerPercent(0.5, "right"), 50);
});

test("完成判定：35% 距离或够快的轻扫", () => {
  assert.equal(shouldFinish(64, 320, 0), false, "20% 且慢 → 不算");
  assert.equal(shouldFinish(112, 320, 0), true, "35% → 算");
  assert.equal(shouldFinish(50, 320, 0.5), true, "15% 但速度快 → 算");
  assert.equal(shouldFinish(50, 320, 0.1), false);
  assert.equal(shouldFinish(0, 320, 5), false, "零位移一定不算");
  assert.equal(shouldFinish(-10, 320, 5), false, "反向位移不算");
});

test("swipeProgress 夹边界并防除零", () => {
  assert.equal(swipeProgress(160, 320), 0.5);
  assert.equal(swipeProgress(999, 320), 1);
  assert.equal(swipeProgress(-10, 320), 0);
  assert.equal(swipeProgress(100, 0), 0);
  assert.equal(swipeProgress(100, Number.NaN), 0);
});

test("起手过滤：交互控件内一律不接管", () => {
  assert.equal(shouldTrackGesture(5, 48, false, true), false);
  assert.equal(shouldTrackGesture(5, 48, true, true), false);
  assert.equal(shouldTrackGesture(5, 48, true, false), true);
});

/* ---------------- 横向滚动的容器里不接管手势 ---------------- */

test("overflow-x 不是 auto/scroll 就不算横向滚动", () => {
  assert.equal(isHorizontalScroller("hidden", 500, 100), false);
  assert.equal(isHorizontalScroller("visible", 500, 100), false);
});

test("内容没超出可视宽度就不算横向滚动", () => {
  assert.equal(isHorizontalScroller("auto", 100, 100), false);
  assert.equal(isHorizontalScroller("auto", 101, 100), false, "1px 以内忽略");
  assert.equal(isHorizontalScroller("auto", 300, 100), true);
  assert.equal(isHorizontalScroller("scroll", 0, 0), false);
});

test("横向可滚动量", () => {
  assert.equal(horizontalScrollRange(300, 100), 200);
  assert.equal(horizontalScrollRange(100, 300), 0, "不能为负");
  assert.equal(horizontalScrollRange(Number.NaN, 10), 0);
});

test("宽表格的拖动不会被当成抽屉手势（选择器层面）", () => {
  const dom = new JSDOM('<!doctype html><body><div class="md"><div class="md-table-wrap"><table class="md-table"><tbody><tr><td>a</td></tr></tbody></table></div></div></body>');
  const cell = dom.window.document.querySelector("td")!;
  // jsdom 没有布局，scrollWidth 恒为 0，所以这里验证的是选择器兜底
  assert.equal(hasHorizontalScrollerAncestor(cell), false);
  assert.ok(cell.closest(".md-table-wrap"), "表格容器必须在排除名单里");
});

/* ---------------- 反向手势不能弹抽屉 ---------------- */

test("速度必须沿手势方向：往回缩的那一步是负速度", () => {
  const openLeft: SwipePlan = { drawer: "left", action: "open" };
  assert.equal(velocityAlong(120, openLeft, 16), 7.5);
  assert.equal(velocityAlong(-150, openLeft, 16), -9.375, "手指往回收 → 负速度");
  assert.equal(velocityAlong(10, openLeft, 0), 0, "除零保护");
});

test("负速度不算轻扫（拉一半又缩回去不会打开）", () => {
  assert.equal(shouldFinish(100, 880, -9.375), false, "距离不够 + 往回走 → 不打开");
  assert.equal(shouldFinish(100, 880, 0), false, "停住也不算");
  assert.equal(shouldFinish(100, 880, 1.2), true, "同向轻扫才算");
  assert.equal(shouldFinish(400, 880, -9.375), true, "距离够就够，方向不影响");
});

test("越过起点才算反悔（留 8px 抖动余量）", () => {
  assert.equal(isReversePastOrigin(-9), true);
  assert.equal(isReversePastOrigin(-8), false);
  assert.equal(isReversePastOrigin(0), false);
  assert.equal(isReversePastOrigin(40), false);
});

/* ---------------- 哪片区域能起手滑 ---------------- */

test("正文里的列表 / 思考块 / 工具块都可以起手滑（曾经被一刀切排除）", () => {
  const dom = new JSDOM(
    '<!doctype html><body><div class="chat-scroll"><div class="history">' +
      '<ul class="md-list"><li id="li">条目</li></ul>' +
      '<details class="reasoning"><summary>思考</summary><div class="reasoning-body" id="rb">正文</div></details>' +
      '<details class="tool"><summary>工具</summary><div class="tool-body" id="tb">正文</div></details>' +
      "</div></div></body>",
  );
  const doc = dom.window.document;
  for (const id of ["li", "rb", "tb"]) {
    assert.equal(isInteractiveTarget(doc.getElementById(id)), false, `${id} 应该能滑出侧栏`);
  }
});

test("输入框 / 自带手势的面板仍然不接管", () => {
  const dom = new JSDOM(
    '<!doctype html><body><textarea id="ta"></textarea><input id="in">' +
      '<div class="sheet"><div id="sd">x</div></div>' +
      '<div class="ws-preview"><div id="wp">y</div></div></body>',
  );
  const doc = dom.window.document;
  assert.equal(isInteractiveTarget(doc.getElementById("ta")), true);
  assert.equal(isInteractiveTarget(doc.getElementById("in")), true);
  assert.equal(isInteractiveTarget(doc.getElementById("sd")), true);
  assert.equal(isInteractiveTarget(doc.getElementById("wp")), true);
});

test("页面级滚动容器横向溢出不算「内容自己管手势」", () => {
  const dom = new JSDOM(
    '<!doctype html><body><div class="chat-scroll" id="cs"><div class="history">' +
      '<article class="msg"><div class="msg-body" id="mb">很长的一行</div></article>' +
      "</div></div></body>",
  );
  const doc = dom.window.document;
  const scroller = doc.getElementById("cs") as HTMLElement;
  // 模拟「有一条消息横向撑爆了 .chat-scroll」：overflow-y:auto 会让 overflow-x 也变成 auto
  scroller.style.overflowX = "auto";
  Object.defineProperty(scroller, "scrollWidth", { value: 1200, configurable: true });
  Object.defineProperty(scroller, "clientWidth", { value: 320, configurable: true });
  assert.equal(isScrollRoot(scroller), true);
  assert.equal(hasHorizontalScrollerAncestor(doc.getElementById("mb")), false, "整页不能因此失去手势");
});

test("真正的横向滚动容器（宽表格）仍然拦下抽屉手势", () => {
  const dom = new JSDOM(
    '<!doctype html><body><div class="chat-scroll"><div class="md">' +
      '<div class="md-table-wrap" id="tw"><table class="md-table"><tbody><tr><td id="td">a</td></tr></tbody></table></div>' +
      "</div></div></body>",
  );
  const doc = dom.window.document;
  const wrap = doc.getElementById("tw") as HTMLElement;
  wrap.style.overflowX = "auto";
  Object.defineProperty(wrap, "scrollWidth", { value: 700, configurable: true });
  Object.defineProperty(wrap, "clientWidth", { value: 300, configurable: true });
  assert.equal(isScrollRoot(wrap), false);
  assert.equal(hasHorizontalScrollerAncestor(doc.getElementById("td")), true);
});

/* ---------------- 触摸序列（真手势） ---------------- */

interface Harness {
  draw: { left: HTMLElement; right: HTMLElement };
  opens: string[];
  closes: string[];
  fire: (type: string, x: number) => void;
  dispose: () => void;
}

function harness(): Harness {
  const dom = new JSDOM(
    '<!doctype html><body><div class="chat-scroll"><article class="msg"><div class="msg-body" id="touch">正文</div></article></div>' +
      '<aside id="left"></aside><div id="left-scrim"></div><aside id="right"></aside><div id="right-scrim"></div></body>',
    { pretendToBeVisual: true },
  );
  const doc = dom.window.document;
  const opens: string[] = [];
  const closes: string[] = [];
  const left = doc.getElementById("left") as HTMLElement;
  const right = doc.getElementById("right") as HTMLElement;
  const dispose = attachDrawerGestures({
    isEnabled: () => true,
    currentOpen: () => "none",
    left: {
      drawer: left,
      scrim: doc.getElementById("left-scrim") as HTMLElement,
      applyOpen: () => opens.push("left"),
      applyClose: () => closes.push("left"),
    },
    right: {
      drawer: right,
      scrim: doc.getElementById("right-scrim") as HTMLElement,
      applyOpen: () => opens.push("right"),
      applyClose: () => closes.push("right"),
    },
  });

  const target = doc.getElementById("touch") as HTMLElement;
  const fire = (type: string, x: number): void => {
    const event = new dom.window.Event(type, { bubbles: true, cancelable: true });
    const touches = type === "touchend" || type === "touchcancel" ? [] : [{ clientX: x, clientY: 0 }];
    Object.defineProperty(event, "touches", { value: touches });
    target.dispatchEvent(event);
  };

  return { draw: { left, right }, opens, closes, fire, dispose };
}

/** 把 Date.now 冻住，按自己给的节奏推进（速度判定要用）。 */
function withClock(run: (tick: (ms: number) => void) => void): void {
  const real = Date.now;
  let now = real();
  Date.now = () => now;
  try {
    run((ms) => {
      now += ms;
    });
  } finally {
    Date.now = real;
  }
}

test("正常右滑 45% → 打开左侧栏", () => {
  const h = harness();
  withClock((tick) => {
    h.fire("touchstart", 100);
    tick(16);
    h.fire("touchmove", 200);
    tick(16);
    h.fire("touchmove", 500);
    tick(16);
    h.fire("touchend", 500);
  });
  assert.deepEqual(h.opens, ["left"]);
  h.dispose();
});

test("拉一半又往回缩 → 不能弹侧栏（回归：以前 abs(速度) 会误判成轻扫）", () => {
  const h = harness();
  withClock((tick) => {
    h.fire("touchstart", 100);
    tick(16);
    h.fire("touchmove", 300);
    tick(16);
    h.fire("touchmove", 150);
    tick(16);
    h.fire("touchend", 150);
  });
  assert.deepEqual(h.opens, [], "最后一帧在往回走，不能打开");
  assert.deepEqual(h.closes, []);
  h.dispose();
});

test("越过起点（反悔）→ 手势作废，后续移动也不再接管", () => {
  const h = harness();
  withClock((tick) => {
    h.fire("touchstart", 200);
    tick(16);
    h.fire("touchmove", 400);
    tick(16);
    h.fire("touchmove", 150); // 越过起点 50px
    assert.equal(h.draw.left.style.transform, "", "反悔后立刻回弹，不留 inline 位移");
    tick(16);
    h.fire("touchmove", 900); // 同一次触摸里再往回拉
    tick(16);
    h.fire("touchend", 900);
  });
  assert.equal(h.draw.left.style.transform, "", "作废后不再跟手");
  assert.deepEqual(h.opens, []);
  h.dispose();
});

test("手指按住不动再松手不算轻扫", () => {
  const h = harness();
  withClock((tick) => {
    h.fire("touchstart", 100);
    tick(16);
    h.fire("touchmove", 200); // 快速一步
    tick(400);                // 然后按住不动
    h.fire("touchend", 200);
  });
  assert.deepEqual(h.opens, [], "100/880 < 35% 且速度已过期");
  h.dispose();
});
