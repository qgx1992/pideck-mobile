/**
 * 上下文用量：/runtime/state 里那组 token 字段的解析与展示。
 *
 * 对齐 Web 端（out/renderer web-*.js）的行为：
 *   - 顶部只显示一个环形百分比（ContextRing），contextWindow > 0 才出现
 *   - 点开是「上下文用量」面板：大号百分比 + 溢出徽章 + 明细行 + 压缩按钮
 *   - 百分比优先用服务端的 contextPercent，缺失时按 contextTokens / contextWindow 算
 *   - 溢出判定：contextOverflow === true 或 percent >= 100
 * 轮询节奏也照搬：streaming ? (事件流连着 ? 3s : 1s) : 10s，页面隐藏时不轮询。
 */

/** /runtime/state 的 value 里我们关心的字段（其余字段原样忽略）。 */
export interface ContextUsage {
  contextWindow: number;
  contextTokens: number;
  /** 0..100 */
  contextPercent: number;
  inputTokens?: number;
  outputTokens?: number;
  /** 累计花费（美元） */
  cost?: number;
  /** 缓存命中率 0..100 */
  cacheHitPercent?: number;
  contextOverflow: boolean;
  isCompacting: boolean;
  provider?: string;
  /** 轮询节奏用不到，但服务器顺手给了 */
  contextMessageTokens?: number;
  contextSystemTokens?: number;
  contextToolsTokens?: number;
}

