/** 上下文用量的解析与展示。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  contextChipLabel,
  contextChipTitle,
  contextPollIntervalMs,
  contextRows,
  formatPercent,
  formatTokens,
  readContextUsage,
  resolveContextPercent,
  unwrapRuntimeValue,
} from "../src/ui/context-usage.ts";

const rawUsage = {
  contextWindow: 200_000,
  contextTokens: 48_000,
  contextPercent: 24,
  inputTokens: 51_200,
  outputTokens: 3_400,
  cost: 0.1234,
  cacheHitPercent: 91.5,
  contextOverflow: false,
  isCompacting: false,
  provider: "magpie",
};

test("优先级：服务端 contextPercent > 自算", () => {
  assert.equal(resolveContextPercent({ contextPercent: 24, contextWindow: 100, contextTokens: 50 }), 24);
  assert.equal(resolveContextPercent({ contextWindow: 100, contextTokens: 50 }), 50);
  assert.equal(resolveContextPercent({ contextWindow: 0, contextTokens: 50 }), 0);
  assert.equal(resolveContextPercent({ contextWindow: 100, contextTokens: 500 }), 100, "夹在 100 以内");
});

test("读 /runtime/state：两层包装都能拆", () => {
  assert.deepEqual(unwrapRuntimeValue({ value: { value: 7 } }), 7);
  assert.deepEqual(unwrapRuntimeValue({ value: 7 }), 7);

  const usage = readContextUsage({ value: { target: {}, value: rawUsage } });
  assert.ok(usage);
  assert.equal(usage.contextWindow, 200_000);
  assert.equal(usage.contextPercent, 24);
  assert.equal(usage.inputTokens, 51_200);
  assert.equal(usage.cacheHitPercent, 91.5);
  assert.equal(usage.provider, "magpie");
  assert.equal(usage.contextOverflow, false);
});

test("没有 contextWindow 就当作没有数据（和 Web 端一致）", () => {
  assert.equal(readContextUsage({ value: { contextTokens: 100 } }), null);
  assert.equal(readContextUsage(null), null);
  assert.equal(readContextUsage({ value: "x" }), null);
});

test("溢出判定：显式标记或百分比到 100", () => {
  const overflow = readContextUsage({
    value: { contextWindow: 100, contextTokens: 99, contextPercent: 100 },
  });
  assert.equal(overflow?.contextOverflow, true);
  const flagged = readContextUsage({
    value: { contextWindow: 100, contextTokens: 10, contextOverflow: true },
  });
  assert.equal(flagged?.contextOverflow, true);
});

test("token 短格式", () => {
  assert.equal(formatTokens(0), "0");
  assert.equal(formatTokens(999), "999");
  assert.equal(formatTokens(1_000), "1K");
  assert.equal(formatTokens(12_345), "12.3K");
  assert.equal(formatTokens(123_456), "123K");
  assert.equal(formatTokens(1_200_000), "1.2M");
});

test("百分比文案", () => {
  assert.equal(formatPercent(0), "0");
  assert.equal(formatPercent(7.5), "7.5");
  assert.equal(formatPercent(42.4), "42");
  assert.equal(formatPercent(120), "100");
});

test("chip 文案与 title", () => {
  const usage = {
    contextWindow: 200_000,
    contextTokens: 48_000,
    contextPercent: 24,
    contextOverflow: false,
    isCompacting: false,
  };
  assert.equal(contextChipLabel(usage), "24%");
  assert.equal(contextChipLabel({ ...usage, isCompacting: true }), "压缩中");
  const title = contextChipTitle(usage);
  assert.ok(title.includes("24%"));
  assert.ok(title.includes("48K / 200K"));
  assert.ok(contextChipTitle({ ...usage, contextOverflow: true }).includes("已超出"));
});

test("明细行包含上下文/输入/输出/缓存/花费", () => {
  const usage = readContextUsage({ value: rawUsage });
  const rows = contextRows(usage!);
  const labels = rows.map((row) => row.label);
  assert.deepEqual(labels, ["上下文用量", "输入 token", "输出 token", "缓存命中", "累计花费"]);
  assert.equal(rows[0]?.value, "48K / 200K");
  assert.equal(rows[4]?.value, "$0.1234");
});

test("轮询节奏照搬 Web 端", () => {
  assert.equal(contextPollIntervalMs(false, true), 10_000);
  assert.equal(contextPollIntervalMs(true, true), 3_000);
  assert.equal(contextPollIntervalMs(true, false), 1_000);
});
