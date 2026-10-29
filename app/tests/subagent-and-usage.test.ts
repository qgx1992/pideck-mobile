/**
 * 子代理活动条 + 供应商额度面板的展示模型。
 *
 * 两边都是「服务端可能给不全 / 可能压根没开」的能力，所以重点是：
 * 缺字段不炸、不可用时给一句人话，而不是抛错。
 */

import test from "node:test";
import assert from "node:assert/strict";

const { normalizeSubagents, shouldShowSubagents, summarizeSubagents } = await import(
  "../src/ui/subagent-model.ts"
);
const { buildUsageView, formatResetAt, usageIntervalMs } = await import("../src/ui/provider-usage-model.ts");

test("子代理：正常条目解析出状态文案", () => {
  const list = normalizeSubagents([
    { id: "a", type: "Explore", description: "找一下登录入口", status: "running", toolUses: 3 },
    { id: "b", type: "Review", description: "看下 diff", status: "completed", result: "没问题" },
    { id: "c", type: "Fix", description: "", status: "error", error: "编译失败" },
  ]);
  assert.equal(list.length, 3);
  assert.equal(list[0]!.statusLabel, "运行中");
  assert.equal(list[0]!.active, true);
  assert.equal(list[0]!.detail, "3 次工具调用");
  assert.equal(list[1]!.detail, "没问题");
  assert.equal(list[2]!.failed, true);
  assert.equal(list[2]!.detail, "编译失败");
});

test("子代理：未知状态按完成处理，脏数据被丢弃", () => {
  const list = normalizeSubagents([{ id: "x", status: "weird" }, null, "字符串", 42]);
  assert.equal(list.length, 1);
  assert.equal(list[0]!.status, "completed");
});

test("子代理：非数组输入返回空", () => {
  assert.deepEqual(normalizeSubagents(undefined), []);
  assert.deepEqual(normalizeSubagents({ subagents: [] }), []);
});

test("子代理：头部摘要与显隐", () => {
  const list = normalizeSubagents([
    { id: "a", status: "running" },
    { id: "b", status: "queued" },
    { id: "c", status: "error" },
  ]);
  const summary = summarizeSubagents(list);
  assert.equal(summary.total, 3);
  assert.equal(summary.active, 2);
  assert.equal(summary.failed, 1);
  assert.match(summary.title, /3 个子代理/);
  assert.match(summary.title, /2 运行中/);
  assert.match(summary.title, /1 失败/);
  assert.equal(shouldShowSubagents(list), true);
  assert.equal(shouldShowSubagents([]), false);
});

test("额度：periods 形态", () => {
  const view = buildUsageView({
    success: true,
    provider: "opencode-go",
    kind: "periods",
    periods: { rolling: { percent: 42.5, resetsAt: "2026-01-01T12:00:00Z" }, weekly: { percent: 10 } },
  });
  assert.equal(view.available, true);
  assert.equal(view.provider, "opencode-go");
  assert.equal(view.lines[0]!.label, "滚动窗口");
  assert.match(view.lines[0]!.value, /42\.5%/);
  assert.match(view.lines[0]!.value, /重置于/);
  assert.equal(view.lines[1]!.label, "本周");
});

test("额度：balance 形态", () => {
  const view = buildUsageView({ success: true, balance: { value: 12.345, currency: "CNY" } });
  assert.deepEqual(view.lines, [{ label: "余额", value: "12.35 CNY" }]);
});

test("额度：credits 形态缺 remaining 时用 total-used 反推", () => {
  const view = buildUsageView({ success: true, credits: { total: 100, used: 30 } });
  assert.deepEqual(view.lines, [
    { label: "剩余点数", value: "70" },
    { label: "总额", value: "100" },
    { label: "已用", value: "30" },
  ]);
});

test("额度：未开启用量查询 → 提示去桌面端打开", () => {
  const view = buildUsageView({ success: false, disabled: true, provider: "deepseek" });
  assert.equal(view.available, false);
  assert.match(view.hint ?? "", /没有开启/);
  assert.equal(view.provider, "deepseek");
});

test("额度：失败时透出服务端错误", () => {
  const view = buildUsageView({ success: false, error: "401 unauthorized" });
  assert.equal(view.available, false);
  assert.match(view.hint ?? "", /401/);
});

test("额度：成功但认不出格式时给原始片段", () => {
  const view = buildUsageView({ success: true, raw: "{\"foo\":1}" });
  assert.equal(view.available, false);
  assert.match(view.hint ?? "", /没识别出用量格式/);
});

test("额度：非对象输入不炸", () => {
  for (const value of [null, undefined, "x", 42]) {
    const view = buildUsageView(value, "p");
    assert.equal(view.available, false);
    assert.equal(view.provider, "p");
  }
});

test("重置时间的格式化与轮询间隔", () => {
  assert.match(formatResetAt("2026-01-02T03:04:00Z"), /^\d{2}-\d{2} \d{2}:\d{2}$/);
  assert.equal(formatResetAt("不是时间"), "不是时间");
  assert.equal(usageIntervalMs({ intervalMinutes: 5 }), 300_000);
  assert.equal(usageIntervalMs({ intervalMinutes: 0 }), 300_000);
  assert.equal(usageIntervalMs({ intervalMinutes: 0.1 }), 60_000);
  assert.equal(usageIntervalMs(null), 300_000);
});
