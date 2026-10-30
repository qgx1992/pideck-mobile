/**
 * 待办清单历史：三条用户反馈的回归。
 *
 *   1. 全部完成**不能**直接消失，要留到下一轮发送
 *   2. 轮询不能刷新记录时间（以前时间一直在跳）
 *   3. 多次触发要记多条（以前同一个 planId 被反复合并成一条）
 */

import test from "node:test";
import assert from "node:assert/strict";

const {
  TODO_HISTORY_LIMIT,
  historyProgressLabel,
  historyStatusLabel,
  historyTimeLabel,
  mergeTodoHistory,
  parseTodoHistory,
  planKeyOf,
  sealTodoHistory,
  shouldShowTodoStrip,
} = await import("../src/ui/todo-history-model.ts");

const view = (planId: string, statuses: string[]) => ({
  planId,
  items: statuses.map((status, index) => ({ id: String(index), text: `任务 ${index}`, status })),
  total: statuses.length,
  done: statuses.filter((status) => status === "completed").length,
  active: statuses.filter((status) => status === "in_progress").length,
  unfinished: statuses.some((status) => status !== "completed"),
});

test("planKeyOf：有 planId 用它，没有就用条目内容兜底", () => {
  assert.equal(planKeyOf(view("8", ["pending"]) as never), "8");
  assert.equal(planKeyOf(view("", ["pending"]) as never), "0:任务 0");
  assert.equal(planKeyOf(view("", []) as never), "unknown");
});

/**
 * 用户反馈 #1：全部完成后还应该留在输入框上方，直到下一轮发送。
 */
test("显示规则：全部完成也继续显示，只有下一轮收口才撤", () => {
  assert.equal(shouldShowTodoStrip(null), false);
  assert.equal(shouldShowTodoStrip(view("8", ["completed", "completed"]) as never), true);
  assert.equal(shouldShowTodoStrip(view("8", ["pending"]) as never), true);
});

/**
 * 用户反馈 #2：时间不能随着轮询一直更新。
 */
test("同一轮里进度不变时，时间必须保持首次触发时间", () => {
  const first = mergeTodoHistory([], view("8", ["pending", "pending"]) as never, 1_000);
  assert.equal(first[0]!.firstSeenAt, 1_000);

  // 5 秒后又拉到同一份清单（内容没变）
  const second = mergeTodoHistory(first, view("8", ["pending", "pending"]) as never, 6_000);
  assert.equal(second.length, 1);
  assert.equal(second[0]!.firstSeenAt, 1_000, "首次时间不变");
  assert.equal(second[0]!.lastChangedAt, 1_000, "内容没变就不该刷新变动时间");
  assert.equal(historyTimeLabel(second[0]!), historyTimeLabel(first[0]!), "展示时间不能跳");
});

test("进度推进时才更新 lastChangedAt，firstSeenAt 仍不变", () => {
  const first = mergeTodoHistory([], view("8", ["pending", "pending"]) as never, 1_000);
  const advanced = mergeTodoHistory(first, view("8", ["completed", "pending"]) as never, 2_000);
  assert.equal(advanced[0]!.firstSeenAt, 1_000);
  assert.equal(advanced[0]!.lastChangedAt, 2_000);
  assert.equal(advanced[0]!.done, 1);
});

/**
 * 用户反馈 #3：多次触发要记多条。
 */
test("收口后同 planId 再次触发 → 新增一条记录", () => {
  const round1 = mergeTodoHistory([], view("8", ["completed"]) as never, 1_000);
  assert.equal(round1.length, 1);

  // 用户发出下一轮 → 收口
  const sealed = sealTodoHistory(round1, view("8", ["completed"]) as never, 2_000);
  assert.equal(sealed.length, 1);
  assert.equal(sealed[0]!.sealed, true);

  // 下一轮 agent 又列了清单（甚至 planId 还是 8）
  const round2 = mergeTodoHistory(sealed, view("8", ["pending"]) as never, 3_000);
  assert.equal(round2.length, 2, "收口后应新增一条，不能合并回旧记录");
  // 最新的在前
  assert.equal(round2[0]!.sealed, false);
  assert.equal(round2[0]!.firstSeenAt, 3_000);
  assert.equal(round2[1]!.sealed, true);
});

test("收口时还没落过历史的清单也会补一条", () => {
  const sealed = sealTodoHistory([], view("9", ["completed"]) as never, 5_000);
  assert.equal(sealed.length, 1);
  assert.equal(sealed[0]!.sealed, true);
  assert.equal(sealed[0]!.finished, true);
});

test("收口 null 不影响现有历史", () => {
  const history = mergeTodoHistory([], view("8", ["pending"]) as never, 1_000);
  assert.deepEqual(sealTodoHistory(history, null, 2_000), history);
});

test("排序按首次触发时间倒序，进度推进不会让老记录插队", () => {
  let history = mergeTodoHistory([], view("8", ["pending"]) as never, 1_000);
  history = mergeTodoHistory(history, view("9", ["pending"]) as never, 3_000);
  assert.deepEqual(history.map((entry) => entry.planId), ["9", "8"]);

  // 老计划后来推进了进度（还没收口）→ 更新内容但不该跳到最前面
  const touched = mergeTodoHistory(history, view("8", ["completed", "pending"]) as never, 9_000);
  assert.deepEqual(touched.map((entry) => entry.planId), ["9", "8"]);
  assert.equal(touched[1]!.lastChangedAt, 9_000);
});

test("超过上限丢最旧的", () => {
  let history: never[] = [];
  for (let index = 0; index < TODO_HISTORY_LIMIT + 5; index += 1) {
    history = mergeTodoHistory(history as never, view(String(index), ["pending"]) as never, index * 1_000) as never;
  }
  const kept = history as unknown as Array<{ planId: string }>;
  assert.equal(kept.length, TODO_HISTORY_LIMIT);
  assert.equal(kept[0]!.planId, String(TODO_HISTORY_LIMIT + 4));
});

test("parseTodoHistory：宽松解析，脏数据丢弃，兼容旧字段", () => {
  const parsed = parseTodoHistory([
    { planId: "a", items: [{ id: 1, text: "做完", status: "completed" }], firstSeenAt: 100, lastChangedAt: 200 },
    // 旧版本只存了 updatedAt
    { planId: "b", items: [{ id: 2, text: "旧", status: "pending" }], firstSeenAt: 300, updatedAt: 900 },
    { planId: "", items: [] },
    null,
  ]);
  assert.equal(parsed.length, 2);
  // 倒序：b（300）比 a（100）新
  assert.deepEqual(parsed.map((entry) => entry.planId), ["b", "a"]);
  assert.equal(parsed[0]!.lastChangedAt, 900, "缺 lastChangedAt 时退回 updatedAt");
  assert.equal(parsed[1]!.sealed, false);
  assert.deepEqual(parseTodoHistory(null), []);
});

test("状态与进度文案", () => {
  assert.equal(historyProgressLabel({ done: 2, total: 5 } as never), "2/5");
  assert.equal(historyStatusLabel({ finished: false, sealed: false } as never), "进行中");
  assert.equal(historyStatusLabel({ finished: true, sealed: false } as never), "已完成（当前轮）");
  assert.equal(historyStatusLabel({ finished: true, sealed: true } as never), "已完成");

  const now = new Date();
  const stamp = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 9, 5).getTime();
  assert.match(historyTimeLabel({ firstSeenAt: stamp } as never), /^09:05$/);
  assert.equal(historyTimeLabel({ firstSeenAt: 0 } as never), "");
});
