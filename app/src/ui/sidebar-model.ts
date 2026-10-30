/**
 * 侧栏视图模型：把「项目 + 会话」摊平成可渲染的分组。
 * 抽成纯函数是为了能直接测——分组/折叠/搜索是最容易写错、又最难在手机上察觉的部分。
 */

import type { Project, SessionSummary } from "@core/types.ts";

export interface ProjectGroupView {
  project: Project;
  label: string;
  sessions: SessionSummary[];
  collapsed: boolean;
  /** 该项目下的会话总数（搜索时用来区分「本来就空」和「被过滤掉」） */
  total: number;
}

export interface SidebarSource {
  projects: Project[];
  sessions: SessionSummary[];
}

/** 项目名：内置 Chat 就叫 Chat，其余优先 name，其次路径末段，最后退回 id。 */
export function projectLabel(project: Project | undefined): string | undefined {
  if (!project) return undefined;
  if (project.kind === "chat") return "Chat";
  const name = typeof project.name === "string" ? project.name.trim() : "";
  if (name) return name;
  const rawPath = typeof project.path === "string" ? project.path.replace(/[\\/]+$/, "") : "";
  const segments = rawPath.split(/[\\/]/).filter(Boolean);
  return segments.at(-1) ?? project.id;
}

/**
 * 生成侧栏分组。
 *
 * 规则：
 *   - 每个项目一个分组，按 state.projects 的原顺序（与桌面端一致）
 *   - 组内会话按 updatedAt 倒序
 *   - 搜索时跨项目过滤，空分组隐藏，且强制展开（否则搜出来的会话是收起的）
 *   - 无搜索时尊重 collapsed 集合
 */
export function buildSidebarGroups(
  source: SidebarSource,
  query: string,
  collapsed: ReadonlySet<string>,
): ProjectGroupView[] {
  const needle = query.trim().toLowerCase();
  const groups: ProjectGroupView[] = [];

  for (const project of source.projects) {
    const own = source.sessions.filter((session) => session.projectId === project.id);
    const matched = needle
      ? own.filter((session) => (session.title ?? "").toLowerCase().includes(needle))
      : own;

    if (needle && matched.length === 0) continue;

    matched.sort(byUpdatedDesc);

    groups.push({
      project,
      label: projectLabel(project) ?? project.id,
      sessions: matched,
      collapsed: needle ? false : collapsed.has(project.id),
      total: own.length,
    });
  }

  return groups;
}

function byUpdatedDesc(a: SessionSummary, b: SessionSummary): number {
  return (b.updatedAt ?? 0) - (a.updatedAt ?? 0);
}

/** 每个项目默认显示最近几个会话（与 sidebar-view 保持一致）。 */
export const SESSIONS_PREVIEW = 6;

/**
 * 用户的显式展开/收起意图：projectId → 是否展开。
 * 有值时必须优先于「自动展开」，否则用户点「收起」会被下一次渲染又顶开。
 */
export type ExpandOverride = ReadonlyMap<string, boolean>;

/**
 * 计算某次渲染时应该「展开全部会话」的项目集合。
 *
 * 优先级从高到低：
 *   1. 用户显式收起（override = false）—— 即使当前会话被截断也不再展开
 *   2. 用户显式展开（override = true）
 *   3. 当前会话在该项目里排到第 6 个之后 → 临时展开（否则看不到自己点开的会话）
 *
 * 注意：临时展开**不能**写回 override，否则进过一次会话后就永久全展开。
 */
export function resolveExpandedProjects(
  state: SidebarSource,
  override: ExpandOverride,
  activeSessionId: string | null,
  preview: number = SESSIONS_PREVIEW,
): Set<string> {
  const result = new Set<string>();

  // 先落用户显式选择
  for (const [projectId, expanded] of override) {
    if (expanded) result.add(projectId);
  }

  if (!activeSessionId) return result;

  const active = state.sessions.find((session) => session.id === activeSessionId);
  if (!active) return result;

  // 用户已经明确收起该项目 → 尊重选择，不自动展开
  if (override.get(active.projectId) === false) return result;

  const siblings = state.sessions
    .filter((session) => session.projectId === active.projectId)
    .sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0));

  const position = siblings.findIndex((session) => session.id === activeSessionId);
  if (position >= preview) result.add(active.projectId);
  return result;
}

/* ------------------------------------------------------------------ */
/* 新建会话前的「选择工作区」                                          */
/* ------------------------------------------------------------------ */

/**
 * 工作区选择器的排序：内置 Chat 置顶，其余按「最近有会话更新的时间」倒序，
 * 再按名字。手机上新建会话时最可能选的就是刚才在用的那个项目。
 */
export function sortProjectsForPicker(
  projects: readonly Project[],
  sessions: readonly SessionSummary[],
): Project[] {
  const lastActive = new Map<string, number>();
  for (const session of sessions) {
    const at = session.updatedAt ?? 0;
    if (at > (lastActive.get(session.projectId) ?? 0)) lastActive.set(session.projectId, at);
  }

  return [...projects].sort((left, right) => {
    const leftChat = left.kind === "chat" ? 0 : 1;
    const rightChat = right.kind === "chat" ? 0 : 1;
    if (leftChat !== rightChat) return leftChat - rightChat;

    const byTime = (lastActive.get(right.id) ?? 0) - (lastActive.get(left.id) ?? 0);
    if (byTime !== 0) return byTime;

    return (projectLabel(left) ?? left.id).localeCompare(projectLabel(right) ?? right.id, "zh-CN");
  });
}

/** projectId → 会话数（选择器上的次要信息）。 */
export function sessionCountByProject(sessions: readonly SessionSummary[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const session of sessions) {
    counts.set(session.projectId, (counts.get(session.projectId) ?? 0) + 1);
  }
  return counts;
}
