/**
 * 供应商用量/余额的展示模型（/api/usage/:provider）。
 *
 * 服务端把各家差异很大的接口归成三类形态（见 PiDeck 的 ProviderUsageResult）：
 *   periods  三档占用百分比（滚动 / 周 / 月）
 *   balance  剩余额度（金额 + 币种）
 *   credits  额度点数（总额 / 已用 / 剩余）
 * 「未开启用量查询」「供应商没端点」「探针失败」统一是 200 + success:false，
 * 属于正常态而不是故障，所以这里只产出「可用 / 不可用 + 原因」，不抛错。
 */

export interface UsageLine {
  label: string;
  value: string;
}

export interface UsageView {
  /** 有可展示的数据 */
  available: boolean;
  /** 供应商名（面板标题用） */
  provider: string;
  lines: UsageLine[];
  /** 不可用时的说明 */
  hint?: string;
  /** 结果自带的时间戳（ms），用于显示数据新旧 */
  at?: number;
}

function str(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function num(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function money(value: number, currency?: string): string {
  const rounded = Math.abs(value) >= 100 ? Math.round(value) : Math.round(value * 100) / 100;
  return currency ? `${rounded} ${currency}` : String(rounded);
}

function percent(value: number): string {
  return `${Math.round(value * 10) / 10}%`;
}

/** ISO 时间 → 本地 MM-DD HH:mm；解析不了就原样返回。 */
export function formatResetAt(value: string): string {
  const time = Date.parse(value);
  if (Number.isNaN(time)) return value;
  const date = new Date(time);
  const pad = (n: number): string => String(n).padStart(2, "0");
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

const PERIOD_LABELS: Array<[string, string]> = [
  ["rolling", "滚动窗口"],
  ["weekly", "本周"],
  ["monthly", "本月"],
];

export function buildUsageView(raw: unknown, fallbackProvider = ""): UsageView {
  const base: UsageView = { available: false, provider: fallbackProvider, lines: [] };
  if (!raw || typeof raw !== "object") {
    return { ...base, hint: "没有返回数据" };
  }
  const record = raw as Record<string, unknown>;
  const provider = str(record.provider).trim() || fallbackProvider;
  const at = num(record.at);

  if (record.success !== true) {
    if (record.disabled === true) {
      return {
        ...base,
        provider,
        at,
        hint: "桌面端没有开启这个供应商的用量查询（设置 → 用量查询里打开）",
      };
    }
    const error = str(record.error).trim();
    return { ...base, provider, at, hint: error || "查不到用量（供应商可能没有余额接口）" };
  }

  const lines: UsageLine[] = [];

  const periods = record.periods;
  if (periods && typeof periods === "object") {
    for (const [key, label] of PERIOD_LABELS) {
      const period = (periods as Record<string, unknown>)[key];
      if (!period || typeof period !== "object") continue;
      const value = num((period as Record<string, unknown>).percent);
      const resetsAt = str((period as Record<string, unknown>).resetsAt);
      const status = str((period as Record<string, unknown>).status);
      const parts = [value !== undefined ? percent(value) : "", status].filter(Boolean);
      if (resetsAt) parts.push(`重置于 ${formatResetAt(resetsAt)}`);
      if (parts.length > 0) lines.push({ label, value: parts.join(" · ") });
    }
  }

  const balance = record.balance;
  if (balance && typeof balance === "object") {
    const value = num((balance as Record<string, unknown>).value);
    const currency = str((balance as Record<string, unknown>).currency);
    if (value !== undefined) lines.push({ label: "余额", value: money(value, currency) });
  }

  const credits = record.credits;
  if (credits && typeof credits === "object") {
    const total = num((credits as Record<string, unknown>).total);
    const used = num((credits as Record<string, unknown>).used);
    const remaining =
      num((credits as Record<string, unknown>).remaining) ??
      (total !== undefined && used !== undefined ? total - used : undefined);
    if (remaining !== undefined) lines.push({ label: "剩余点数", value: String(remaining) });
    if (total !== undefined) lines.push({ label: "总额", value: String(total) });
    if (used !== undefined) lines.push({ label: "已用", value: String(used) });
  }

  const booster = record.booster;
  if (booster && typeof booster === "object") {
    const value = num((booster as Record<string, unknown>).balance);
    const currency = str((booster as Record<string, unknown>).currency);
    if (value !== undefined) lines.push({ label: "附加额度", value: money(value, currency) });
  }

  if (lines.length === 0) {
    const rawText = str(record.raw).trim();
    return {
      ...base,
      provider,
      at,
      hint: rawText ? `返回了数据但没识别出用量格式：${rawText.slice(0, 160)}` : "返回里没有可展示的额度字段",
    };
  }

  return { available: true, provider, lines, at };
}

/** 节流：默认 5 分钟一轮，服务端给了 intervalMinutes 就以它为准。 */
export const DEFAULT_USAGE_INTERVAL_MINUTES = 5;
export const USAGE_REFRESH_COOLDOWN_MS = 30_000;

export function usageIntervalMs(raw: unknown): number {
  const record = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const minutes = num(record.intervalMinutes);
  const safe = minutes !== undefined && minutes > 0 ? minutes : DEFAULT_USAGE_INTERVAL_MINUTES;
  return Math.max(60_000, Math.round(safe * 60_000));
}
