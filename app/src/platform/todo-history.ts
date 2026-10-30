/**
 * 待办清单历史的本地持久化。
 *
 * 服务端不保留历史计划，所以这份数据只在本机：按「设备 + 会话」存，
 * 多设备时两台机器上的同 id 会话不会串台。
 */

import { parseTodoHistory } from "../ui/todo-history-model.ts";
import type { TodoHistoryEntry } from "../ui/todo-history-model.ts";

const KEY_PREFIX = ["pideck", "todo", "history", "v1"].join("-");

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
