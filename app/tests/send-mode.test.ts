/**
 * 运行中发消息的行为选择（排队 / 插话）。
 *
 * 关键约束：只有 running 时才把 streamingBehavior 传给服务端；
 * 空闲时多传字段没有任何意义，还可能被旧版本 PiDeck 当成未知参数。
 */

import test from "node:test";
import assert from "node:assert/strict";

const { SEND_MODE_ORDER, sendModeActionLabel, sendModeHint, sendModeLabel, sendModeSwitchToast, shouldShowRunningSend, streamingBehaviorFor, toggleSendMode } =
  await import("../src/ui/send-mode.ts");

test("两种模式都有中文标签与提示", () => {
  for (const mode of SEND_MODE_ORDER) {
    assert.ok(sendModeLabel(mode).length > 0);
    assert.ok(sendModeActionLabel(mode).length > 0);
    assert.ok(sendModeHint(mode).length > 0);
  }
  assert.equal(sendModeLabel("queue"), "排队");
  assert.equal(sendModeLabel("steer"), "插话");
});

test("切换在两种模式之间往返", () => {
  assert.equal(toggleSendMode("queue"), "steer");
  assert.equal(toggleSendMode("steer"), "queue");
  assert.equal(toggleSendMode(toggleSendMode("queue")), "queue");
});

test("空闲时不带 streamingBehavior", () => {
  assert.equal(streamingBehaviorFor(false, "queue"), undefined);
  assert.equal(streamingBehaviorFor(false, "steer"), undefined);
});

test("运行中按选择的模式映射到协议字段", () => {
  assert.equal(streamingBehaviorFor(true, "queue"), "followUp");
  assert.equal(streamingBehaviorFor(true, "steer"), "steer");
});

/**
 * 回归：以前只看 running 就显示按钮，输入框空着也会挂一个「排队」出来。
 * 现在必须同时满足「在跑」+「有东西可发」。
 */
test("运行中发送按钮：必须同时满足在跑 + 有内容", () => {
  assert.equal(shouldShowRunningSend(true, true), true);
  assert.equal(shouldShowRunningSend(true, false), false, "运行中但输入框空着 → 不显示");
  assert.equal(shouldShowRunningSend(false, true), false, "没在跑时主按钮就是发送 → 不显示");
  assert.equal(shouldShowRunningSend(false, false), false);
});

test("切换模式的提示带模式名与说明", () => {
  assert.match(sendModeSwitchToast("steer"), /插话/);
  assert.match(sendModeSwitchToast("queue"), /排队/);
  assert.match(sendModeSwitchToast("queue"), /结束后/);
});
