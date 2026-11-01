/**
 * 单条消息能做什么（长按菜单的内容）。
 *
 * 对应桌面端的 SessionReplyActions / SessionActionsMenu：
 *   - 编辑：改这条消息的文本（runtime/edit-message）
 *   - 重发：把这条消息的文本填回输入框，改完再发（runtime/prepare-resend）
 *   - 删除：从会话里删掉（runtime/delete-message）
 *   - 分叉：以这条消息为起点开一个新会话（runtime/fork）
 *
 * 哪些动作能出现在菜单里由 role 决定 —— 助手消息不能「编辑」，
 * 工具/系统消息根本不该给菜单。附加的 `backend` 再收一层：ACP 会话的历史
 * 由 agent CLI 的归档主导，运行时没有 fork/编辑/删除命令，只留「复制」。
 */

import type { WireMessage } from "@core/types.ts";

export type MessageAction = "copy" | "edit" | "resend" | "delete" | "fork";

export interface MessageActionItem {
  id: MessageAction;
  label: string;
  /** 危险操作：菜单里标红，并二次确认 */
  danger?: boolean;
}

/** @deprecated 固定菜单已换成 actionsForRole(role, backend?)；保留导出只为老测试 */
export const USER_ACTIONS: readonly MessageActionItem[] = [
  { id: "copy", label: "复制文本" },
  { id: "edit", label: "编辑这条消息" },
  { id: "resend", label: "改完重发" },
  { id: "fork", label: "从这里分叉" },
  { id: "delete", label: "删除这条消息", danger: true },
];

const ASSISTANT_ACTIONS: readonly MessageActionItem[] = [
  { id: "copy", label: "复制文本" },
  { id: "fork", label: "从这里分叉" },
];

/** ACP 会话只能复制：历史由 agent CLI 的归档主导，运行时根本没有 fork/编辑/删除命令。 */
const ACP_ACTIONS: readonly MessageActionItem[] = [{ id: "copy", label: "复制文本" }];

/**
 * 这条消息能出现哪些动作。
 *
 * `backend` 省略时按 pi 处理（老调用点行为不变）；ACP 只给「复制」——
 * 桌面端 WebTimeline 同样是 `canManageMessages={acp ? false : …}`。
 */
export function actionsForRole(role: string, backend?: unknown): MessageActionItem[] {
  const value = role.toLowerCase();
  if (isAcpBackend(backend)) return value === "user" || value === "assistant" ? [...ACP_ACTIONS] : [];
  if (value === "user") return [...USER_ACTIONS];
  if (value === "assistant") return [...ASSISTANT_ACTIONS];
  return [];
}

export function hasActions(role: string, backend?: unknown): boolean {
  return actionsForRole(role, backend).length > 0;
}

function isAcpBackend(backend: unknown): boolean {
  return typeof backend === "string" && backend.trim().toLowerCase() === "acp";
}

/**
 * 消息在运行时接口里的 id。
 *
 * 历史消息的 id 可能只在 `id`，也可能只在 `meta.entryId`（早期版本），
 * 两个都取不到就别给菜单 —— 点了也只会 400。
 */
export function messageIdOf(message: WireMessage): string | null {
  const direct = message.id;
  if (typeof direct === "string" && direct.trim()) return direct.trim();
  const entry = message.entryId;
  if (typeof entry === "string" && entry.trim()) return entry.trim();
  const meta = (message as { meta?: Record<string, unknown> }).meta;
  const metaEntry = meta?.entryId;
  if (typeof metaEntry === "string" && metaEntry.trim()) return metaEntry.trim();
  return null;
}

/** fork 用的是 entryId（会话条目 id），与 messageId 同源，取不到就退回 messageId。 */
export function forkEntryIdOf(message: WireMessage): string | null {
  const entry = message.entryId;
  if (typeof entry === "string" && entry.trim()) return entry.trim();
  const meta = (message as { meta?: Record<string, unknown> }).meta;
  const metaEntry = meta?.entryId;
  if (typeof metaEntry === "string" && metaEntry.trim()) return metaEntry.trim();
  return messageIdOf(message);
}

/** 二次确认的文案（删除是不可恢复的）。 */
export function confirmTextFor(action: MessageAction): string {
  if (action === "delete") return "删除后这一条会从会话里消失（不可恢复），确认？";
  return "";
}
