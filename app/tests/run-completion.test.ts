/** 任务完成检测：快照差分、通知文案。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  askNotificationBody,
  askNotificationTitle,
  completionBody,
  completionTitle,
  diffCompletedRuns,
  diffNewPendingRequests,
} from "../src/ui/run-completion.ts";

test("running → idle 算完成", () => {
  const done = diffCompletedRuns(
    [{ sessionId: "s1", status: "running" }],
    [{ sessionId: "s1", status: "idle" }],
  );
  assert.deepEqual(done, [{ sessionId: "s1", endedAs: "idle" }]);
});

test("running → 快照里消失 也算完成（会话被关闭/重启）", () => {
  const done = diffCompletedRuns([{ sessionId: "s1", status: "running" }], []);
  assert.deepEqual(done, [{ sessionId: "s1", endedAs: "gone" }]);
});

test("running → error 带出错误状态", () => {
  const done = diffCompletedRuns(
    [{ sessionId: "s1", status: "running" }],
    [{ sessionId: "s1", status: "error" }],
  );
  assert.equal(done[0]?.endedAs, "error");
});

test("首次快照（prev 为空）不误报", () => {
  assert.deepEqual(diffCompletedRuns([], [{ sessionId: "s1", status: "idle" }]), []);
});

test("一直 running 不重复通知", () => {
  assert.deepEqual(
    diffCompletedRuns(
      [{ sessionId: "s1", status: "running" }],
      [{ sessionId: "s1", status: "running" }],
    ),
    [],
  );
});

test("本来就 idle 的会话不会触发", () => {
  assert.deepEqual(
    diffCompletedRuns(
      [{ sessionId: "s1", status: "idle" }],
      [{ sessionId: "s1", status: "idle" }],
    ),
    [],
  );
});

test("多个会话只挑出真正跑完的", () => {
  const done = diffCompletedRuns(
    [
      { sessionId: "a", status: "running" },
      { sessionId: "b", status: "running" },
      { sessionId: "c", status: "idle" },
    ],
    [
      { sessionId: "a", status: "running" },
      { sessionId: "b", status: "idle" },
    ],
  );
  assert.deepEqual(done, [{ sessionId: "b", endedAs: "idle" }]);
});

test("通知文案：出错与正常区分", () => {
  assert.equal(completionTitle("idle"), "任务完成");
  assert.equal(completionTitle("gone"), "任务完成");
  assert.equal(completionTitle("error"), "任务出错");
});

test("通知正文：带会话标题，缺标题时兜底", () => {
  assert.equal(completionBody("修 CI"), "「修 CI」已结束");
  assert.equal(completionBody("   "), "会话已结束，点击查看");
  assert.equal(completionBody(undefined), "会话已结束，点击查看");
});

/* ---------------- 问答卡通知 ---------------- */

test("新出现的待答卡片被检出", () => {
  const items = diffNewPendingRequests(
    [],
    [{ sessionId: "s1", requestId: "r1", title: "部署到哪？" }],
  );
  assert.equal(items.length, 1);
  assert.equal(items[0]?.sessionId, "s1");
  assert.equal(items[0]?.count, 1);
  assert.equal(items[0]?.firstTitle, "部署到哪？");
});

test("已存在的卡片不重复通知", () => {
  const prev = [{ sessionId: "s1", requestId: "r1" }];
  assert.deepEqual(diffNewPendingRequests(prev, [{ sessionId: "s1", requestId: "r1" }]), []);
});

test("同一会话多个新提问合并成一条", () => {
  const items = diffNewPendingRequests([], [
    { sessionId: "s1", requestId: "r1", title: "第一题" },
    { sessionId: "s1", requestId: "r2", title: "第二题" },
    { sessionId: "s2", requestId: "r3", title: "别的会话" },
  ]);
  assert.equal(items.length, 2);
  const s1 = items.find((i) => i.sessionId === "s1");
  assert.equal(s1?.count, 2);
  assert.equal(s1?.firstTitle, "第一题", "正文用第一条标题");
});

test("卡片被回答后消失，不会再触发", () => {
  const prev = [{ sessionId: "s1", requestId: "r1" }];
  assert.deepEqual(diffNewPendingRequests(prev, []), []);
});

test("提问通知文案", () => {
  assert.equal(askNotificationTitle(), "AI 在等你回答");
  assert.equal(
    askNotificationBody({ sessionId: "s1", count: 1, firstTitle: "部署到哪？" }, "修 CI"),
    "「修 CI」：部署到哪？",
  );
  assert.equal(
    askNotificationBody({ sessionId: "s1", count: 3, firstTitle: "" }, "修 CI"),
    "「修 CI」有 3 个问题待回答",
  );
  assert.equal(askNotificationBody({ sessionId: "s1", count: 1, firstTitle: "" }, "修 CI"), "「修 CI」有一个问题待回答");
  assert.equal(askNotificationBody({ sessionId: "s1", count: 1, firstTitle: "" }), "有一个问题待回答");
});
