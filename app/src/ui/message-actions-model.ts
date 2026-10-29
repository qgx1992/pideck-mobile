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
 * 工具/系统消息根本不该给菜单。
 */

import type { WireMessage } from "@core/types.ts";

export type MessageAction = "copy" | "edit" | "resend" | "delete" | "fork";

export interface MessageActionItem {
  id: MessageAction;
  label: string;
  /** 危险操作：菜单里标红，并二次确认 */
  danger?: boolean;
}

const USER_ACTIONS: readonly MessageActionItem[] = [
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

/** 未知/工具/系统消息不给菜单。 */
export function actionsForRole(role: string): MessageActionItem[] {
  const value = role.toLowerCase();
  if (value === "user") return [...USER_ACTIONS];
  if (value === "assistant") return [...ASSISTANT_ACTIONS];
  return [];
}

export function hasActions(role: string): boolean {
  return actionsForRole(role).length > 0;
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
