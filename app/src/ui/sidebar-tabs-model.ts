/**
 * 侧栏三分段（对齐桌面端侧栏顶部的 活动 / 聊天 / 项目 切换）。
 *
 * 桌面端的语义（见 SidebarContent / ProjectTree）：
 *   - 活动：上半是绑定了运行时的 Agent 行，下半是「最近会话」（排除已在活动区出现的）
 *   - 聊天：只显示内置 Chat 项目的会话
 *   - 项目：所有工作区项目（不含内置 Chat）及其会话
 *
 * 手机端没有「Agent 标签页」和「访问记录」，所以：
 *   - 活动区按 runtime 是否还活着判定，行上再区分「在跑」与「挂着」
 *   - 最近会话用 updatedAt 当排序基准（手机端没记访问时间）
 *   - 行上补一个项目名，否则跨项目的列表看不出消息从哪来
 */

import type { PiDeckState, Project, SessionSummary } from "@core/types.ts";
import { projectLabel } from "./sidebar-model.ts";

export type SidebarTab = "active" | "chats" | "projects";

export const SIDEBAR_TABS: readonly { id: SidebarTab; label: string }[] = [
  { id: "active", label: "活动" },
  { id: "chats", label: "聊天" },
  { id: "projects", label: "项目" },
];

export function parseSidebarTab(value: unknown): SidebarTab | null {
  return value === "active" || value === "chats" || value === "projects" ? value : null;
}

export function sidebarTabLabel(tab: SidebarTab): string {
  return SIDEBAR_TABS.find((item) => item.id === tab)?.label ?? "项目";
}

/** 「最近会话」最多放几条（与桌面端活动页一致） */
export const ACTIVITY_RECENT_LIMIT = 10;

export function isChatProject(project: Project | undefined | null): boolean {
  return project?.kind === "chat";
}

/** 项目分段要显示的：工作区项目（内置 Chat 归到「聊天」分段） */
export function workspaceProjects(projects: readonly Project[]): Project[] {
  return projects.filter((project) => !isChatProject(project));
}

export function chatProject(projects: readonly Project[]): Project | undefined {
  return projects.find((project) => isChatProject(project));
}

function byUpdatedDesc(a: SessionSummary, b: SessionSummary): number {
  return (b.updatedAt ?? 0) - (a.updatedAt ?? 0);
}

/** 这些状态的运行时已经不算「活动」了。 */
const INACTIVE_RUNTIME_STATUS = new Set(["stopped", "closed", "exited", "error", "aborted"]);

export interface ActivityRow {
  session: SessionSummary;
  projectLabel: string;
  /** 正在真正跑（决定行上的脉冲点） */
  running: boolean;
}

export interface ActivityView {
  /** 上半区：还活着的运行时绑定的会话 */
  live: ActivityRow[];
  /** 下半区：其余最近会话 */
  recent: ActivityRow[];
}

function labelFor(projects: readonly Project[], session: SessionSummary): string {
  return projectLabel(projects.find((project) => project.id === session.projectId)) ?? "";
}

/**
 * 活动分段的数据。
 *
 * 判活规则：runtime 存在且状态不是 stopped/closed/exited/error/aborted。
 * 只有 status === "running" 的行才带脉冲点 —— 挂着的运行时也是「活动」，但没在跑。
 */
export function buildActivityView(
  state: Pick<PiDeckState, "projects" | "sessions" | "runtimes">,
  limit: number = ACTIVITY_RECENT_LIMIT,
): ActivityView {
  const liveIds = new Set<string>();
  const runningIds = new Set<string>();
  for (const runtime of state.runtimes) {
    const status = typeof runtime.status === "string" ? runtime.status.toLowerCase() : "";
    if (INACTIVE_RUNTIME_STATUS.has(status)) continue;
    liveIds.add(runtime.sessionId);
    if (status === "running") runningIds.add(runtime.sessionId);
  }

  const sorted = [...state.sessions].sort(byUpdatedDesc);
  const toRow = (session: SessionSummary): ActivityRow => ({
    session,
    projectLabel: labelFor(state.projects, session),
    running: runningIds.has(session.id),
  });

  const live = sorted.filter((session) => liveIds.has(session.id)).map(toRow);
  const recent = sorted
    .filter((session) => !liveIds.has(session.id))
    .slice(0, Math.max(0, limit))
    .map(toRow);

  return { live, recent };
}

/** 聊天分段：内置 Chat 项目下的会话，按 updatedAt 倒序。 */
export function chatSessions(state: Pick<PiDeckState, "projects" | "sessions">): SessionSummary[] {
  const project = chatProject(state.projects);
  if (!project) return [];
  return state.sessions.filter((session) => session.projectId === project.id).sort(byUpdatedDesc);
}

export interface FlatSessionRow {
  session: SessionSummary;
  projectLabel: string;
}

/**
 * 搜索：跨项目扁平匹配（标题或项目名命中）。
 *
 * 手机窄屏上按项目分组搜索反而难扫，所以搜索时忽略当前分段，直接给一张扁平清单。
 */
