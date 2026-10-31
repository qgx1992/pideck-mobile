/**
 * 提示词库（`/api/prompts`）的数据模型。
 *
 * 与桌面端是同一个库（XuePromptManager）。列表只给摘要，正文要再按 slug 拉一次。
 * 这里负责把后端返回收拾成可渲染的分类 + 条目，并做本地过滤（搜索框输入时不必每次都打接口）。
 */

/**
 * 一次拉多少条。
 *
 * 服务端 `MAX_PROMPT_PAGE_SIZE = 50`（`src/main/web/WebWorkspaceRoutes.ts`）会把更大的值截到 50，
 * 所以客户端按 50 取、搜索/分类都交给服务端 —— 库里有四千多条，只拉一页再本地过滤必然会漏。
 */
export const PROMPT_PAGE_SIZE = 50;

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

/**
 * 提示词库 db 的就绪状态（上游客服端 `YaoPromptListResult.dbState`）。
 *
 * 安装包不再内置 db，首次使用要在电脑端远程拉取；没拉下来时服务端返回的 `prompts` 是空数组。
 * 老版本 PiDeck 的 `/api/prompts` 不透传该字段，所以这里允许 `undefined`。
 */
export type PromptDbState = "ready" | "missing" | "downloading" | "failed";

function dbStateOf(value: unknown): PromptDbState | undefined {
  return value === "ready" || value === "missing" || value === "downloading" || value === "failed" ? value : undefined;
}

/** 宽松解析 `/api/prompts` 的返回。 */
export function normalizePrompts(raw: unknown): {
  categories: PromptCategory[];
  items: PromptItem[];
  dbState: PromptDbState | undefined;
} {
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

  return { categories, items, dbState: dbStateOf(payload.dbState) };
}

/**
 * 列表为空时的解释文案。
 *
 * 空数组最常见的原因不是「库里真没条目」，而是电脑端提示词库还没完成首次下载
 * （安装包不内置 db）；老版本 PiDeck 不透传 dbState，只能给通用引导。
 */
export function emptyPromptsHint(dbState: PromptDbState | undefined): string {
  if (dbState === "downloading") return "电脑端正在下载提示词库，等它下载完再点「重试」。";
  if (dbState === "missing") {
    return "电脑端还没下载提示词库：在电脑上打开 PiDeck → 设置 → 提示词库，等它完成首次下载后回来点「重试」。";
  }
  if (dbState === "failed") {
    return "电脑端下载提示词库失败了：在电脑上打开 PiDeck → 设置 → 提示词库，点重试把库拉下来，再回来点「重试」。";
  }
  return "这台 PiDeck 没有返回提示词。通常是电脑端提示词库还没完成首次下载（安装包不自带库）：在电脑上打开 PiDeck → 设置 → 提示词库，等它下载完再点「重试」。";
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
