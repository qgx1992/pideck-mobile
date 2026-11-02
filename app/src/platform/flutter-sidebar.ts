/**
 * 侧栏快照：把侧栏要画的东西摊成一份纯数据，推给 Flutter 的 `Drawer`。
 *
 * 与聊天页同一套「视图迁移」：分组 / 折叠 / 搜索 / 徽标这些规则仍然只在这里算
 * （`sidebar-model.ts` 与 `sidebar-tabs-model.ts` 是唯一实现），Dart 侧只负责画。
 *
 * 为什么不像 `settingsData()` 那样直接在 main.ts 里拼：
 * 侧栏的分段、折叠、预览截断、多设备分块组合起来分支很多，抽成纯函数才测得动。
 */

import type { SheetView } from "./flutter-sheet.ts";
import type { SessionSummary } from "@core/types.ts";
import { acpToolNameOf, backendBadge, normalizeBackend } from "../ui/backend-capabilities.ts";
import type { ExpandOverride, ProjectGroupView } from "../ui/sidebar-model.ts";
import { SESSIONS_PREVIEW, buildSidebarGroups, resolveExpandedProjects } from "../ui/sidebar-model.ts";
import type { EndpointState, SidebarTab } from "../ui/sidebar-tabs-model.ts";
import {
  ACTIVITY_RECENT_LIMIT,
  SIDEBAR_TABS,
  buildActivityView,
  buildActivityViewMulti,
  chatSessionsMulti,
  endpointCatalogs,
  searchSessions,
  searchSessionsMulti,
  tabCounts,
} from "../ui/sidebar-tabs-model.ts";

/** 一行会话。字段名与 `flutter_module/lib/sidebar/sidebar_models.dart` 一一对应。 */
export interface SidebarRowView {
  id: string;
  title: string;
  /** 第二行：「项目名 · 14:05」或「14:05」 */
  meta: string;
  /** 后端徽标文案（pi 后端为空，避免噪音） */
  badge?: string;
  /** 徽标样式名：pi / dsh / acp */
  badgeKind?: string;
  /** ACP 会话绑定的 CLI 显示名（行尾灰字，用户得知道背后是哪个 CLI） */
  acpToolName?: string;
  /** 多设备时的设备名徽标 */
  deviceLabel?: string;
  active: boolean;
  running: boolean;
  acp: boolean;
  dsh: boolean;
}

export interface SidebarSectionView {
  /** 展开/收起动作的键（`toggleSection` 用它） */
  key: string;
  kind: "flat" | "device" | "group";
  /** 小标题（扁平段用） */
  title?: string;
  /** 设备名 / 项目名 */
  label?: string;
  /** 项目分段：折叠状态 */
  collapsed?: boolean;
  /** 项目分段：该项目的 id（`+` 与长按菜单要用） */
  projectId?: string;
  /** 项目分段：是否当前会话所在项目 */
  active?: boolean;
  /** 项目分段：会话总数（搜索时用来区分「本来就空」与「被过滤掉」） */
  count?: number;
  /** 项目分段：组内正在跑的会话数（折叠时项目头上有脉冲点） */
  runningCount?: number;
  /** 多设备：设备头上正在跑的数量 */
  deviceRunning?: number;
  rows: SidebarRowView[];
  /** 被预览截断的条数（0 表示没有「展开其余 N 个」） */
  hiddenCount: number;
  expanded: boolean;
  /** 空态文案 */
  empty?: string;
}

export interface SidebarSnapshot {
  tab: SidebarTab;
  tabs: Array<{ id: SidebarTab; label: string; count: number }>;
  query: string;
  sections: SidebarSectionView[];
  deviceCount: number;
  /** 整个侧栏一条都没有时的文案 */
  empty?: string;
  /** 侧栏发起的弹层（新建会话 / 切换后端）；null 表示不显示 */
  sheet?: SheetView | null;
}

