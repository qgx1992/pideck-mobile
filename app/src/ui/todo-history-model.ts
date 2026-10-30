/**
 * 会话待办清单的「历史」。
 *
 * 服务端只给**当前**那一份清单（`GET /api/sessions/:id/todo` 返回最新的 planId），
 * 换计划后旧的就被覆盖。历史只能客户端自己攒，但要解决三个实际问题：
 *
 *   1. **时间不能跳**：轮询每 5s 都会拿到同一份清单，进度可能没变。
 *      所以「触发时间」固定在 firstSeenAt，只有内容真的变了才更新 lastChangedAt。
 *   2. **一轮一条，不要反复合并**：同一轮里进度推进是「更新同一条」；
 *      用户发出下一轮（收口 seal）后，即使服务端复用同一个 planId，也要新增一条。
 *   3. **已完成也要留到下一轮**：全部完成不等于「结束」，用户还看着它，
 *      真正的结束点是下一次发送。
 */

import type { TodoItem, TodoView } from "./todo-model.ts";

export interface TodoHistoryEntry {
  /** 记录自身 id（planId + 首次时间），同 planId 的多轮也能区分 */
  id: string;
  planId: string;
  /** 这一份清单第一次被看到的时间（展示用，不随轮询变化） */
  firstSeenAt: number;
  /** 内容最后一次真正变化的时间（进度推进） */
  lastChangedAt: number;
  /** 已收口：下一轮已经开始，不再接受进度更新 */
  sealed: boolean;
  items: TodoItem[];
  done: number;
  total: number;
  /** 全部完成 */
  finished: boolean;
}

/** 一个会话最多保留多少份历史清单（超了就丢最旧的）。 */
export const TODO_HISTORY_LIMIT = 30;

/** 快照的稳定标识：服务端没给 planId 时用条目内容兜底。 */
export function planKeyOf(view: TodoView): string {
  const planId = (view.planId ?? "").trim();
  if (planId) return planId;
  return view.items.map((item) => `${item.id}:${item.text}`).join("|").slice(0, 200) || "unknown";
}

function entryIdOf(planId: string, firstSeenAt: number): string {
  return `${planId}@${firstSeenAt}`;
}

export function entryFromView(view: TodoView, now: number, sealed = false): TodoHistoryEntry {
  const planId = planKeyOf(view);
  return {
    id: entryIdOf(planId, now),
    planId,
    firstSeenAt: now,
    lastChangedAt: now,
    sealed,
    items: view.items.map((item) => ({ ...item })),
    done: view.done,
    total: view.total,
    finished: !view.unfinished,
  };
}

function sortAndCap(history: readonly TodoHistoryEntry[], limit: number): TodoHistoryEntry[] {
  return [...history].sort((left, right) => right.firstSeenAt - left.firstSeenAt).slice(0, limit);
}

/**
 * 合并一份轮询快照。
 *
 * 找到「同 planId 且未收口」的那一条就地更新进度；时间只在内容变化时才动。
 * 找不到（新计划，或上一轮的同名计划已收口）就新建一条。
 */
export function mergeTodoHistory(
  history: readonly TodoHistoryEntry[],
  view: TodoView,
  now: number,
  limit: number = TODO_HISTORY_LIMIT,
): TodoHistoryEntry[] {
  const planId = planKeyOf(view);
  const index = history.findIndex((entry) => entry.planId === planId && !entry.sealed);

  if (index < 0) {
    return sortAndCap([entryFromView(view, now), ...history], limit);
  }

  const existing = history[index]!;
  const changed =
    existing.done !== view.done || existing.total !== view.total || !sameItems(existing.items, view.items);

  const next: TodoHistoryEntry = {
    ...existing,
    items: view.items.map((item) => ({ ...item })),
    done: view.done,
    total: view.total,
    finished: !view.unfinished,
    // 时间只跟着「内容真的变了」走，避免每 5s 抖动一次
    lastChangedAt: changed ? now : existing.lastChangedAt,
  };

  const copy = [...history];
  copy[index] = next;
  return sortAndCap(copy, limit);
}

function sameItems(left: readonly TodoItem[], right: readonly TodoItem[]): boolean {
  if (left.length !== right.length) return false;
  return left.every(
    (item, index) =>
      item.id === right[index]?.id && item.status === right[index]?.status && item.text === right[index]?.text,
  );
}

/**
 * 收口：用户发出下一轮时调用。
 *
 * 把当前这一份标成 sealed（不再接受进度更新），下一轮再触发清单就会**新增一条**，
 * 这样多次触发在历史里能看到多条记录。
 */
export function sealTodoHistory(
  history: readonly TodoHistoryEntry[],
  view: TodoView | null,
  now: number,
  limit: number = TODO_HISTORY_LIMIT,
): TodoHistoryEntry[] {
  if (!view) return [...history];
  const planId = planKeyOf(view);
  const index = history.findIndex((entry) => entry.planId === planId && !entry.sealed);

  if (index < 0) {
    // 只在内存里见过但还没落历史（比如刚触发就发送）→ 补一条收口的
    return sortAndCap([entryFromView(view, now, true), ...history], limit);
  }

  const copy = [...history];
  copy[index] = { ...copy[index]!, sealed: true, lastChangedAt: now };
  return sortAndCap(copy, limit);
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
    // 旧版本（v1）只存了 updatedAt，读的时候要能接上，否则老记录时间会归零
    const lastChangedAt =
      typeof record.lastChangedAt === "number"
        ? record.lastChangedAt
        : typeof record.updatedAt === "number"
          ? record.updatedAt
          : firstSeenAt;
    out.push({
      id: typeof record.id === "string" && record.id ? record.id : entryIdOf(planId, firstSeenAt),
      planId,
      firstSeenAt,
      lastChangedAt,
      sealed: record.sealed === true,
      items,
      done,
      total: items.length,
      finished: done === items.length,
    });
  }
  return out.sort((left, right) => right.firstSeenAt - left.firstSeenAt);
}

/* ------------------------------------------------------------------ */
/* 显示规则                                                            */
/* ------------------------------------------------------------------ */

/**
 * 输入框上方的清单条是否显示。
 *
 * 规则（用户明确要求）：**全部完成也继续显示**，直到发出下一轮才撤掉。
 * 「撤掉」由 main.ts 的 dismissed 集合负责，这里只判断有没有清单。
 */
export function shouldShowTodoStrip(view: TodoView | null): boolean {
  return view !== null;
}

/** 历史条目头部文案：`4/5`。 */
export function historyProgressLabel(entry: TodoHistoryEntry): string {
  return `${entry.done}/${entry.total}`;
}

/** 历史条目的触发时间（固定不变，不随轮询跳动）。 */
export function historyTimeLabel(entry: TodoHistoryEntry): string {
  const at = entry.firstSeenAt;
  if (!at) return "";
  const date = new Date(at);
  const now = new Date();
  const sameDay = date.toDateString() === now.toDateString();
  const pad = (value: number): string => String(value).padStart(2, "0");
  if (sameDay) return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  return `${date.getMonth() + 1}/${date.getDate()} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 头部状态文案：进行中 / 已完成 / 已归档（下一轮已开始）。 */
export function historyStatusLabel(entry: TodoHistoryEntry): string {
  if (!entry.finished) return "进行中";
  return entry.sealed ? "已完成" : "已完成（当前轮）";
}
