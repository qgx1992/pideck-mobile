/**
 * 子代理活动条（贴在输入框上方，对齐 Web 端 WebSessionStrips 的子代理部分）。
 *
 * 数据来自 pi-subagents 插件：/api/sessions/:id/subagents 返回
 * { id, type, description, status, result?, error?, toolUses?, tokens? }。
 * 与任务清单一样只做展示：子代理由 agent 派生，用户点它没有意义。
 */

export type SubagentStatus =
  | "queued"
  | "running"
  | "completed"
  | "steered"
  | "aborted"
  | "stopped"
  | "error";

export interface SubagentView {
  id: string;
  type: string;
  description: string;
  status: SubagentStatus;
  statusLabel: string;
  /** 还在跑（queued / running）：决定条带是否呼吸 */
  active: boolean;
  failed: boolean;
  /** 展开后显示的正文：结果 / 错误 / 用量摘要 */
  detail: string;
  toolUses?: number;
  tokens?: number;
}

const STATUS_LABELS: Record<SubagentStatus, string> = {
  queued: "排队中",
  running: "运行中",
  completed: "完成",
  steered: "已插话",
  aborted: "已中止",
  stopped: "已停止",
  error: "失败",
};

function str(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function num(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function normalizeStatus(value: unknown): SubagentStatus {
  const text = str(value).toLowerCase();
  if (text in STATUS_LABELS) return text as SubagentStatus;
  return "completed";
}

function detailOf(record: Record<string, unknown>, status: SubagentStatus): string {
  const error = str(record.error).trim();
  if (error) return error;
  const result = str(record.result).trim();
  if (result) return result.length > 2000 ? `${result.slice(0, 2000)}…` : result;
  const parts: string[] = [];
  const toolUses = num(record.toolUses);
  const tokens = num(record.tokens);
  if (toolUses !== undefined) parts.push(`${toolUses} 次工具调用`);
  if (tokens !== undefined) parts.push(`${tokens} tokens`);
  if (parts.length > 0) return parts.join(" · ");
  return STATUS_LABELS[status];
}

/** 宽松解析：字段缺失/类型不对都不该让整条炸掉。 */
export function normalizeSubagents(raw: unknown): SubagentView[] {
  if (!Array.isArray(raw)) return [];
  const out: SubagentView[] = [];
  raw.forEach((item, index) => {
    if (!item || typeof item !== "object") return;
    const record = item as Record<string, unknown>;
    const status = normalizeStatus(record.status);
    const id = str(record.id).trim() || `subagent-${index}`;
    out.push({
      id,
      type: str(record.type).trim() || "子代理",
      description: str(record.description).trim(),
      status,
      statusLabel: STATUS_LABELS[status],
      active: status === "queued" || status === "running",
      failed: status === "error",
      detail: detailOf(record, status),
      toolUses: num(record.toolUses),
      tokens: num(record.tokens),
    });
  });
  return out;
}

export interface SubagentSummary {
  total: number;
  active: number;
  failed: number;
  /** 头部文案：「3 个子代理 · 1 运行中」 */
  title: string;
}

export function summarizeSubagents(list: readonly SubagentView[]): SubagentSummary {
  let active = 0;
  let failed = 0;
  for (const item of list) {
    if (item.active) active += 1;
    if (item.failed) failed += 1;
  }
  const parts = [`${list.length} 个子代理`];
  if (active > 0) parts.push(`${active} 运行中`);
  if (failed > 0) parts.push(`${failed} 失败`);
  return { total: list.length, active, failed, title: parts.join(" · ") };
}

export function shouldShowSubagents(list: readonly SubagentView[]): boolean {
  return list.length > 0;
}