export function searchSessions(
  state: Pick<PiDeckState, "projects" | "sessions">,
  query: string,
): FlatSessionRow[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [];
  return state.sessions
    .filter((session) => {
      const title = (session.title ?? "").toLowerCase();
      const label = labelFor(state.projects, session).toLowerCase();
      return title.includes(needle) || label.includes(needle);
    })
    .sort(byUpdatedDesc)
    .map((session) => ({ session, projectLabel: labelFor(state.projects, session) }));
}

/** 分段按钮上的计数（让用户不点进去也知道有没有东西）。 */
export function tabCounts(state: Pick<PiDeckState, "projects" | "sessions" | "runtimes">): Record<SidebarTab, number> {
  const activity = buildActivityView(state);
  return {
    active: activity.live.length + activity.recent.length,
    chats: chatSessions(state).length,
    projects: workspaceProjects(state.projects).length,
  };
}

/* ------------------------------------------------------------------ */
/* 多端点（一机连多台）聚合                                            */
/* ------------------------------------------------------------------ */

/** 一个已连接端点的最小状态面。 */
export interface EndpointState {
  id: string;
  label: string;
  state: Pick<PiDeckState, "projects" | "sessions" | "runtimes"> & {
    pendingUiRequests?: PiDeckState["pendingUiRequests"];
  };
}

export interface EndpointTag {
  endpointId: string;
  endpointLabel: string;
}

export interface MultiActivityRow extends ActivityRow, EndpointTag {}

export interface MultiActivityView {
  live: MultiActivityRow[];
  recent: MultiActivityRow[];
}

/**
 * 设备间时钟可能差几分钟，未来时间戳会把某台机器的会话永远顶在最上面。
 * 这里把超过「现在 + 1 分钟」的时间压回去，宁可排错也不让它霸榜。
 */
const CLOCK_SKEW_MS = 60_000;

function sortAt(session: SessionSummary, now: number): number {
  return Math.min(session.updatedAt ?? 0, now + CLOCK_SKEW_MS);
}

/** 跨设备的「活动」分段：活着的运行时 + 跨设备取最近若干条。 */
export function buildActivityViewMulti(
  endpoints: readonly EndpointState[],
  limit: number = ACTIVITY_RECENT_LIMIT,
  now: number = Date.now(),
): MultiActivityView {
  const live: MultiActivityRow[] = [];
  const rest: MultiActivityRow[] = [];

  for (const endpoint of endpoints) {
    const tag = { endpointId: endpoint.id, endpointLabel: endpoint.label };
    const view = buildActivityView(endpoint.state, Number.MAX_SAFE_INTEGER);
    for (const row of view.live) live.push({ ...row, ...tag });
    for (const row of view.recent) rest.push({ ...row, ...tag });
  }

  const byRecent = (left: MultiActivityRow, right: MultiActivityRow): number =>
    sortAt(right.session, now) - sortAt(left.session, now);

  live.sort(byRecent);
  rest.sort(byRecent);
  return { live, recent: rest.slice(0, Math.max(0, limit)) };
}

/** 跨设备的「聊天」分段：各端内置 Chat 项目的会话。 */
export function chatSessionsMulti(
  endpoints: readonly EndpointState[],
  now: number = Date.now(),
): Array<{ session: SessionSummary } & EndpointTag> {
  const rows: Array<{ session: SessionSummary } & EndpointTag> = [];
  for (const endpoint of endpoints) {
    for (const session of chatSessions(endpoint.state)) {
      rows.push({ session, endpointId: endpoint.id, endpointLabel: endpoint.label });
    }
  }
  return rows.sort((left, right) => sortAt(right.session, now) - sortAt(left.session, now));
}

/** 跨设备搜索：标题或项目名命中，行上带设备名与项目名。 */
export function searchSessionsMulti(
  endpoints: readonly EndpointState[],
  query: string,
  now: number = Date.now(),
): Array<FlatSessionRow & EndpointTag> {
  const rows: Array<FlatSessionRow & EndpointTag> = [];
  for (const endpoint of endpoints) {
    for (const row of searchSessions(endpoint.state, query)) {
      rows.push({ ...row, endpointId: endpoint.id, endpointLabel: endpoint.label });
    }
  }
  return rows.sort((left, right) => sortAt(right.session, now) - sortAt(left.session, now));
}

/** 「项目」分段：多设备时按设备分块，单设备时直接就是原来的一套。 */
export function endpointCatalogs(endpoints: readonly EndpointState[]): Array<{
  endpointId: string;
  endpointLabel: string;
  projects: Project[];
  sessions: SessionSummary[];
  running: number;
}> {
  return endpoints.map((endpoint) => {
    const runningIds = new Set(
      endpoint.state.runtimes
        .filter((runtime) => typeof runtime.status === "string" && runtime.status === "running")
        .map((runtime) => runtime.sessionId),
    );
    return {
      endpointId: endpoint.id,
      endpointLabel: endpoint.label,
      projects: workspaceProjects(endpoint.state.projects),
      sessions: endpoint.state.sessions,
      running: runningIds.size,
    };
  });
}

/** 某个端点上的待答卡片数量（设备选择器上的红点）。 */
export function pendingCount(endpoint: EndpointState): number {
  return endpoint.state.pendingUiRequests?.length ?? 0;
}
