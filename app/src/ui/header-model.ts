/**
 * 顶部栏视图模型：标题、副标题、状态文案、运行标记。
 * 抽成纯函数，避免再次出现「切会话标题不刷新」这类只能靠肉眼看的问题。
 */

import type { PiDeckState, SessionSummary } from "@core/types.ts";

export const RUN_STATUS_LABEL: Record<string, string> = {
  running: "运行中",
  idle: "空闲",
  starting: "启动中",
  error: "失败",
  closed: "已关闭",
  active: "已就绪",
  draft: "草稿",
};

export interface HeaderView {
  title: string;
  /** 标题右侧的元信息：项目名 · 运行状态 */
  meta: string;
  /** 标题下方一行：模型 · 思考档位 */
  subtitle: string;
  statusLabel: string;
  isRunning: boolean;
  docTitle: string;
}

export function projectLabelOf(project: { id: string; kind?: string; name?: string; path?: string } | undefined): string | undefined {
  if (!project) return undefined;
  if (project.kind === "chat") return "Chat";
  const name = typeof project.name === "string" ? project.name.trim() : "";
  if (name) return name;
  const rawPath = typeof project.path === "string" ? project.path.replace(/[\\/]+$/, "") : "";
  const segments = rawPath.split(/[\\/]/).filter(Boolean);
  return segments.at(-1) ?? project.id;
}

export function buildHeaderView(
  state: PiDeckState,
  activeSessionId: string | null,
  activeProjectId: string | null,
): HeaderView {
  const session: SessionSummary | undefined = activeSessionId
    ? state.sessions.find((item) => item.id === activeSessionId)
    : undefined;
  const project = state.projects.find((item) => item.id === activeProjectId);
  const title = session?.title || projectLabelOf(project) || "PiDeck";

  const runtimeInfo = activeSessionId
    ? state.runtimes.find((item) => item.sessionId === activeSessionId)
    : undefined;

  // 运行时状态比 session.status 更能反映「正在跑」
  const rawStatus = runtimeInfo?.status ?? session?.status ?? "";
  const statusLabel = rawStatus ? (RUN_STATUS_LABEL[rawStatus] ?? rawStatus) : "";

  // 标题右侧：项目名 + 运行状态
  const meta = [
    session ? projectLabelOf(state.projects.find((item) => item.id === session.projectId)) : undefined,
    statusLabel || undefined,
  ]
    .filter((item): item is string => Boolean(item))
    .join(" · ");

  // 第二行：只留模型与思考档位
  const subtitle = [
    session?.model?.modelName ?? session?.model?.modelId ?? undefined,
    session?.thinkingLevel ? `思考 ${session.thinkingLevel}` : undefined,
  ]
    .filter((item): item is string => Boolean(item))
    .join(" · ");

  return {
    title,
    meta,
    subtitle,
    statusLabel,
    isRunning: rawStatus === "running",
    docTitle: session ? `${session.title} · PiDeck` : "PiDeck",
  };
}
