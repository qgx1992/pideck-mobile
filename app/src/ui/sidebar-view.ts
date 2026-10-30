/**
 * 侧栏 DOM 渲染（纯函数）：输入分组视图模型，输出节点。
 * 抽出来的目的是可以脱离网络/全局状态直接测。
 */

import type { SessionSummary } from "@core/types.ts";
import { h, fmtTime } from "./dom.ts";
import { createChevron } from "./chevron.ts";
import { attachPressable } from "./press.ts";
import type { ProjectGroupView } from "./sidebar-model.ts";

export interface SidebarViewHandlers {
  onToggleProject: (projectId: string) => void;
  onOpenSession: (sessionId: string) => void;
  onCreateSession: (projectId: string) => void;
  /** 长按/右键会话 → 操作菜单 */
  onSessionMenu: (sessionId: string) => void;
  /** 长按/右键项目 → 操作菜单 */
  onProjectMenu: (projectId: string) => void;
  /** 展开 / 收起该项目的剩余会话 */
  onToggleSessions: (projectId: string) => void;
}

export interface SidebarViewContext {
  activeSessionId: string | null;
  activeProjectId: string | null;
  handlers: SidebarViewHandlers;
  /** 该项目是否已展开全部会话（默认只显示最近 N 个） */
  expandedProjects?: ReadonlySet<string>;
  /** 正在运行的会话 id（来自 state.runtimes 中 status=running 的项） */
  runningSessionIds?: ReadonlySet<string>;
}

/** 每个项目默认显示最近几个会话，其余折叠。 */
export const SESSIONS_PREVIEW_COUNT = 6;

export function renderProjectGroup(group: ProjectGroupView, ctx: SidebarViewContext): HTMLElement {
  const { handlers } = ctx;
  const isActiveProject = group.project.id === ctx.activeProjectId;

  const addBtn = h("span", {
    class: "project-add",
    text: "+",
    title: "在此项目新建会话",
    onclick: (event: MouseEvent) => {
      event.stopPropagation();
      handlers.onCreateSession(group.project.id);
    },
  });

  // 分组折叠时，若组内有会话在跑，项目头上也要能看出来
  const runningInGroup = group.sessions.filter((s) => ctx.runningSessionIds?.has(s.id)).length;
  const groupRunningDot =
    group.collapsed && runningInGroup > 0
      ? h("span", { class: "pulse-dot", title: `${runningInGroup} 个会话运行中` })
      : null;

  const head = h(
    "button",
    {
      class: `project-head${isActiveProject ? " is-active" : ""}`,
      title: group.collapsed ? "展开（长按可管理项目）" : "收起（长按可管理项目）",
    },
    [
      createChevron(!group.collapsed, 14),
      h("span", { class: "pname", text: group.label }),
      ...(groupRunningDot ? [groupRunningDot] : []),
      h("span", { class: "pcount", text: String(group.sessions.length) }),
      addBtn,
    ],
  );

  attachPressable(head, {
    onTap: () => handlers.onToggleProject(group.project.id),
    onLongPress: () => handlers.onProjectMenu(group.project.id),
  });

  const list = h("div", { class: "project-sessions" });
  if (group.sessions.length === 0) {
    list.append(h("p", { class: "group-empty", text: group.total === 0 ? "暂无会话" : "无匹配" }));
  } else {
    const expanded = ctx.expandedProjects?.has(group.project.id) === true;
    const overflow = group.sessions.length > SESSIONS_PREVIEW_COUNT;
    const visible = overflow && !expanded ? group.sessions.slice(0, SESSIONS_PREVIEW_COUNT) : group.sessions;

    for (const session of visible) {
      list.append(
        renderSessionRow(session, session.id === ctx.activeSessionId, handlers, {
          running: ctx.runningSessionIds?.has(session.id) === true,
        }),
      );
    }

    if (overflow) {
      const hidden = group.sessions.length - SESSIONS_PREVIEW_COUNT;
      list.append(
        h(
          "button",
          {
            class: `sessions-more${expanded ? " is-expanded" : ""}`,
            title: expanded ? "收起多余的会话" : `展开其余 ${hidden} 个会话`,
            onclick: (event: MouseEvent) => {
              event.stopPropagation();
              handlers.onToggleSessions(group.project.id);
            },
          },
          [
            createChevron(expanded, 12),
            h("span", { text: expanded ? "收起" : `展开其余 ${hidden} 个` }),
          ],
        ),
      );
    }
  }

  return h("section", { class: `project-group${group.collapsed ? " is-collapsed" : ""}` }, [head, list]);
}

