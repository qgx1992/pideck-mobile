/**
 * 待办清单历史的本地持久化。
 *
 * 服务端不保留历史计划，所以这份数据只在本机：按「设备 + 会话」存，
 * 多设备时两台机器上的同 id 会话不会串台。
 */

import { parseTodoHistory } from "../ui/todo-history-model.ts";
import type { TodoHistoryEntry, TodoIdentity } from "../ui/todo-history-model.ts";

const KEY_PREFIX = ["pideck", "todo", "history", "v1"].join("-");
const DISMISSED_PREFIX = ["pideck", "todo", "dismissed", "v1"].join("-");

function storageKey(scope: string): string {
  return `${KEY_PREFIX}-${scope}`;
}

export function loadTodoHistory(scope: string): TodoHistoryEntry[] {
  try {
    const raw = localStorage.getItem(storageKey(scope));
    return raw ? parseTodoHistory(JSON.parse(raw)) : [];
  } catch {
    return [];
  }
}

export function saveTodoHistory(scope: string, history: readonly TodoHistoryEntry[]): void {
  try {
    if (history.length === 0) localStorage.removeItem(storageKey(scope));
    else localStorage.setItem(storageKey(scope), JSON.stringify(history));
  } catch {
    /* 隐私模式写不进去，忽略 */
  }
}

/** 清掉某个会话的历史（删除会话时顺手调用）。 */
export function clearTodoHistory(scope: string): void {
  try {
    localStorage.removeItem(storageKey(scope));
  } catch {
    /* ignore */
  }
}

function dismissedKey(scope: string): string {
  return `${DISMISSED_PREFIX}-${scope}`;
}

/**
 * 读这个会话「已收口」的那份清单身份。
 *
 * 必须落盘：以前只放在内存 Map 里，重启 App 就丢了，于是
 * ①重新打开会话时 5 秒轮询把清掉的清单又写回「待办」；
 * ②发出新一轮时服务端仍返回那份旧清单，条带又冒到输入框上方。
 */
export function loadDismissedTodo(scope: string): TodoIdentity | null {
  try {
    const raw = localStorage.getItem(dismissedKey(scope));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
    const record = parsed as Record<string, unknown>;
    const planId = typeof record.planId === "string" ? record.planId : "";
    const signature = typeof record.signature === "string" ? record.signature : "";
    if (!planId && !signature) return null;
    return { planId, signature };
  } catch {
    return null;
  }
}

export function saveDismissedTodo(scope: string, value: TodoIdentity | null): void {
  try {
    if (!value) localStorage.removeItem(dismissedKey(scope));
    else localStorage.setItem(dismissedKey(scope), JSON.stringify({ planId: value.planId, signature: value.signature }));
  } catch {
    /* 隐私模式写不进去，忽略 */
  }
}

/** 清掉某个会话的历史与屏蔽（删除会话时调用）。 */
export function clearDismissedTodo(scope: string): void {
  saveDismissedTodo(scope, null);
}
