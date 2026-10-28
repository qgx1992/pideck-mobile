import test from "node:test";
import assert from "node:assert/strict";
import { normalizeState } from "../src/api/client.ts";
import { findTarget, isTargetStale, requireTarget, sameTarget } from "../src/runtime/target.ts";
import { Backoff } from "../src/api/backoff.ts";
import type { PiDeckState } from "../src/types.ts";

test("normalizeState 对垃圾输入返回空结构", () => {
  assert.deepEqual(normalizeState(null), { projects: [], sessions: [], runtimes: [], pendingUiRequests: [] });
  assert.deepEqual(normalizeState({ projects: "nope" }), {
    projects: [],
    sessions: [],
    runtimes: [],
    pendingUiRequests: [],
  });
});

test("normalizeState 过滤非对象元素", () => {
  const state = normalizeState({ projects: [{ id: "p1" }, null, 3], sessions: [] });
  assert.equal(state.projects.length, 1);
  assert.equal(state.projects[0]?.id, "p1");
});

const state: PiDeckState = {
  projects: [],
  sessions: [],
  runtimes: [
    { sessionId: "s1", agentId: "a1", runtimeGeneration: 2 },
    { sessionId: "s2", agentId: "a2", runtimeGeneration: 1 },
  ],
  pendingUiRequests: [],
};

test("findTarget 只取三元组", () => {
  assert.deepEqual(findTarget(state, "s1"), { sessionId: "s1", agentId: "a1", runtimeGeneration: 2 });
  assert.equal(findTarget(state, "nope"), undefined);
});

test("requireTarget 在无实例时抛 runtime 错误", () => {
  assert.throws(
    () => requireTarget(state, "nope"),
    (error: unknown) => (error as { kind?: string }).kind === "runtime",
  );
});

test("isTargetStale 识别 generation 变化", () => {
  assert.equal(isTargetStale(state, { sessionId: "s1", agentId: "a1", runtimeGeneration: 2 }), false);
  assert.equal(isTargetStale(state, { sessionId: "s1", agentId: "a1", runtimeGeneration: 1 }), true);
  assert.equal(isTargetStale(state, { sessionId: "gone", agentId: "a1", runtimeGeneration: 2 }), true);
});

test("sameTarget 比较语义", () => {
  assert.equal(sameTarget({ sessionId: "s1", agentId: "a1", runtimeGeneration: 2 }, findTarget(state, "s1")), true);
  assert.equal(sameTarget(null, findTarget(state, "s1")), false);
});

test("Backoff 递增且不超过上限，reset 归零", () => {
  const backoff = new Backoff({ baseMs: 100, maxMs: 400, jitter: 0, factor: 2, random: () => 0.5 });
  assert.equal(backoff.nextDelay(), 100);
  assert.equal(backoff.nextDelay(), 200);
  assert.equal(backoff.nextDelay(), 400);
  assert.equal(backoff.nextDelay(), 400);
  assert.equal(backoff.attempts, 4);
  backoff.reset();
  assert.equal(backoff.attempts, 0);
  assert.equal(backoff.nextDelay(), 100);
});
