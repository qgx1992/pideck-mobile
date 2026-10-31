/**
 * 吞吐指标：首 token 延迟、流式 tok/s、均速 tok/s、会话平均。
 *
 * 字段来自 `/runtime/state`（实测 PiDeck 0.9.8 都有），口径与桌面端 `messagePerf.settle` 一致，
 * 本模块不自己算，只负责取数与文案：
 *   ttftMs              首 token 延迟：请求发出 → 首个**正文** delta（没有正文退回首个 delta）
 *   tps                 流式 TPS：最近一条回复的输出 tokens ÷（首个 delta → 终态）。
 *                       注意分母含思考与流内停顿，只排除首个 delta 之前的排队与首字等待
 *   endToEndTps         端到端 TPS（均速）：同一批 tokens ÷ totalMs
 *   sessionTps          会话平均流式 TPS：整段会话累计输出 ÷ 解码区间并集（子代理并发重叠算一次）
 *   sessionEndToEndTps  会话平均端到端 TPS：同上，但区间从请求发出算起
 *   totalMs             最近一条回复的墙钟耗时（请求 → 完成）
 *
 * `outputTokens` 是 usage **累计**投影，不是本条回复的 token 数，别拿它反推 TPS。
 *
 * 桌面端把口径做成设置二选一（tpsDisplayMode：streaming / endToEnd）并按整数显示；
 * 手机屏幕窄，两个并列（流 = 生成速度，均 = 端到端速度），会话级数字放进「上下文用量」面板，
 * 与桌面「会话详情」同位置。
 */

export interface PerfMetrics {
  /** 首 token 延迟（毫秒） */
  ttftMs?: number;
  /** 流式 TPS（纯生成速度） */
  streamingTps?: number;
  /** 端到端 TPS（含首 token 等待，即「均速」） */
  averageTps?: number;
  /** 会话平均流式 TPS */
  sessionStreamingTps?: number;
  /** 会话平均端到端 TPS */
  sessionAverageTps?: number;
  /** 最近一条回复的墙钟耗时 */
  totalMs?: number;
  /** usage 累计输出 token（不是本条回复的量） */
  outputTokens?: number;
}

function num(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

/** 速度类字段：桌面端 `>= 0` 也算有效（会显示 0 tok/s），只有负数/NaN 才是缺失。 */
function speed(value: unknown): number | undefined {
  const parsed = num(value);
  return parsed !== undefined && parsed >= 0 ? parsed : undefined;
}

/** 从 /runtime/state 的 value 里取吞吐指标；一个都没有就返回 null。 */
export function readPerfMetrics(raw: unknown): PerfMetrics | null {
  const value = unwrap(raw);
  if (!value || typeof value !== "object") return null;
  const record = value as Record<string, unknown>;

  const metrics: PerfMetrics = {};
  const ttftMs = num(record.ttftMs);
  if (ttftMs !== undefined && ttftMs > 0) metrics.ttftMs = ttftMs;

  const totalMs = num(record.totalMs);
  if (totalMs !== undefined && totalMs > 0) metrics.totalMs = totalMs;

  const streaming = speed(record.tps);
  if (streaming !== undefined) metrics.streamingTps = streaming;

  const average = speed(record.endToEndTps);
  if (average !== undefined) metrics.averageTps = average;

  const sessionStreaming = speed(record.sessionTps);
  if (sessionStreaming !== undefined) metrics.sessionStreamingTps = sessionStreaming;

  const sessionAverage = speed(record.sessionEndToEndTps);
  if (sessionAverage !== undefined) metrics.sessionAverageTps = sessionAverage;

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

/** 速度：和桌面端统计栏一样取整（`toFixed(0)`），0 也照显。 */
export function formatTps(value: number): string {
  if (!Number.isFinite(value) || value < 0) return "-";
  return String(Math.round(value));
}

export interface PerfChip {
  key: "ttft" | "total" | "streaming" | "average";
  /** 紧凑显示用（贴边栏那一行） */
  text: string;
  /** 长按/悬停提示 */
  title: string;
}

/**
 * 生成要显示的指标胶囊。顺序照桌面端统计栏：首 token → 总耗时 → 速度。
 * 缺失的指标直接不出现，不做「--」占位（窄屏上宁可少一条）。
 */
export function perfChips(metrics: PerfMetrics | null): PerfChip[] {
  if (!metrics) return [];
  const chips: PerfChip[] = [];

  if (metrics.ttftMs !== undefined) {
    const text = formatDuration(metrics.ttftMs);
    chips.push({
      key: "ttft",
      text: `首 ${text}`,
      title: `首 token 延迟 ${text}：从请求发出到收到第一个输出 token 的耗时，仅代表最近一条回复`,
    });
  }
  if (metrics.totalMs !== undefined) {
    const text = formatDuration(metrics.totalMs);
    chips.push({
      key: "total",
      text: `总 ${text}`,
      title: `回复总耗时 ${text}：最近一条回复从请求到完成的墙钟耗时`,
    });
  }
  if (metrics.streamingTps !== undefined) {
    const text = formatTps(metrics.streamingTps);
    chips.push({
      key: "streaming",
      text: `流 ${text} tok/s`,
      title: `流式 TPS ${text}：最近一条回复的输出 tokens ÷ 从首个有效思考或正文片段到结束的耗时；不含首 token 等待与工具执行`,
    });
  }
  if (metrics.averageTps !== undefined) {
    const text = formatTps(metrics.averageTps);
    chips.push({
      key: "average",
      text: `均 ${text} tok/s`,
      title: `端到端 TPS ${text}：最近一条回复的输出 tokens ÷ 本次模型请求从发出到结束的耗时；包含首 token 等待，不含前序工具执行`,
    });
  }
  return chips;
}

/** 面板明细行（与 contextRows 同形状，直接拼在一起用）。 */
export interface PerfRow {
  key: string;
  label: string;
  value: string;
}

/**
 * 「上下文用量」面板里的吞吐明细：先上次回复，再会话平均。
 * 桌面端也是如此——胶囊/统计栏只放单条回复，会话级留在详情面板。
 */
export function perfRows(metrics: PerfMetrics | null): PerfRow[] {
  if (!metrics) return [];
  const rows: PerfRow[] = [];
  const speedText = (value: number) => `${formatTps(value)} tok/s`;

  if (metrics.ttftMs !== undefined) {
    rows.push({ key: "ttft", label: "首 token 延迟", value: formatDuration(metrics.ttftMs) });
  }
  if (metrics.totalMs !== undefined) {
    rows.push({ key: "total", label: "回复总耗时", value: formatDuration(metrics.totalMs) });
  }
  if (metrics.streamingTps !== undefined) {
    rows.push({ key: "streaming", label: "流式 TPS", value: speedText(metrics.streamingTps) });
  }
  if (metrics.averageTps !== undefined) {
    rows.push({ key: "average", label: "端到端 TPS", value: speedText(metrics.averageTps) });
  }
  if (metrics.sessionStreamingTps !== undefined) {
    rows.push({
      key: "session-streaming",
      label: "会话平均流式 TPS",
      value: speedText(metrics.sessionStreamingTps),
    });
  }
  if (metrics.sessionAverageTps !== undefined) {
    rows.push({
      key: "session-average",
      label: "会话平均端到端 TPS",
      value: speedText(metrics.sessionAverageTps),
    });
  }
  return rows;
}
