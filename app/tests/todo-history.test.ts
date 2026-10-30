/**
 * 待办清单历史：归档、去重、上限、显示规则。
 *
 * 服务端只给当前那一份清单（换计划后旧的被覆盖），所以历史完全靠客户端攒。
 * 这几条规则直接决定「会不会重复记同一份」「已完成的是否还挂在输入框上」。
 */

import test from "node:test";
import assert from "node:assert/strict";

const {
  TODO_HISTORY_LIMIT,
  entryFromView,
  historyProgressLabel,
  historyTimeLabel,
  mergeTodoHistory,
  parseTodoHistory,
  planKeyOf,
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

test("merge：同一 planId 就地更新进度，不新增条目", () => {
  const first = mergeTodoHistory([], view("8", ["pending", "pending"]) as never, 1_000);
  assert.equal(first.length, 1);
  assert.equal(first[0]!.done, 0);

  const second = mergeTodoHistory(first, view("8", ["completed", "pending"]) as never, 2_000);
  assert.equal(second.length, 1, "同一个计划不该变成两条");
  assert.equal(second[0]!.done, 1);
  assert.equal(second[0]!.firstSeenAt, 1_000, "首次时间要保留");
  assert.equal(second[0]!.updatedAt, 2_000);
});

test("merge：新 planId 追加，并按更新时间倒序", () => {
  let history = mergeTodoHistory([], view("8", ["completed"]) as never, 1_000);
  history = mergeTodoHistory(history, view("9", ["pending"]) as never, 3_000);
  assert.equal(history.length, 2);
  assert.deepEqual(history.map((entry) => entry.planId), ["9", "8"]);

  // 老计划又有进展 → 时间刷新后应回到最前
  history = mergeTodoHistory(history, view("8", ["completed", "pending"]) as never, 4_000);
  assert.deepEqual(history.map((entry) => entry.planId), ["8", "9"]);
});

test("merge：超过上限丢最旧的", () => {
  let history: unknown[] = [];
  for (let index = 0; index < TODO_HISTORY_LIMIT + 5; index += 1) {
    history = mergeTodoHistory(history as never, view(String(index), ["pending"]) as never, index * 1_000);
  }
  const kept = history as Array<{ planId: string }>;
  assert.equal(kept.length, TODO_HISTORY_LIMIT);
  // 留下的是最新的那批
  assert.equal(kept[0]!.planId, String(TODO_HISTORY_LIMIT + 4));
});

test("finished 标记：全完成才是 true", () => {
  const entry = entryFromView(view("8", ["completed", "completed"]) as never, 1_000);
  assert.equal(entry.finished, true);
  assert.equal(entryFromView(view("8", ["completed", "pending"]) as never, 1_000).finished, false);
});

test("parseTodoHistory：宽松解析，脏数据丢弃", () => {
  const parsed = parseTodoHistory([
    { planId: "a", items: [{ id: 1, text: "做完", status: "completed" }], firstSeenAt: 100, updatedAt: 200 },
    { planId: "", items: [] },
    { planId: "b", items: [{ text: "  " }] },
    null,
    "nope",
  ]);
  assert.equal(parsed.length, 1);
  assert.equal(parsed[0]!.planId, "a");
  assert.equal(parsed[0]!.done, 1);
  assert.equal(parsed[0]!.finished, true);
  // 未知状态按 pending
  const messy = parseTodoHistory([{ planId: "c", items: [{ id: "x", text: "t", status: "weird" }] }]);
  assert.equal(messy[0]!.items[0]!.status, "pending");
});

test("parseTodoHistory：非数组返回空", () => {
  assert.deepEqual(parseTodoHistory(null), []);
  assert.deepEqual(parseTodoHistory({}), []);
});

/**
 * 用户报的 bug：清单全部完成后，输入框上方还一直挂着。
 * 现在规则是「只有还有未完成才显示」。
 */
test("显示规则：全部完成就不再挂在输入框上方", () => {
  assert.equal(shouldShowTodoStrip(null), false);
  assert.equal(shouldShowTodoStrip(view("8", ["completed", "completed"]) as never), false);
  assert.equal(shouldShowTodoStrip(view("8", ["completed", "pending"]) as never), true);
  assert.equal(shouldShowTodoStrip(view("8", ["in_progress", "pending"]) as never), true);
});

test("历史条目的进度与时间文案", () => {
  assert.equal(historyProgressLabel({ done: 2, total: 5 } as never), "2/5");
  const now = new Date();
  const stamp = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 9, 5).getTime();
  assert.match(historyTimeLabel({ updatedAt: stamp, firstSeenAt: stamp } as never), /^09:05$/);
  assert.equal(historyTimeLabel({ updatedAt: 0, firstSeenAt: 0 } as never), "");
});
