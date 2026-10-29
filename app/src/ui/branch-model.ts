/**
 * 会话分支家族（对齐桌面端 branchFamily / Web 端 webBranchFamily）。
 *
 * 会话分叉（fork）后，同一「家族」里的会话共享一个根：沿 parentSessionId
 * 向上找根，再自根向下收集全部后代，按 createdAt 升序返回。
 * 家族只有一条时不显示分支条。
 *
 * 脏数据（互相指父）不会死循环：向上向下都带 visited 集合。
 */

import type { SessionSummary } from "@core/types.ts";

export interface BranchNode {
  id: string;
  title: string;
  forked: boolean;
}

export function deriveBranchFamily(
  sessions: readonly SessionSummary[],
  sessionId: string | null,
): SessionSummary[] {
  if (!sessionId) return [];
  const byId = new Map(sessions.map((session) => [session.id, session] as const));
  const active = byId.get(sessionId);
  if (!active) return [];

  const visitedUp = new Set<string>([active.id]);
  let root = active;
  while (root.parentSessionId) {
    const parent = byId.get(root.parentSessionId);
    if (!parent || visitedUp.has(parent.id)) break;
    visitedUp.add(parent.id);
    root = parent;
  }

  const childrenOf = new Map<string, SessionSummary[]>();
  for (const session of sessions) {
    if (!session.parentSessionId) continue;
    const bucket = childrenOf.get(session.parentSessionId);
    if (bucket) bucket.push(session);
    else childrenOf.set(session.parentSessionId, [session]);
  }

  const family: SessionSummary[] = [];
  const queue: SessionSummary[] = [root];
  const visitedDown = new Set<string>();
  while (queue.length > 0) {
    const node = queue.shift();
    if (!node || visitedDown.has(node.id)) continue;
    visitedDown.add(node.id);
    family.push(node);
    for (const child of childrenOf.get(node.id) ?? []) queue.push(child);
  }

  family.sort((left, right) => (left.createdAt ?? 0) - (right.createdAt ?? 0));
  return family;
}

/** 分支条要显示的节点（含「是不是分叉出来的」标记）。 */
export function branchNodes(
  sessions: readonly SessionSummary[],
  sessionId: string | null,
): BranchNode[] {
  return deriveBranchFamily(sessions, sessionId).map((session) => ({
    id: session.id,
    title: session.title?.trim() || "未命名会话",
    forked: Boolean(session.forked) || Boolean(session.parentSessionId),
  }));
}

/** 只有真的分叉过才值得占一行。 */
export function shouldShowBranchBar(nodes: readonly BranchNode[]): boolean {
  return nodes.length > 1;
}

/** 分支 chip 上的短标题：太长的会话名在窄屏会挤成一团。 */
export function shortBranchTitle(title: string, max = 12): string {
  const text = title.trim();
  if (text.length <= max) return text;
  return `${text.slice(0, max)}…`;
}
