/** 吞吐指标：解析与文案。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  formatDuration,
  formatTps,
  perfChips,
  readPerfMetrics,
} from "../src/ui/perf-metrics.ts";

// 实测 /runtime/state 的形状（只留相关字段）
const realState = {
  value: {
    target: {},
    value: {
      ttftMs: 1274,
      tps: 113.06532663316582,
      endToEndTps: 52.508751458576434,
      totalMs: 1714,
      outputTokens: 90,
      sessionTps: 113.06532663316582,
      sessionEndToEndTps: 52.508751458576434,
      contextWindow: 1_000_000,
    },
  },
};

test("从 /runtime/state 读出首 token、流式、均速", () => {
  const metrics = readPerfMetrics(realState);
  assert.equal(metrics?.ttftMs, 1274);
  assert.equal(Math.round(metrics?.streamingTps ?? 0), 113);
  assert.equal(Math.round(metrics?.averageTps ?? 0), 53, "四舍五入 52.5 → 53");
  assert.equal(metrics?.totalMs, 1714);
});

test("一层包装也能解析", () => {
  const metrics = readPerfMetrics({ value: { ttftMs: 900, tps: 40 } });
  assert.equal(metrics?.ttftMs, 900);
  assert.equal(metrics?.streamingTps, 40);
  assert.equal(metrics?.averageTps, undefined);
});

test("没有指标时返回 null（不占位置）", () => {
  assert.equal(readPerfMetrics({ value: { contextWindow: 200_000 } }), null);
  assert.equal(readPerfMetrics(null), null);
  assert.equal(readPerfMetrics({ value: "x" }), null);
});

test("0 / 负数 / NaN 当作缺失", () => {
  const metrics = readPerfMetrics({ value: { ttftMs: 0, tps: -1, endToEndTps: Number.NaN, totalMs: 500 } });
  assert.equal(metrics?.ttftMs, undefined);
  assert.equal(metrics?.streamingTps, undefined);
  assert.equal(metrics?.averageTps, undefined);
  assert.equal(metrics?.totalMs, 500, "其它字段仍在，所以不是 null");
});

test("时长格式", () => {
  assert.equal(formatDuration(820), "820ms");
  assert.equal(formatDuration(1_274), "1.3s");
  assert.equal(formatDuration(12_400), "12s");
  assert.equal(formatDuration(0), "-");
});

test("速度格式：整数不带小数", () => {
  assert.equal(formatTps(113.06), "113");
  assert.equal(formatTps(52.508), "52.5");
  assert.equal(formatTps(9.04), "9");
  assert.equal(formatTps(0), "-");
  assert.equal(formatTps(-3), "-");
});

test("胶囊顺序固定：首 token → 流式 → 均速，缺失项不占位", () => {
  const chips = perfChips(readPerfMetrics(realState));
  assert.deepEqual(chips.map((c) => c.key), ["ttft", "streaming", "average"]);
  assert.equal(chips[0]?.text, "首 1.3s");
  assert.equal(chips[1]?.text, "流 113 tok/s");
  assert.equal(chips[2]?.text, "均 52.5 tok/s");

  const partial = perfChips({ streamingTps: 30 });
  assert.deepEqual(partial.map((c) => c.key), ["streaming"], "只有流式时不出现其它占位");
});

test("没有指标时不产生任何胶囊", () => {
  assert.deepEqual(perfChips(null), []);
});

test("每个胶囊都带解释性 title", () => {
  for (const chip of perfChips(readPerfMetrics(realState))) {
    assert.ok(chip.title.length > 8, `${chip.key} 的 title 太短`);
  }
});
