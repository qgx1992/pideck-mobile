/**
 * 会话任务清单（todo）。
 *
 * 数据来自 `GET /api/sessions/:id/todo`，实测形状（PiDeck 0.9.7）：
 *   { planId: 8, todos: [{ id: 47, text: "…", status: "completed" }, …] }
 * status 只有 completed / pending / in_progress 三种，其它一律当 pending。
 *
 * Web 端是每 5s 轮询一次（页面隐藏时跳过），这里沿用同样的节奏。
 */

export type TodoStatus = "pending" | "in_progress" | "completed";

export interface TodoItem {
  id: string;
  text: string;
  status: TodoStatus;
}

export interface TodoView {
  planId: string;
  items: TodoItem[];
  total: number;
  done: number;
  /** 正在进行中的条数 */
  active: number;
  /** 还有没做完的（用于动画标识） */
  unfinished: boolean;
}

function normalizeStatus(value: unknown): TodoStatus {
  const text = typeof value === "string" ? value.toLowerCase() : "";
  if (text === "completed" || text === "done") return "completed";
  if (text === "in_progress" || text === "in-progress" || text === "active" || text === "running") {
    return "in_progress";
  }
  return "pending";
}

/**
 * 解析 /todo 的返回。空清单返回 null（不显示条带）。
 * raw 可能是 `{planId, todos}`，也可能被包了一层 `{todo: …}`。
 */
export function parseSessionTodo(raw: unknown): TodoView | null {
  if (!raw || typeof raw !== "object") return null;
  const record = raw as Record<string, unknown>;
  const source = (record.todo ?? record) as Record<string, unknown>;

  const rawItems = Array.isArray(source.todos) ? source.todos : [];
  const items: TodoItem[] = [];
  for (const entry of rawItems) {
    if (!entry || typeof entry !== "object") continue;
    const item = entry as Record<string, unknown>;
    const text = typeof item.text === "string" ? item.text.trim() : "";
    if (!text) continue;
    items.push({
      id: String(item.id ?? items.length),
      text,
      status: normalizeStatus(item.status),
    });
  }
  if (items.length === 0) return null;

  const done = items.filter((item) => item.status === "completed").length;
  const active = items.filter((item) => item.status === "in_progress").length;
  return {
    planId: String(source.planId ?? ""),
    items,
    total: items.length,
    done,
    active,
    unfinished: done < items.length,
  };
}

/** 头部进度文案：`3/6`。 */
export function todoProgressLabel(view: TodoView): string {
  return `${view.done}/${view.total}`;
}

/**
 * 清单是否该自动展开。
 * 有「进行中」的条目就展开（正在干活时最需要看到进度），否则保持用户上次的选择。
 */
export function shouldAutoExpand(view: TodoView | null): boolean {
  return view !== null && view.active > 0;
}

/** 头部 title 提示。 */
export function todoTitle(view: TodoView): string {
  const parts = [`任务清单 ${todoProgressLabel(view)}`];
  if (view.active > 0) parts.push(`${view.active} 项进行中`);
  if (!view.unfinished) parts.push("已全部完成");
  return parts.join(" · ");
}