/** 后端徽章文案：pi 后端不显示，避免噪音。 */
const BACKEND_BADGE: Record<string, string> = {
  dsh: "DSH",
  imagegen: "生图",
};

export function renderSessionRow(
  session: SessionSummary,
  isActive: boolean,
  handlers: Pick<SidebarViewHandlers, "onOpenSession" | "onSessionMenu">,
  options: { running?: boolean; projectLabel?: string } = {},
): HTMLElement {
  const isRunning = options.running === true;
  const backend = typeof session.backend === "string" ? session.backend : "";
  const badge = BACKEND_BADGE[backend] ?? "";
  const isDsh = backend === "dsh";

  const meta = [options.projectLabel ?? "", fmtTime(session.updatedAt)].filter(Boolean).join(" · ");

  const nameRow = h("span", { class: "sname-row" }, [
    h("span", { class: "sname", text: session.title || "未命名会话" }),
    ...(badge ? [h("span", { class: `sbadge sbadge--${backend}`, text: badge })] : []),
  ]);

  const row = h(
    "button",
    {
      class: `session${isActive ? " is-active" : ""}${isDsh ? " session--dsh" : ""}${isRunning ? " session--running" : ""}`,
      title: `${isRunning ? "运行中 · " : ""}${badge ? `${badge} 后端 · ` : ""}长按可重命名 / 复制 / 删除`,
    },
    [
      nameRow,
      h("span", { class: "smeta" }, [
        ...(isRunning ? [h("span", { class: "pulse-dot pulse-dot--sm" })] : []),
        h("span", { text: meta }),
      ]),
    ],
  );

  attachPressable(row, {
    onTap: () => handlers.onOpenSession(session.id),
    onLongPress: () => handlers.onSessionMenu(session.id),
  });

  return row;
}

export interface FlatSectionRow {
  session: SessionSummary;
  projectLabel?: string;
  running?: boolean;
}

export interface FlatSectionOptions {
  /** 区块标题（不传就不画标题） */
  title?: string;
  /** 没有数据时的文案 */
  empty?: string;
  /** 超过多少条折叠，默认全部显示 */
  preview?: number;
  /** 当前是否已展开（配合 onToggle） */
  expanded?: boolean;
  onToggle?: () => void;
  /** 没有数据时是否整块隐藏（活动区上半区就该这样） */
  hideWhenEmpty?: boolean;
}

/**
 * 扁平会话列表（活动 / 聊天 / 搜索结果共用）。
 *
 * 与项目分组不同，这里没有「项目头」，行上带项目名；
 * 跨项目列表看不出消息从哪来是最容易踩的坑。
 */
export function renderFlatSection(
  rows: readonly FlatSectionRow[],
  ctx: SidebarViewContext,
  options: FlatSectionOptions = {},
): HTMLElement | null {
  const { handlers } = ctx;
  if (rows.length === 0 && options.hideWhenEmpty) return null;

  const children: Array<HTMLElement | null> = [];
  if (options.title) children.push(h("div", { class: "flat-title", text: options.title }));

  if (rows.length === 0) {
    children.push(h("p", { class: "group-empty", text: options.empty ?? "暂无会话" }));
  } else {
    const preview = options.preview ?? rows.length;
    const overflow = rows.length > preview;
    const visible = overflow && !options.expanded ? rows.slice(0, preview) : rows;
    for (const row of visible) {
      children.push(
        renderSessionRow(row.session, row.session.id === ctx.activeSessionId, handlers, {
          running: row.running === true || ctx.runningSessionIds?.has(row.session.id) === true,
          ...(row.projectLabel ? { projectLabel: row.projectLabel } : {}),
        }),
      );
    }
    if (overflow && options.onToggle) {
      const hidden = rows.length - preview;
      const expanded = options.expanded === true;
      children.push(
        h(
          "button",
          {
            class: `sessions-more${expanded ? " is-expanded" : ""}`,
            title: expanded ? "收起多余的会话" : `展开其余 ${hidden} 个会话`,
            onclick: (event: MouseEvent) => {
              event.stopPropagation();
              options.onToggle?.();
            },
          },
          [createChevron(expanded, 12), h("span", { text: expanded ? "收起" : `展开其余 ${hidden} 个` })],
        ),
      );
    }
  }

  return h("section", { class: "flat-section" }, children);
}