export interface SidebarSnapshotInput {
  tab: SidebarTab;
  query: string;
  /** 已连接的端点（单设备时长度为 1） */
  endpoints: EndpointState[];
  activeEndpointId: string;
  activeSessionId: string | null;
  activeProjectId: string | null;
  /** 用户显式收起的项目 */
  collapsedProjects: ReadonlySet<string>;
  /** 项目内「展开全部会话」的显式意图 */
  expandedSessions: ExpandOverride;
  /** 扁平段（活动 / 聊天）的「展开其余」状态 */
  flatExpanded: ReadonlySet<string>;
  /** 侧栏发起的弹层（由侧栏动作算出，原样带出） */
  sheet?: SheetView | null;
  now?: number;
}

/** 与 `ui/dom.ts` 的 `fmtTime` 同口径，但留在本模块里以保持可单测（不 import DOM）。 */
export function timeLabel(ms: number | undefined, now: number = Date.now()): string {
  if (!ms) return "";
  const date = new Date(ms);
  const sameDay = new Date(now).toDateString() === date.toDateString();
  const hh = String(date.getHours()).padStart(2, "0");
  const mm = String(date.getMinutes()).padStart(2, "0");
  if (sameDay) return `${hh}:${mm}`;
  return `${date.getMonth() + 1}/${date.getDate()}`;
}

function rowOf(
  session: SessionSummary,
  options: {
    active: boolean;
    running: boolean;
    projectLabel?: string;
    deviceLabel?: string;
    acpTools?: unknown;
    now: number;
  },
): SidebarRowView {
  const backend = typeof session.backend === "string" ? session.backend : "";
  const badge = backendBadge(backend);
  const acp = normalizeBackend(backend) === "acp";
  const meta = [options.projectLabel ?? "", timeLabel(session.updatedAt, options.now)]
    .filter(Boolean)
    .join(" · ");

  const acpToolName = acp ? acpToolNameOf(options.acpTools, session.acpToolId) : null;

  return {
    id: session.id,
    title: session.title || "未命名会话",
    meta,
    ...(badge ? { badge, badgeKind: backend } : {}),
    ...(acpToolName ? { acpToolName } : {}),
    ...(options.deviceLabel ? { deviceLabel: options.deviceLabel } : {}),
    active: options.active,
    running: options.running,
    acp,
    dsh: backend === "dsh",
  };
}

function runningIdsOf(state: EndpointState["state"]): Set<string> {
  return new Set(
    state.runtimes.filter((item) => item.status === "running").map((item) => item.sessionId),
  );
}

/** 把一个扁平列表按 `preview` 截断，并算出隐藏条数。 */
function clipped(
  rows: SidebarRowView[],
  preview: number | null,
  expanded: boolean,
): { rows: SidebarRowView[]; hiddenCount: number } {
  if (preview === null || rows.length <= preview) {
    return { rows, hiddenCount: 0 };
  }
  // 展开时也要报出被折叠的条数：否则 Dart 侧拿不到「收起」这个动作的入口
  return { rows: expanded ? rows : rows.slice(0, preview), hiddenCount: rows.length - preview };
}

function flatSection(
  key: string,
  rows: SidebarRowView[],
  options: {
    title?: string;
    empty?: string;
    preview: number;
    expanded: boolean;
  },
): SidebarSectionView {
  const { rows: visible, hiddenCount } = clipped(rows, options.preview, options.expanded);
  return {
    key,
    kind: "flat",
    ...(options.title ? { title: options.title } : {}),
    rows: visible,
    hiddenCount,
    expanded: options.expanded,
    ...(rows.length === 0 && options.empty ? { empty: options.empty } : {}),
  };
}

