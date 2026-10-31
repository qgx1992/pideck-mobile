/**
 * 提示词库（`/api/prompts`）的数据模型。
 *
 * 与桌面端是同一个库（XuePromptManager）。列表只给摘要，正文要再按 slug 拉一次。
 * 这里负责把后端返回收拾成可渲染的分类 + 条目，并做本地过滤（搜索框输入时不必每次都打接口）。
 */

export interface PromptItem {
  /** 分类 + slug 才能唯一定位（不同分类下可能同名） */
  key: string;
  slug: string;
  title: string;
  category: string;
  subcategory: string;
  tags: string[];
  description: string;
}

export interface PromptCategory {
  slug: string;
  name: string;
  count: number;
}

function str(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" ? (value as Record<string, unknown>) : {};
}

/** 宽松解析 `/api/prompts` 的返回。 */
export function normalizePrompts(raw: unknown): { categories: PromptCategory[]; items: PromptItem[] } {
  const payload = asRecord(raw);

  const items: PromptItem[] = [];
  for (const entry of Array.isArray(payload.prompts) ? payload.prompts : []) {
    const record = asRecord(entry);
    const slug = str(record.slug).trim();
    if (!slug) continue;
    const category = str(record.category).trim();
    items.push({
      key: `${category}/${slug}`,
      slug,
      title: str(record.title).trim() || slug,
      category,
      subcategory: str(record.subcategory).trim(),
      tags: Array.isArray(record.tags) ? record.tags.map((tag) => str(tag)).filter(Boolean) : [],
      description: str(record.description).trim(),
    });
  }

  const categories: PromptCategory[] = [];
  for (const entry of Array.isArray(payload.categories) ? payload.categories : []) {
    const record = asRecord(entry);
    const slug = str(record.slug).trim();
    if (!slug) continue;
    const count = typeof record.count === "number" && Number.isFinite(record.count) ? record.count : 0;
    categories.push({ slug, name: str(record.name).trim() || slug, count });
  }

  return { categories, items };
}

/** 本地过滤：标题 / 描述 / 标签 / 分类命中。 */
export function filterPrompts(items: readonly PromptItem[], query: string): PromptItem[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [...items];
  return items.filter((item) => {
    const haystack = [item.title, item.description, item.category, item.subcategory, ...item.tags]
      .join(" ")
      .toLowerCase();
    return haystack.includes(needle);
  });
}

/** 分类筛选（`""` 表示全部）。 */
export function filterByCategory(items: readonly PromptItem[], category: string): PromptItem[] {
  if (!category) return [...items];
  return items.filter((item) => item.category === category);
}

/** 列表行副标题：分类 + 子分类 + 标签，避免只剩一句描述看不出出处。 */
export function promptSubtitle(item: PromptItem): string {
  return [item.category, item.subcategory, item.tags.slice(0, 2).join(" ")]
    .filter(Boolean)
    .join(" · ");
}
