/**
 * / 斜线命令的视图模型。
 *
 * 数据来源（对齐桌面端的 / 菜单）：
 *   1. 运行时命令：/runtime/commands，来自 pi 扩展与内置命令（goal、plan、compact…）
 *   2. 提示词库：/api/prompts
 * 二者合并去重，运行时命令优先（同名时以运行时为准）。
 */

import type { CommandInfo } from "@core/types.ts";

export type SlashSource = "runtime" | "prompt" | "builtin";

export interface SlashItem {
  /** 不含斜线的名字 */
  name: string;
  description: string;
  source: SlashSource;
}

export interface SlashSection {
  title: string;
  items: SlashItem[];
}

const SOURCE_TITLE: Record<SlashSource, string> = {
  runtime: "命令",
  builtin: "内置",
  prompt: "提示词",
};

/** 从提示词库原始结构里取名字与描述（形状随版本变化，做宽松提取）。 */
export function promptToItem(raw: unknown): SlashItem | null {
  if (!raw || typeof raw !== "object") return null;
  const record = raw as Record<string, unknown>;
  const name = typeof record.name === "string" ? record.name : typeof record.id === "string" ? record.id : "";
  if (!name.trim()) return null;
  const description =
    typeof record.description === "string"
      ? record.description
      : typeof record.title === "string"
        ? record.title
        : "";
  return { name: name.trim(), description, source: "prompt" };
}

export function commandToItem(raw: CommandInfo): SlashItem | null {
  const name = typeof raw.name === "string" ? raw.name.trim() : "";
  if (!name) return null;
  return {
    name,
    description: typeof raw.description === "string" ? raw.description : "",
    source: "runtime",
  };
}

/** 合并三路来源：运行时 > 内置 > 提示词，同名只留优先级最高的。 */
export function mergeSlashItems(input: {
  runtime?: readonly CommandInfo[];
  prompts?: readonly unknown[];
  builtin?: readonly SlashItem[];
}): SlashItem[] {
  const seen = new Set<string>();
  const result: SlashItem[] = [];

  const push = (item: SlashItem | null): void => {
    if (!item) return;
    const key = item.name.toLowerCase();
    if (seen.has(key)) return;
    seen.add(key);
    result.push(item);
  };

  for (const raw of input.runtime ?? []) push(commandToItem(raw));
  for (const item of input.builtin ?? []) push(item);
  for (const raw of input.prompts ?? []) push(promptToItem(raw));

  return result;
}

export function filterSlashItems(items: readonly SlashItem[], query: string): SlashItem[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [...items];
  return items.filter(
    (item) =>
      item.name.toLowerCase().includes(needle) || item.description.toLowerCase().includes(needle),
  );
}

/** 按来源分组，供面板分区展示。 */
export function groupSlashItems(items: readonly SlashItem[]): SlashSection[] {
  const order: SlashSource[] = ["runtime", "builtin", "prompt"];
  const buckets = new Map<SlashSource, SlashItem[]>();
  for (const item of items) {
    const list = buckets.get(item.source) ?? [];
    list.push(item);
    buckets.set(item.source, list);
  }
  return order
    .filter((source) => (buckets.get(source)?.length ?? 0) > 0)
    .map((source) => ({ title: SOURCE_TITLE[source], items: buckets.get(source)! }));
}

/** 点击命令后插入到输入框的文本。 */
export function slashText(item: SlashItem): string {
  return `/${item.name} `;
}

/**
 * 内置命令：这些不依赖运行时，永远可用。
 * 只放确认存在的（PiDeck 输入框支持 /compact）。
 */
export const BUILTIN_SLASH: SlashItem[] = [
  { name: "compact", description: "压缩上下文，腾出空间继续对话", source: "builtin" },
];