function groupSection(
  group: ProjectGroupView,
  ctx: {
    activeSessionId: string | null;
    activeProjectId: string | null;
    expanded: boolean;
    runningIds: Set<string>;
    acpTools?: unknown;
    now: number;
  },
): SidebarSectionView {
  const runningCount = group.sessions.filter((session) => ctx.runningIds.has(session.id)).length;
  const rows = group.sessions.map((session) =>
    rowOf(session, {
      active: session.id === ctx.activeSessionId,
      running: ctx.runningIds.has(session.id),
      acpTools: ctx.acpTools,
      now: ctx.now,
    }),
  );
  const { rows: visible, hiddenCount } = clipped(rows, SESSIONS_PREVIEW, ctx.expanded);

  return {
    key: `project:${group.project.id}`,
    kind: "group",
    label: group.label,
    projectId: group.project.id,
    collapsed: group.collapsed,
    active: group.project.id === ctx.activeProjectId,
    count: group.sessions.length,
    runningCount,
    rows: group.collapsed ? [] : visible,
    hiddenCount,
    expanded: ctx.expanded,
    ...(group.sessions.length === 0
      ? { empty: group.total === 0 ? "暂无会话" : "无匹配" }
      : {}),
  };
}

/**
 * 组装侧栏快照。
 *
 * 分段规则与 `main.ts` 的 `renderSidebar()` 严格一致（那边是 DOM 版的唯一实现，
 * 这边只是把同样的数据摊平；改规则时两处一起改，单测在 `app/tests/flutter-sidebar.test.ts`）。
 */
