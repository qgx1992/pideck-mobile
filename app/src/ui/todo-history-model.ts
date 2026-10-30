/**
 * 会话待办清单的「历史」。
 *
 * 服务端只给**当前**那一份清单（`GET /api/sessions/:id/todo` 返回最新的 planId），
 * 换计划后旧的就被覆盖了。所以历史只能客户端自己攒：每次轮询到新快照就按 planId 归档，
 * 同一个 planId 的就地更新（进度会变），不同 planId 的追加。
 *
 * 归档时机有两处：
 *   1. 轮询看到清单变化（新计划出现、进度推进）
 *   2. 用户发出新提问 —— 这一轮结束，当前清单归入历史并从输入框上方撤掉
 */

import type { TodoItem, TodoView } from "./todo-model.ts";

export interface TodoHistoryEntry {
  planId: string;
  /** 首次观测到这个计划的时间 */
  firstSeenAt: number;
  /** 最后一次更新的时间（排序用） */
  updatedAt: number;
  items: TodoItem[];
  done: number;
  total: number;
  /** 全部完成 */
  finished: boolean;
}

/** 一个会话最多保留多少份历史清单（超了就丢最旧的）。 */
export const TODO_HISTORY_LIMIT = 30;

/** 快照的稳定标识：服务端没给 planId 时用条目文本 + id 兜底，避免所有清单都算同一份。 */
export function planKeyOf(view: TodoView): string {
  const planId = (view.planId ?? "").trim();
  if (planId) return planId;
  return view.items.map((item) => `${item.id}:${item.text}`).join("|").slice(0, 200) || "unknown";
}

export function entryFromView(view: TodoView, now: number): TodoHistoryEntry {
  return {
    planId: planKeyOf(view),
    firstSeenAt: now,
    updatedAt: now,
    items: view.items.map((item) => ({ ...item })),
    done: view.done,
    total: view.total,
    finished: !view.unfinished,
  };
}

/**
 * 合并一份快照进历史。
 *
 * 同一 planId → 就地更新（保留 firstSeenAt，进度取新的）；
 * 新 planId → 追加到最前面。返回值已按 updatedAt 倒序，并裁到上限。
 */
export function mergeTodoHistory(
  history: readonly TodoHistoryEntry[],
  view: TodoView,
  now: number,
  limit: number = TODO_HISTORY_LIMIT,
): TodoHistoryEntry[] {
  const key = planKeyOf(view);
  const existing = history.find((entry) => entry.planId === key);
  const next: TodoHistoryEntry = existing
    ? {
        ...entryFromView(view, now),
        firstSeenAt: existing.firstSeenAt,
      }
    : entryFromView(view, now);

  const rest = history.filter((entry) => entry.planId !== key);
  return [next, ...rest]
    .sort((left, right) => right.updatedAt - left.updatedAt)
    .slice(0, limit);
}

/** 宽松解析持久化的历史（跨版本、被手改过都要能读）。 */
export function parseTodoHistory(raw: unknown): TodoHistoryEntry[] {
  if (!Array.isArray(raw)) return [];
  const out: TodoHistoryEntry[] = [];
  for (const item of raw) {
    if (!item || typeof item !== "object") continue;
    const record = item as Record<string, unknown>;
    const planId = typeof record.planId === "string" ? record.planId : "";
    if (!planId) continue;
    const items: TodoItem[] = [];
    for (const entry of Array.isArray(record.items) ? record.items : []) {
      if (!entry || typeof entry !== "object") continue;
      const row = entry as Record<string, unknown>;
      const text = typeof row.text === "string" ? row.text.trim() : "";
      if (!text) continue;
      const status = row.status === "completed" || row.status === "in_progress" ? row.status : "pending";
      items.push({ id: String(row.id ?? items.length), text, status });
    }
    if (items.length === 0) continue;
    const done = items.filter((row) => row.status === "completed").length;
    const firstSeenAt = typeof record.firstSeenAt === "number" ? record.firstSeenAt : 0;
    const updatedAt = typeof record.updatedAt === "number" ? record.updatedAt : firstSeenAt;
    out.push({
      planId,
      firstSeenAt,
      updatedAt,
      items,
      done,
      total: items.length,
      finished: done === items.length,
    });
  }
  return out.sort((left, right) => right.updatedAt - left.updatedAt);
}

/* ------------------------------------------------------------------ */
/* 显示规则                                                            */
/* ------------------------------------------------------------------ */

/**
 * 输入框上方的清单条是否显示。
 *
 * 规则（对应用户反馈）：
 *   - 没有清单 → 不显示
 *   - **全部完成 → 不显示**（以前会一直挂着，看着像还没完事）
 *   - 还有未完成 → 显示
 */
export function shouldShowTodoStrip(view: TodoView | null): boolean {
  return view !== null && view.unfinished;
}

/** 历史条目头部文案：`4/5 · 完成`。 */
export function historyProgressLabel(entry: TodoHistoryEntry): string {
  return `${entry.done}/${entry.total}`;
}

/** 历史条目的时间文案（今天只给时分，其它给月日）。 */
export function historyTimeLabel(entry: TodoHistoryEntry): string {
  const at = entry.updatedAt || entry.firstSeenAt;
  if (!at) return "";
  const date = new Date(at);
  const now = new Date();
  const sameDay = date.toDateString() === now.toDateString();
  const pad = (value: number): string => String(value).padStart(2, "0");
  if (sameDay) return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  return `${date.getMonth() + 1}/${date.getDate()} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
