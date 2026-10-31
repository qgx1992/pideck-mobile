/** 吞吐指标：解析与文案。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  formatDuration,
  formatTps,
  perfChips,
  perfRows,
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

test("从 /runtime/state 读出首 token、流式、均速、会话平均", () => {
  const metrics = readPerfMetrics(realState);
  assert.equal(metrics?.ttftMs, 1274);
  assert.equal(Math.round(metrics?.streamingTps ?? 0), 113);
  assert.equal(Math.round(metrics?.averageTps ?? 0), 53, "四舍五入 52.5 → 53");
  assert.equal(metrics?.totalMs, 1714);
  assert.equal(metrics?.sessionStreamingTps, 113.06532663316582);
  assert.equal(metrics?.sessionAverageTps, 52.508751458576434);
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

test("0 是有效速度（桌面端也显示 0 tok/s），负数 / NaN 当缺失", () => {
  const metrics = readPerfMetrics({
    value: { ttftMs: 0, tps: 0, endToEndTps: Number.NaN, sessionTps: -1, totalMs: 500 },
  });
  assert.equal(metrics?.ttftMs, undefined);
  assert.equal(metrics?.streamingTps, 0);
  assert.equal(metrics?.averageTps, undefined);
  assert.equal(metrics?.sessionStreamingTps, undefined);
  assert.equal(metrics?.totalMs, 500, "其它字段仍在，所以不是 null");
});

test("时长格式", () => {
  assert.equal(formatDuration(820), "820ms");
  assert.equal(formatDuration(1_274), "1.3s");
  assert.equal(formatDuration(12_400), "12s");
  assert.equal(formatDuration(0), "-");
});

test("速度格式：和桌面端统计栏一样取整，0 也照显", () => {
  assert.equal(formatTps(113.06), "113");
  assert.equal(formatTps(52.508), "53");
  assert.equal(formatTps(9.04), "9");
  assert.equal(formatTps(0), "0");
  assert.equal(formatTps(-3), "-");
});

test("胶囊顺序固定：首 token → 总耗时 → 流式 → 均速，缺失项不占位", () => {
  const chips = perfChips(readPerfMetrics(realState));
  assert.deepEqual(chips.map((c) => c.key), ["ttft", "total", "streaming", "average"]);
  assert.equal(chips[0]?.text, "首 1.3s");
  assert.equal(chips[1]?.text, "总 1.7s");
  assert.equal(chips[2]?.text, "流 113 tok/s");
  assert.equal(chips[3]?.text, "均 53 tok/s");

  const partial = perfChips({ streamingTps: 30 });
  assert.deepEqual(partial.map((c) => c.key), ["streaming"], "只有流式时不出现其它占位");
});

test("面板明细：单条回复在前，会话平均在后", () => {
  const rows = perfRows(readPerfMetrics(realState));
  assert.deepEqual(
    rows.map((r) => r.key),
    ["ttft", "total", "streaming", "average", "session-streaming", "session-average"],
  );
  assert.equal(rows[2]?.value, "113 tok/s");
  assert.equal(rows[3]?.value, "53 tok/s");
  assert.equal(rows[4]?.label, "会话平均流式 TPS");
  assert.equal(rows[5]?.label, "会话平均端到端 TPS");
  assert.deepEqual(perfRows(null), []);
  assert.deepEqual(perfRows({ streamingTps: 30 }).map((r) => r.key), ["streaming"]);
});

test("没有指标时不产生任何胶囊", () => {
  assert.deepEqual(perfChips(null), []);
});

test("每个胶囊都带解释性 title", () => {
  for (const chip of perfChips(readPerfMetrics(realState))) {
    assert.ok(chip.title.length > 8, `${chip.key} 的 title 太短`);
  }
});
