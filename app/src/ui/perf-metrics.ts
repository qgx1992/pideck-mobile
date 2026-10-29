/**
 * 吞吐指标：首 token 延迟、流式 tok/s、均速 tok/s。
 *
 * 字段来自 `/runtime/state`（实测 PiDeck 0.9.7 都有）：
 *   ttftMs        首 token 延迟（从发出到最后一次回复的第一个 token）
 *   tps           流式 TPS：最后一次回复的有效 token ÷ 生成耗时（不含等待与工具执行）
 *   endToEndTps   端到端 TPS：同样口径但把等待/工具时间也算进去，也就是「均速」
 *   totalMs       最后一次回复的墙钟耗时
 *   outputTokens / sessionTps / sessionEndToEndTps 备用
 *
 * 桌面端把 TPS 口径做成可切换的（设置 → TPS 统计口径：流式 / 端到端），
 * 手机端屏幕窄，干脆两个并列显示：流 = 生成速度，均 = 端到端速度。
 */

export interface PerfMetrics {
  /** 首 token 延迟（毫秒） */
  ttftMs?: number;
  /** 流式 TPS（纯生成速度） */
  streamingTps?: number;
  /** 端到端 TPS（含等待与工具执行，即「均速」） */
  averageTps?: number;
  /** 最后一次回复的墙钟耗时 */
  totalMs?: number;
  /** 最后一次回复的输出 token 数 */
  outputTokens?: number;
}

function num(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

/** 从 /runtime/state 的 value 里取吞吐指标；一个都没有就返回 null。 */
export function readPerfMetrics(raw: unknown): PerfMetrics | null {
  const value = unwrap(raw);
  if (!value || typeof value !== "object") return null;
  const record = value as Record<string, unknown>;

  const metrics: PerfMetrics = {};
  const ttftMs = num(record.ttftMs);
  if (ttftMs !== undefined && ttftMs > 0) metrics.ttftMs = ttftMs;

  const streaming = num(record.tps);
  if (streaming !== undefined && streaming > 0) metrics.streamingTps = streaming;

  const average = num(record.endToEndTps);
  if (average !== undefined && average > 0) metrics.averageTps = average;

  const totalMs = num(record.totalMs);
  if (totalMs !== undefined && totalMs > 0) metrics.totalMs = totalMs;

  const outputTokens = num(record.outputTokens);
  if (outputTokens !== undefined && outputTokens > 0) metrics.outputTokens = outputTokens;

  if (Object.keys(metrics).length === 0) return null;
  return metrics;
}

/** runtime 命令的返回可能是 { value } 或 { value: { value } }。 */
function unwrap(raw: unknown): unknown {
  if (!raw || typeof raw !== "object") return raw;
  const first = (raw as { value?: unknown }).value;
  if (first && typeof first === "object" && "value" in first) {
    return (first as { value?: unknown }).value;
  }
  return first;
}

/** 时长：不到 1 秒用 ms，超过用一位小数的秒。 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return "-";
  if (ms < 1_000) return `${Math.round(ms)}ms`;
  const seconds = ms / 1_000;
  return `${seconds >= 10 ? Math.round(seconds) : seconds.toFixed(1)}s`;
}

/** 速度：整数就不带小数，避免「113.0」这种噪音。 */
export function formatTps(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return "-";
  if (value >= 100) return String(Math.round(value));
  return String(Math.round(value * 10) / 10);
}

export interface PerfChip {
  key: "ttft" | "streaming" | "average" | "total";
  /** 紧凑显示用（贴边栏那一行） */
  text: string;
  /** 长按/悬停提示 */
  title: string;
}

/**
 * 生成要显示的指标胶囊。顺序固定：首 token → 流式 → 均速。
 * 缺失的指标直接不出现，不做「--」占位（窄屏上宁可少一条）。
 */
export function perfChips(metrics: PerfMetrics | null): PerfChip[] {
  if (!metrics) return [];
  const chips: PerfChip[] = [];

  if (metrics.ttftMs !== undefined) {
    chips.push({
      key: "ttft",
      text: `首 ${formatDuration(metrics.ttftMs)}`,
      title: `首 token 延迟 ${formatDuration(metrics.ttftMs)}（从发出到最后一次回复的第一个 token）`,
    });
  }
  if (metrics.streamingTps !== undefined) {
    chips.push({
      key: "streaming",
      text: `流 ${formatTps(metrics.streamingTps)} tok/s`,
      title: `流式 TPS ${formatTps(metrics.streamingTps)}：最后一次回复的有效 token ÷ 生成耗时（不含等待与工具执行）`,
    });
  }
  if (metrics.averageTps !== undefined) {
    chips.push({
      key: "average",
      text: `均 ${formatTps(metrics.averageTps)} tok/s`,
      title: `端到端 TPS ${formatTps(metrics.averageTps)}：同样口径但把等待与工具执行也算进去`,
    });
  }
  return chips;
}
