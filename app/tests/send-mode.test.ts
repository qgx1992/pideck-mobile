/**
 * 运行中发消息的行为选择（排队 / 插话）。
 *
 * 关键约束：只有 running 时才把 streamingBehavior 传给服务端；
 * 空闲时多传字段没有任何意义，还可能被旧版本 PiDeck 当成未知参数。
 */

import test from "node:test";
import assert from "node:assert/strict";

const { SEND_MODE_ORDER, sendModeActionLabel, sendModeHint, sendModeLabel, streamingBehaviorFor, toggleSendMode } =
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
