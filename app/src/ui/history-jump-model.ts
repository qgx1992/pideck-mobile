/**
 * 「跳转到历史消息」的视图模型。
 *
 * 面板里列出可定位的消息（自己发的 + AI 的正文），点一条就滚到那条并高亮。
 * tool / system 这类过程消息不进列表——它们在「执行过程」折叠块里，定位意义不大。
 */

import type { WireMessage } from "@core/types.ts";
import { stripCompleteThinking } from "./thinking-tags.ts";

export type JumpRole = "user" | "assistant";

export interface JumpTarget {
  /** 消息 id，用于在 DOM 里定位 */
  id: string;
  role: JumpRole;
  /** 列表里显示的摘要（压成一行） */
  preview: string;
  timestamp?: number;
}

const PREVIEW_MAX = 90;

function normalizeRole(role: unknown): string {
  return typeof role === "string" ? role.toLowerCase() : "";
}

/**
 * 与历史渲染同口径取正文。
 * 必须剥掉 `<thinking>`：Pi 某些会话把思考直接写进 text，不处理会让列表里全是思考文字。
 */
export function messageBodyText(message: WireMessage): string {
  const top = (message as { text?: unknown }).text;
  const raw = typeof top === "string" && top.trim()
    ? top
    : typeof (message as { content?: unknown }).content === "string"
      ? ((message as { content: string }).content)
      : "";
  return stripCompleteThinking(raw).trim();
}

function toPreview(text: string): string {
  const oneLine = text.replace(/\s+/g, " ").trim();
  return oneLine.length > PREVIEW_MAX ? `${oneLine.slice(0, PREVIEW_MAX - 1)}…` : oneLine;
}

/**
 * 收集可跳转的消息，**按时间倒序**（最近的在最上面）。
 *
 * 不去重：同一个问题问了两次是两个不同位置，各自都要能跳过去。
 */
export function collectJumpTargets(messages: readonly WireMessage[]): JumpTarget[] {
  const result: JumpTarget[] = [];

  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const message = messages[i];
    if (!message) continue;

    const role = normalizeRole(message.role);
    if (role !== "user" && role !== "assistant") continue;

    const text = messageBodyText(message);
    if (!text) continue;

    const id = typeof message.id === "string" ? message.id : "";
    if (!id) continue; // 没有 id 就没法定位

    const timestamp = typeof message.timestamp === "number" ? message.timestamp : undefined;
    result.push({
      id,
      role,
      preview: toPreview(text),
      ...(timestamp !== undefined ? { timestamp } : {}),
    });
  }

  return result;
}

export function filterJumpTargets(items: readonly JumpTarget[], query: string): JumpTarget[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [...items];
  return items.filter((item) => item.preview.toLowerCase().includes(needle));
}

export function roleLabel(role: JumpRole): string {
  return role === "user" ? "我" : "AI";
}