function num(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

/**
 * 百分比：优先服务端字段，否则自己算，并夹在 0..100。
 * 与 Web 端 resolveContextPercent 等价。
 */
export function resolveContextPercent(raw: {
  contextPercent?: unknown;
  contextWindow?: unknown;
  contextTokens?: unknown;
}): number {
  const contextWindow = num(raw.contextWindow) ?? 0;
  const contextTokens = num(raw.contextTokens) ?? 0;
  const declared = num(raw.contextPercent);
  const percent =
    declared ?? (contextWindow > 0 ? Math.min(100, (contextTokens / contextWindow) * 100) : 0);
  if (!Number.isFinite(percent)) return 0;
  return Math.min(100, Math.max(0, percent));
}

/** 把 /runtime/state 的 value（可能被包了两层）转成 ContextUsage。 */
export function readContextUsage(raw: unknown): ContextUsage | null {
  const value = unwrapRuntimeValue(raw);
  if (!value || typeof value !== "object") return null;
  const record = value as Record<string, unknown>;

  const contextWindow = num(record.contextWindow) ?? 0;
  const contextTokens = num(record.contextTokens) ?? 0;
  // 没有窗口信息就没法算比例，和 Web 端一样直接不显示
  if (contextWindow <= 0) return null;

  const percent = resolveContextPercent(record);
  const usage: ContextUsage = {
    contextWindow,
    contextTokens,
    contextPercent: percent,
    contextOverflow: record.contextOverflow === true || percent >= 100,
    isCompacting: record.isCompacting === true,
  };

  const inputTokens = num(record.inputTokens);
  if (inputTokens !== undefined) usage.inputTokens = inputTokens;
  const outputTokens = num(record.outputTokens);
  if (outputTokens !== undefined) usage.outputTokens = outputTokens;
  const cost = num(record.cost);
  if (cost !== undefined) usage.cost = cost;
  const cacheHitPercent = num(record.cacheHitPercent);
  if (cacheHitPercent !== undefined) usage.cacheHitPercent = cacheHitPercent;
  const messageTokens = num(record.contextMessageTokens);
  if (messageTokens !== undefined) usage.contextMessageTokens = messageTokens;
  const systemTokens = num(record.contextSystemTokens);
  if (systemTokens !== undefined) usage.contextSystemTokens = systemTokens;
  const toolsTokens = num(record.contextToolsTokens);
  if (toolsTokens !== undefined) usage.contextToolsTokens = toolsTokens;
  if (typeof record.provider === "string") usage.provider = record.provider;

  return usage;
}

/** runtime 命令的返回可能是 { value } 或 { value: { value } }，统一拆开。 */
export function unwrapRuntimeValue(raw: unknown): unknown {
  if (!raw || typeof raw !== "object") return raw;
  const first = (raw as { value?: unknown }).value;
  if (first && typeof first === "object" && "value" in first) {
    return (first as { value?: unknown }).value;
  }
  return first;
}

/** token 数的短格式：999 / 12.3K / 1.2M，与 Web 端 formatTokens 观感一致。 */
export function formatTokens(value: number): string {
  if (!Number.isFinite(value) || value < 0) return "0";
  if (value < 1_000) return String(Math.round(value));
  if (value < 1_000_000) {
    const k = value / 1_000;
    return `${k >= 100 ? Math.round(k) : k.toFixed(1).replace(/\.0$/, "")}K`;
  }
  const m = value / 1_000_000;
  return `${m >= 100 ? Math.round(m) : m.toFixed(1).replace(/\.0$/, "")}M`;
}

/** 百分比文案：整数就省掉小数位。 */
export function formatPercent(value: number): string {
  const clamped = Math.min(100, Math.max(0, value));
  return clamped >= 10 || clamped === 0 ? String(Math.round(clamped)) : clamped.toFixed(1);
}

/** 圆环用的小数百分比（保留一位，避免 0.4% 显示成 0%）。 */
export function ringPercent(usage: ContextUsage): number {
  return Math.min(100, Math.max(0, usage.contextPercent));
}

/** 按钮文字：正常显示百分比，压缩中显示省略号。 */
export function contextChipLabel(usage: ContextUsage): string {
  if (usage.isCompacting) return "压缩中";
  return `${formatPercent(usage.contextPercent)}%`;
}

/** 按钮 title / aria-label：和 Web 端 tooltip 同构。 */
export function contextChipTitle(usage: ContextUsage): string {
  const base = `上下文用量 ${formatPercent(usage.contextPercent)}% · ${formatTokens(
    usage.contextTokens,
  )} / ${formatTokens(usage.contextWindow)}`;
  return usage.contextOverflow ? `${base} · 已超出` : base;
}

/** 面板里的明细行（顺序与 Web 端一致）。 */
export interface ContextRow {
  key: string;
  label: string;
  value: string;
}

export function contextRows(usage: ContextUsage): ContextRow[] {
  const rows: ContextRow[] = [
    {
      key: "context",
      label: "上下文用量",
      value: `${formatTokens(usage.contextTokens)} / ${formatTokens(usage.contextWindow)}`,
    },
  ];
  if (usage.inputTokens !== undefined) {
    rows.push({ key: "input", label: "输入 token", value: formatTokens(usage.inputTokens) });
  }
  if (usage.outputTokens !== undefined) {
    rows.push({ key: "output", label: "输出 token", value: formatTokens(usage.outputTokens) });
  }
  if (usage.contextMessageTokens !== undefined) {
    rows.push({ key: "message", label: "其中对话", value: formatTokens(usage.contextMessageTokens) });
  }
  if (usage.contextSystemTokens !== undefined) {
    rows.push({ key: "system", label: "其中系统", value: formatTokens(usage.contextSystemTokens) });
  }
  if (usage.contextToolsTokens !== undefined) {
    rows.push({ key: "tools", label: "其中工具", value: formatTokens(usage.contextToolsTokens) });
  }
  if (usage.cacheHitPercent !== undefined) {
    rows.push({ key: "cache", label: "缓存命中", value: `${formatPercent(usage.cacheHitPercent)}%` });
  }
  if (usage.cost !== undefined) {
    rows.push({ key: "cost", label: "累计花费", value: `$${usage.cost.toFixed(4)}` });
  }
  return rows;
}

/**
 * 轮询间隔，照搬 Web 端：跑动中且事件流连着 3s，事件流断了 1s（更急着补），空闲 10s。
 */
export function contextPollIntervalMs(streaming: boolean, eventsConnected: boolean): number {
  if (!streaming) return 10_000;
  return eventsConnected ? 3_000 : 1_000;
}
