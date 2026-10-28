/**
 * @ 文件引用的视图模型：目录栈、排序、过滤。
 *
 * PiDeck 的 /api/files 只列一层，所以这里维护「当前路径栈」做逐层浏览，
 * 对齐桌面端「@ 之后可以一路点进去」的手感。
 */

import type { FileNode } from "@core/types.ts";

export interface MentionView {
  /** 从项目根开始的相对路径，空数组 = 根 */
  stack: string[];
  /** 当前层的条目（目录在前、同类型按名称排序） */
  nodes: FileNode[];
  /** 面包屑：根 + 各级目录 */
  crumbs: Array<{ label: string; depth: number }>;
}

export function joinPath(stack: readonly string[]): string {
  return stack.filter(Boolean).join("/");
}

/** 目录优先，其次名字（不区分大小写）。 */
export function sortNodes(nodes: readonly FileNode[]): FileNode[] {
  return [...nodes].sort((a, b) => {
    const aDir = a.type === "directory" ? 0 : 1;
    const bDir = b.type === "directory" ? 0 : 1;
    if (aDir !== bDir) return aDir - bDir;
    return a.name.localeCompare(b.name, "zh-Hans-CN", { numeric: true });
  });
}

export function filterNodes(nodes: readonly FileNode[], query: string): FileNode[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return sortNodes(nodes);
  return sortNodes(nodes.filter((node) => node.relativePath.toLowerCase().includes(needle)));
}

/** 过滤时把命中目录视为可进入，但不做递归展开（服务端只给一层）。 */
export function buildMentionView(
  projectName: string,
  stack: readonly string[],
  nodes: readonly FileNode[],
  query = "",
): MentionView {
  const crumbs = [{ label: projectName, depth: 0 }];
  stack.forEach((segment, index) => crumbs.push({ label: segment, depth: index + 1 }));

  return {
    stack: [...stack],
    nodes: filterNodes(nodes, query),
    crumbs,
  };
}

/** 点击文件后要插入到输入框的文本。 */
export function mentionText(node: FileNode): string {
  return `@${node.relativePath.replace(/\\/g, "/")} `;
}

/** 目录类条目才允许进入。 */
export function isDirectory(node: FileNode): boolean {
  return node.type === "directory";
}
