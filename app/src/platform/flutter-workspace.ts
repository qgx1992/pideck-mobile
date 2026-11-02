/**
 * 右侧「工作区」抽屉的数据快照。
 *
 * 和侧栏/聊天页一样：**规则留在 Web 侧，Dart 只画**。Web 版这块界面
 * （`main.ts` 的 `renderWorkspacePanel()`）读的是 Git 状态、目录列表、待办历史，
 * 都是异步的；Flutter 侧不该为了画一棵目录树再学一遍 pi 的接口，
 * 所以这里把「当前该显示什么」压成一份纯数据，Dart 收到就画、点了就回传动作。
 */

export type WorkspaceTab = "git" | "files" | "todos";

export interface WorkspaceChangeRow {
  /** 状态字母：M / A / D / R / ?? …（沿用 git 原样） */
  letter: string;
  /** 相对项目根的路径 */
  path: string;
  /** 分组标题：已暂存 / 工作区 / 未跟踪 / 合并冲突 */
  group: string;
}

export interface WorkspaceCommitRow {
  sha: string;
  subject: string;
  date: string;
}

export interface WorkspaceEntryRow {
  name: string;
  /** 相对项目根的路径（引用到输入框时用这个） */
  path: string;
  dir: boolean;
}

export interface WorkspaceTodoRow {
  text: string;
  /** pending / in_progress / completed */
  status: string;
  /** 最后一次见到的轮次或时间，原样显示 */
  at: string;
}

export interface WorkspaceSnapshot {
  open: boolean;
  tab: WorkspaceTab;
  /** 项目名（面包屑第一段也用它） */
  title: string;
  loading: boolean;
  /** 空态文案；非空时 Dart 画一行灰字 */
  empty: string;
  branch: string;
  changedTotal: number;
  changes: WorkspaceChangeRow[];
  commits: WorkspaceCommitRow[];
  /** 面包屑：`["项目名", "src", "ui"]` */
  crumbs: string[];
  entries: WorkspaceEntryRow[];
  preview: { path: string; content: string; truncated: boolean } | null;
  todos: WorkspaceTodoRow[];
}

/** 预览超过这个长度就截断（手机上一屏几千行没人看，过桥也慢）。 */
export const PREVIEW_MAX_CHARS = 8000;

export function emptyWorkspaceSnapshot(): WorkspaceSnapshot {
  return {
    open: false,
    tab: "git",
    title: "",
    loading: false,
    empty: "",
    branch: "",
    changedTotal: 0,
    changes: [],
    commits: [],
    crumbs: [],
    entries: [],
    preview: null,
    todos: [],
  };
}

/** 目录在前、其余按名称排（与 Web 版 `renderFilesTab()` 的排序一致）。 */
export function sortWorkspaceEntries(rows: readonly WorkspaceEntryRow[]): WorkspaceEntryRow[] {
  return [...rows].sort((a, b) => {
    if (a.dir !== b.dir) return a.dir ? -1 : 1;
    return a.name.localeCompare(b.name, "zh-Hans-CN", { numeric: true });
  });
}

/** 预览截断：超长就切掉尾巴并标记，避免把 512KB 的文件整份推过桥。 */
export function truncatePreview(content: string, max = PREVIEW_MAX_CHARS): { content: string; truncated: boolean } {
  if (content.length <= max) return { content, truncated: false };
  return { content: content.slice(0, max), truncated: true };
}

/** 面包屑 → 目录路径（第一段是项目名，不是目录）。 */
export function crumbsToPath(crumbs: readonly string[]): string {
  return crumbs.slice(1).join("/");
}

/** 文件路径 → 末尾一段（服务端偶尔会在 name 里带路径，进目录时只认最后一段）。 */
export function lastSegment(path: string): string {
  const parts = path.split(/[\\/]/).filter((part) => part.length > 0);
  return parts.length > 0 ? parts[parts.length - 1]! : path;
}