export function buildSidebarSnapshot(input: SidebarSnapshotInput): SidebarSnapshot {
  const now = input.now ?? Date.now();
  const multi = input.endpoints.length > 1;
  const active = input.endpoints.find((item) => item.id === input.activeEndpointId) ?? input.endpoints[0];
  if (!active) {
    return {
      tab: input.tab,
      tabs: [],
      query: input.query,
      sections: [],
      deviceCount: 0,
      sheet: input.sheet ?? null,
    };
  }

  const multiActivity = multi ? buildActivityViewMulti(input.endpoints, ACTIVITY_RECENT_LIMIT, now) : null;
  const counts = multiActivity
    ? {
        active: multiActivity.live.length + multiActivity.recent.length,
        chats: chatSessionsMulti(input.endpoints, now).length,
        projects: endpointCatalogs(input.endpoints).reduce(
          (sum, catalog) => sum + catalog.projects.length,
          0,
        ),
      }
    : tabCounts(active.state);

  const tabs = SIDEBAR_TABS.map((tab) => ({ id: tab.id, label: tab.label, count: counts[tab.id] }));
  const query = input.query.trim();

  /* ---------------------------- 搜索 ---------------------------- */
  if (query) {
    const rows = multi
      ? searchSessionsMulti(input.endpoints, query, now).map((row) =>
          rowOf(row.session, {
            active: row.session.id === input.activeSessionId,
            running: runningIdsOf(
              input.endpoints.find((item) => item.id === row.endpointId)?.state ?? active.state,
            ).has(row.session.id),
            projectLabel: row.projectLabel,
            ...(multi ? { deviceLabel: row.endpointLabel } : {}),
            acpTools: active.state.acpTools,
            now,
          }),
        )
      : searchSessions(active.state, query).map((row) =>
          rowOf(row.session, {
            active: row.session.id === input.activeSessionId,
            running: runningIdsOf(active.state).has(row.session.id),
            projectLabel: row.projectLabel,
            acpTools: active.state.acpTools,
            now,
          }),
        );

    return {
      tab: input.tab,
      tabs,
      query: input.query,
      deviceCount: input.endpoints.length,
      sheet: input.sheet ?? null,
      sections: [
        flatSection("search", rows, {
          preview: rows.length,
          expanded: true,
          empty: "没有匹配的会话",
        }),
      ],
    };
  }

  /* ---------------------------- 活动 ---------------------------- */
  if (input.tab === "active") {
    const view = multi
      ? buildActivityViewMulti(input.endpoints, ACTIVITY_RECENT_LIMIT, now)
      : buildActivityView(active.state, ACTIVITY_RECENT_LIMIT);
    const toRow = (row: {
      session: SessionSummary;
      projectLabel: string;
      running: boolean;
      endpointLabel?: string;
    }): SidebarRowView =>
      rowOf(row.session, {
        active: row.session.id === input.activeSessionId,
        running: row.running,
        projectLabel: row.projectLabel,
        ...(row.endpointLabel ? { deviceLabel: row.endpointLabel } : {}),
        acpTools: active.state.acpTools,
        now,
      });

    const sections: SidebarSectionView[] = [];
    const live = flatSection("active-live", view.live.map(toRow), {
      title: "运行中",
      preview: 6,
      expanded: input.flatExpanded.has("active-live"),
    });
    if (view.live.length > 0) sections.push(live);
    sections.push(
      flatSection("active-recent", view.recent.map(toRow), {
        title: "最近会话",
        preview: ACTIVITY_RECENT_LIMIT,
        expanded: input.flatExpanded.has("active-recent"),
        empty: "还没有会话；发一条消息就会出现在这里",
      }),
    );

    return {
      tab: input.tab,
      tabs,
      query: input.query,
      deviceCount: input.endpoints.length,
      sheet: input.sheet ?? null,
      sections,
    };
  }

  /* ---------------------------- 聊天 ---------------------------- */
  if (input.tab === "chats") {
    const rows = chatSessionsMulti(
      multi ? input.endpoints : [{ id: active.id, label: active.label, state: active.state }],
      now,
    ).map((row) =>
      rowOf(row.session, {
        active: row.session.id === input.activeSessionId,
        running: runningIdsOf(
          input.endpoints.find((item) => item.id === row.endpointId)?.state ?? active.state,
        ).has(row.session.id),
        ...(multi ? { deviceLabel: row.endpointLabel } : {}),
        acpTools: active.state.acpTools,
        now,
      }),
    );

    return {
      tab: input.tab,
      tabs,
      query: input.query,
      deviceCount: input.endpoints.length,
      sheet: input.sheet ?? null,
      sections: [
        flatSection("chats", rows, {
          preview: 12,
          expanded: input.flatExpanded.has("chats"),
          empty: "没有聊天会话（桌面端没有启用内置 Chat 项目）",
        }),
      ],
    };
  }

  /* ---------------------------- 项目 ---------------------------- */
  const catalogs = multi
    ? endpointCatalogs(input.endpoints)
    : [
        {
          endpointId: active.id,
          endpointLabel: active.label,
          projects: endpointCatalogs([active])[0]?.projects ?? [],
          sessions: active.state.sessions,
          running: runningIdsOf(active.state).size,
        },
      ];

  const sections: SidebarSectionView[] = [];
  for (const catalog of catalogs) {
    const endpointState =
      input.endpoints.find((item) => item.id === catalog.endpointId)?.state ?? active.state;
    const groups = buildSidebarGroups(
      { projects: catalog.projects, sessions: catalog.sessions },
      "",
      input.collapsedProjects,
    );
    if (groups.length === 0) continue;

    if (multi) {
      sections.push({
        key: `device:${catalog.endpointId}`,
        kind: "device",
        label: catalog.endpointLabel,
        active: catalog.endpointId === input.activeEndpointId,
        deviceRunning: catalog.running,
        rows: [],
        hiddenCount: 0,
        expanded: true,
      });
    }

    const expandedProjects = resolveExpandedProjects(
      endpointState,
      input.expandedSessions,
      catalog.endpointId === input.activeEndpointId ? input.activeSessionId : null,
    );

    for (const group of groups) {
      sections.push(
        groupSection(group, {
          activeSessionId:
            catalog.endpointId === input.activeEndpointId ? input.activeSessionId : null,
          activeProjectId:
            catalog.endpointId === input.activeEndpointId ? input.activeProjectId : null,
          expanded: expandedProjects.has(group.project.id),
          runningIds: runningIdsOf(endpointState),
          acpTools: endpointState.acpTools,
          now,
        }),
      );
    }
  }

  return {
    tab: input.tab,
    tabs,
    query: input.query,
    deviceCount: input.endpoints.length,
    sheet: input.sheet ?? null,
    sections,
    ...(sections.filter((section) => section.kind === "group").length === 0
      ? { empty: "还没有项目，请先在桌面端添加目录" }
      : {}),
  };
}
