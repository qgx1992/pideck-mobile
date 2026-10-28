/** 抽屉手势：方向→计划、行程换算、完成判定。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  drawerPercent,
  planSwipe,
  progressForAction,
  shouldFinish,
  shouldTrackGesture,
  swipeProgress,
  travelFor,
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
