/**
 * 回合计时：输入框上方那行从「运行中…」改成计时。
 *
 * 用户要求：运行中显示计时，回答结束后显示「计时 + 完成时间」。
 * 这里只测纯函数（文案与口径），DOM 的定时器部分靠 verify:send 兜。
 */

import test from "node:test";
import assert from "node:assert/strict";

const { formatTurnElapsed, formatFinishClock, turnStatusText, turnStatusTitle, lastUserTimestamp } = await import(
  "../src/ui/turn-clock.ts"
);

test("运行中耗时：1 分钟内一位小数，超过用 m:ss", () => {
  assert.equal(formatTurnElapsed(0), "0.0s");
  assert.equal(formatTurnElapsed(340), "0.3s");
  assert.equal(formatTurnElapsed(12_400), "12.4s");
  assert.equal(formatTurnElapsed(59_900), "59.9s");
  assert.equal(formatTurnElapsed(60_000), "1:00");
  assert.equal(formatTurnElapsed(65_200), "1:05");
  assert.equal(formatTurnElapsed(3_725_000), "62:05");
  // 负值/NaN（时钟回拨、没拿到锚点）不能出现 -0.5s 这种东西
  assert.equal(formatTurnElapsed(-5_000), "0.0s");
  assert.equal(formatTurnElapsed(Number.NaN), "0.0s");
});

test("完成时刻：今天只显示 HH:MM，跨天补日期", () => {
  const today = new Date(2026, 9, 31, 15, 32, 5).getTime();
  assert.equal(formatFinishClock(today, today), "15:32");

  const yesterday = new Date(2026, 9, 30, 9, 5, 0).getTime();
  assert.equal(formatFinishClock(yesterday, today), "10/30 09:05");

  assert.equal(formatFinishClock(0, today), "");
  assert.equal(formatFinishClock(Number.NaN, today), "");
});

test("运行中：显示计时，不再写「运行中…」", () => {
  const now = 1_000_000;
  const text = turnStatusText({ startedAt: now - 12_400, finishedAt: null }, "running", now);
  assert.equal(text, "12.4s");
  assert.ok(!text.includes("运行中"), "文字让位给计时，转圈图标负责表达「在跑」");
});

test("运行中没有锚点也要有东西显示（不出现空白）", () => {
  assert.equal(turnStatusText(null, "running", 5_000), "0.0s");
});

test("结束后：计时 + 完成时间 + 完成", () => {
  const started = new Date(2026, 9, 31, 15, 20, 0).getTime();
  const finished = started + 12_400;
  const text = turnStatusText({ startedAt: started, finishedAt: finished }, "finished", finished + 60_000);
  assert.equal(text, "用时 12.4s · 15:20 完成");
});

test("失败/停止换掉最后一个词，用时与时刻仍然保留", () => {
  const started = new Date(2026, 9, 31, 8, 0, 0).getTime();
  const finished = started + 3_000;
  assert.equal(
    turnStatusText({ startedAt: started, finishedAt: finished }, "error", finished),
    "用时 3.0s · 08:00 · 本轮失败",
  );
  assert.equal(
    turnStatusText({ startedAt: started, finishedAt: finished }, "aborted", finished),
    "用时 3.0s · 08:00 · 已停止",
  );
});

test("没有回合信息时退回旧文案（历史会话不该冒出一行 0.0s）", () => {
  assert.equal(turnStatusText(null, "finished", 0), "");
  assert.equal(turnStatusText(null, "error", 0), "本轮失败");
  assert.equal(turnStatusText(null, "aborted", 0), "已停止");
});

test("标题提示：说明口径是整轮墙钟，不是单条模型请求", () => {
  assert.ok(turnStatusTitle("running").includes("本轮已运行"));
  assert.ok(turnStatusTitle("finished").includes("完成时刻"));
});

test("lastUserTimestamp：倒着找最后一条用户提问", () => {
  const messages = [
    { role: "user", timestamp: 100 },
    { role: "assistant", timestamp: 200 },
    { role: "user", timestamp: 300 },
    { role: "assistant", timestamp: 400 },
  ];
  assert.equal(lastUserTimestamp(messages), 300);
  // 只有 assistant 时不能拿它当回合起点
  assert.equal(lastUserTimestamp([{ role: "assistant", timestamp: 400 }]), undefined);
  assert.equal(lastUserTimestamp([]), undefined);
  assert.equal(lastUserTimestamp([{ role: "user", timestamp: "bad" }]), undefined);
});
