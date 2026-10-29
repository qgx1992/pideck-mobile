/** SessionChannel：静默断流检测与强制重连。 */

import test from "node:test";
import assert from "node:assert/strict";
import { SessionChannel } from "../src/session.ts";

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

test("连接正常结束后会自动重连", async () => {
  let calls = 0;
  const fake = {
    async streamSession() {
      calls += 1;
      await wait(20);
      // 正常返回 = 服务端关闭了流
    },
    async sendPrompt() {
      return {};
    },
    async abort() {
      return {};
    },
  };
  const channel = new SessionChannel({
    client: fake as never,
    sessionId: "s1",
    autoReconnect: true,
    stallMs: 10_000,
  });
  channel.start();
  await wait(1_300);   // 退避基线 800ms，要等过一轮
  channel.close();
  assert.ok(calls >= 2, `应至少重连一次，实际 ${calls} 次`);
});

test("静默断流（后台被冻结）：看门狗会 abort 并重连", async () => {
  let calls = 0;
  let aborted = 0;
  const fake = {
    streamSession(_id: string, opts: { signal: AbortSignal; onActivity?: () => void }) {
      calls += 1;
      // 模拟假死：既不报错也不来数据，直到被 abort
      return new Promise<void>((_resolve, reject) => {
        opts.signal.addEventListener(
          "abort",
          () => {
            aborted += 1;
            reject(new Error("aborted"));
          },
          { once: true },
        );
      });
    },
    async sendPrompt() {
      return {};
    },
    async abort() {
      return {};
    },
  };

  const channel = new SessionChannel({
    client: fake as never,
    sessionId: "s1",
    autoReconnect: true,
    // 200ms 无字节即判定假死；看门狗间隔会自动缩到 50ms
    stallMs: 200,
  });
  channel.start();
  await wait(600);
  const afterStall = aborted;
  channel.close();
  assert.ok(afterStall >= 1, "看门狗应至少 abort 一次");
  assert.ok(calls >= 2, `abort 后应重连，实际 ${calls} 次`);
});

test("有心跳（onActivity）时不会被误判为假死", async () => {
  let calls = 0;
  let aborted = 0;
  const fake = {
    streamSession(_id: string, opts: { signal: AbortSignal; onActivity?: () => void }) {
      calls += 1;
      const timer = setInterval(() => opts.onActivity?.(), 50);
      return new Promise<void>((_resolve, reject) => {
        opts.signal.addEventListener(
          "abort",
          () => {
            aborted += 1;
            clearInterval(timer);
            reject(new Error("aborted"));
          },
          { once: true },
        );
      });
    },
    async sendPrompt() {
      return {};
    },
    async abort() {
      return {};
    },
  };

  const channel = new SessionChannel({
    client: fake as never,
    sessionId: "s1",
    autoReconnect: true,
    stallMs: 200,
  });
  channel.start();
  await wait(600);
  const callsBeforeClose = calls;
  const abortedBeforeClose = aborted;
  channel.close();
  assert.equal(callsBeforeClose, 1, "持续有心跳就不该重连");
  assert.equal(abortedBeforeClose, 0, "也不该被 abort");
});

test("forceReconnect 会断开当前流并重连", async () => {
  let calls = 0;
  const fake = {
    streamSession(_id: string, opts: { signal: AbortSignal }) {
      calls += 1;
      return new Promise<void>((_resolve, reject) => {
        opts.signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
      });
    },
    async sendPrompt() {
      return {};
    },
    async abort() {
      return {};
    },
  };

  const channel = new SessionChannel({
    client: fake as never,
    sessionId: "s1",
    autoReconnect: true,
    stallMs: 10_000,
  });
  channel.start();
  await wait(60);
  assert.equal(calls, 1);
  channel.forceReconnect();
  await wait(1_300);   // abort 不算 stalls，走正常退避
  channel.close();
  assert.ok(calls >= 2, `forceReconnect 后应重连，实际 ${calls} 次`);
});
