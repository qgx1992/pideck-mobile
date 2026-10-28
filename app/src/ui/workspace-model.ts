/**
 * 右侧「工作区」抽屉的视图模型。
 *
 * 对齐 PiDeck Web 端的 workspaceDrawer：两个 Tab —— Git（默认）与 文件。
 *
 * 已核实的接口形状：
 *   GET /api/git/status → { repo, branch: { current, branches[] },
 *                           groups: { merge[], index[], workingTree[], untracked[] } }
 *     每个条目：{ path（绝对路径）, status（数字）, letter（字母） }
 *   GET /api/git/log    → { commits: [{ hash, shortHash, message, authorName, authorDate, refNames, graph }] }
 *   GET /api/files      → { nodes: [{ name, relativePath, type, hasChildren }] }
 *   GET /api/file-content → { content, size }
 */

export interface GitFileEntry {
  /** 绝对路径，diff / 打开时用 */
  path: string;
  /** 相对项目根的路径，展示用 */
  relativePath: string;
  /** 单字母状态：M / A / D / U / R … */
  letter: string;
  /** 所属分组 */
  group: GitGroupKey;
}

export type GitGroupKey = "merge" | "index" | "workingTree" | "untracked";

export interface GitCommit {
  hash: string;
  shortHash: string;
  message: string;
  authorName: string;
  authorDate: string;
}

export interface GitStatusView {
  isRepo: boolean;
  branch: string;
  branches: string[];
  groups: Array<{ key: GitGroupKey; title: string; files: GitFileEntry[] }>;
  total: number;
}

const GROUP_TITLE: Record<GitGroupKey, string> = {
  merge: "冲突中",
  index: "已暂存",
  workingTree: "已修改",
  untracked: "未跟踪",
};

const GROUP_ORDER: GitGroupKey[] = ["merge", "index", "workingTree", "untracked"];

/** 把绝对路径压成相对项目根的路径，兼容 Windows 反斜杠。 */
export function toRelativePath(absolute: string, projectPath: string): string {
  const normalized = String(absolute ?? "").replace(/\\/g, "/");
  const root = String(projectPath ?? "").replace(/\\/g, "/").replace(/\/+$/, "");
  if (!root) return normalized;
  if (normalized.toLowerCase().startsWith(`${root.toLowerCase()}/`)) {
    return normalized.slice(root.length + 1);
  }
  // 不同盘符或已相对：只保留最后两段，避免整行被路径撑爆
  const segments = normalized.split("/").filter(Boolean);
  return segments.slice(-2).join("/");
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

export function parseGitStatus(raw: unknown, projectPath: string): GitStatusView {
  const record = (raw ?? {}) as Record<string, unknown>;
  if (record.repo !== true) {
    return { isRepo: false, branch: "", branches: [], groups: [], total: 0 };
  }

  const branchInfo = (record.branch ?? {}) as Record<string, unknown>;
  const groupsRaw = (record.groups ?? {}) as Record<string, unknown>;

  const groups: GitStatusView["groups"] = [];
  let total = 0;

  for (const key of GROUP_ORDER) {
    const files = asArray(groupsRaw[key])
      .map((item): GitFileEntry | null => {
        if (!item || typeof item !== "object") return null;
        const entry = item as Record<string, unknown>;
        const path = typeof entry.path === "string" ? entry.path : "";
        if (!path) return null;
        return {
          path,
          relativePath: toRelativePath(path, projectPath),
          letter: typeof entry.letter === "string" ? entry.letter : "?",
          group: key,
        };
      })
      .filter((item): item is GitFileEntry => item !== null);

    if (files.length === 0) continue;
    total += files.length;
    groups.push({ key, title: GROUP_TITLE[key], files });
  }

  return {
    isRepo: true,
    branch: typeof branchInfo.current === "string" ? branchInfo.current : "",
    branches: asArray(branchInfo.branches).filter((b): b is string => typeof b === "string"),
    groups,
    total,
  };
}

export function parseGitLog(raw: unknown): GitCommit[] {
  const record = (raw ?? {}) as Record<string, unknown>;
  return asArray(record.commits)
    .map((item): GitCommit | null => {
      if (!item || typeof item !== "object") return null;
      const commit = item as Record<string, unknown>;
      const hash = typeof commit.hash === "string" ? commit.hash : "";
      if (!hash) return null;
      return {
        hash,
        shortHash: typeof commit.shortHash === "string" ? commit.shortHash : hash.slice(0, 7),
        message: typeof commit.message === "string" ? commit.message.split("\n")[0]!.trim() : "",
        authorName: typeof commit.authorName === "string" ? commit.authorName : "",
        authorDate: typeof commit.authorDate === "string" ? commit.authorDate : "",
      };
    })
    .filter((item): item is GitCommit => item !== null);
}

/** 提交时间显示成 10-27 15:20。 */
export function formatCommitDate(raw: string): string {
  if (!raw) return "";
  const date = new Date(raw);
  if (Number.isNaN(date.getTime())) return raw.slice(0, 16);
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  const dd = String(date.getDate()).padStart(2, "0");
  const hh = String(date.getHours()).padStart(2, "0");
  const mi = String(date.getMinutes()).padStart(2, "0");
  return `${mm}-${dd} ${hh}:${mi}`;
}

/** 文件内容太大时不预览，避免卡住 WebView。 */
export const FILE_PREVIEW_MAX_BYTES = 512 * 1024;

export function shouldPreviewFile(size: number): boolean {
  return Number.isFinite(size) && size >= 0 && size <= FILE_PREVIEW_MAX_BYTES;
}

/** 简单语言判定，用于预览时的等宽高亮提示。 */
export function fileExtension(path: string): string {
  const match = /\.([a-zA-Z0-9]+)$/.exec(path ?? "");
  return match ? match[1]!.toLowerCase() : "";
}
