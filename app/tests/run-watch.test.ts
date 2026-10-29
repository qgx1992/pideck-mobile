/** 后台看护：何时启动、标题收集、去重语义。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  collectRunWatchTitles,
  isRunWatchActive,
  shouldStartRunWatch,
  stopRunWatch,
} from "../src/platform/run-watch.ts";

test("有 running 才启动看护", () => {
  assert.equal(shouldStartRunWatch([]), false);
  assert.equal(shouldStartRunWatch([{ sessionId: "a", status: "idle" }]), false);
  assert.equal(shouldStartRunWatch([{ sessionId: "a", status: "running" }]), true);
  assert.equal(
    shouldStartRunWatch([
      { sessionId: "a", status: "idle" },
      { sessionId: "b", status: "running" },
    ]),
    true,
  );
});

test("只收集正在跑的会话标题，缺标题的跳过", () => {
  const titles = collectRunWatchTitles(
    [
      { sessionId: "a", status: "running" },
      { sessionId: "b", status: "idle" },
      { sessionId: "c", status: "running" },
    ],
    (sessionId) => ({ a: { title: "修 CI" }, b: { title: "不该出现" }, c: { title: "  " } })[sessionId],
  );
  assert.deepEqual(titles, { a: "修 CI" });
});

test("标题去掉首尾空白，查不到会话时不报错", () => {
  const titles = collectRunWatchTitles(
    [{ sessionId: "a", status: "running" }],
    () => undefined,
  );
  assert.deepEqual(titles, {});

  const trimmed = collectRunWatchTitles([{ sessionId: "a", status: "running" }], () => ({ title: "  对话  " }));
  assert.deepEqual(trimmed, { a: "对话" });
});

test("浏览器环境（非原生）不会真的启动，也不会抛错", async () => {
  assert.equal(isRunWatchActive(), false);
  await stopRunWatch();
  assert.equal(isRunWatchActive(), false);
});
