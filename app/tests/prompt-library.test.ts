/**
 * 提示词库的数据模型：宽松解析、本地搜索、分类筛选。
 *
 * 后端形状（`/api/prompts`）：`{ categories: [{slug,name,count}], prompts: [{slug,title,category,subcategory,tags,description}], total? }`
 */

import test from "node:test";
import assert from "node:assert/strict";

const { filterPrompts, filterByCategory, normalizePrompts, promptSubtitle } = await import(
  "../src/ui/prompt-library-model.ts"
);

const payload = {
  categories: [
    { slug: "coding", name: "编码", count: 2 },
    { slug: "writing", name: "写作", count: 1 },
  ],
  prompts: [
    {
      slug: "review-diff",
      title: "审查改动",
      category: "coding",
      subcategory: "review",
      tags: ["git", "diff"],
      description: "逐条审查未提交的改动",
    },
    {
      slug: "fix-tests",
      title: "修失败测试",
      category: "coding",
      subcategory: "test",
      tags: ["test"],
      description: "跑测试并修复",
    },
    {
      slug: "summary",
      title: "写周报",
      category: "writing",
      subcategory: "",
      tags: [],
      description: "把本周工作整理成周报",
    },
  ],
};

test("解析分类与条目，key 用 category/slug 保证唯一", () => {
  const parsed = normalizePrompts(payload);
  assert.deepEqual(parsed.categories.map((item) => item.name), ["编码", "写作"]);
  assert.equal(parsed.items.length, 3);
  assert.equal(parsed.items[0]!.key, "coding/review-diff");
  assert.deepEqual(parsed.items[0]!.tags, ["git", "diff"]);
});

test("脏数据丢弃：没有 slug 的条目、没有分类的 categories", () => {
  const parsed = normalizePrompts({
    categories: [{ name: "没有 slug" }, { slug: "ok", name: "好" }],
    prompts: [{ title: "没有 slug" }, { slug: "a", title: "有" }, null, 42],
  });
  assert.deepEqual(parsed.categories.map((item) => item.slug), ["ok"]);
  assert.deepEqual(parsed.items.map((item) => item.slug), ["a"]);
  // title 缺失时用 slug 兜底
  assert.equal(normalizePrompts({ prompts: [{ slug: "x" }] }).items[0]!.title, "x");
});

test("非法输入不炸", () => {
  assert.deepEqual(normalizePrompts(null), { categories: [], items: [] });
  assert.deepEqual(normalizePrompts({ prompts: "nope" }), { categories: [], items: [] });
});

test("本地搜索：标题 / 描述 / 标签 / 分类都能命中", () => {
  const { items } = normalizePrompts(payload);
  assert.deepEqual(filterPrompts(items, "审查").map((item) => item.slug), ["review-diff"]);
  assert.deepEqual(filterPrompts(items, "git").map((item) => item.slug), ["review-diff"]);
  assert.deepEqual(filterPrompts(items, "测试").map((item) => item.slug), ["fix-tests"]);
  assert.deepEqual(filterPrompts(items, "writing").map((item) => item.slug), ["summary"]);
  // 空查询返回全部
  assert.equal(filterPrompts(items, "   ").length, 3);
  // 没命中
  assert.deepEqual(filterPrompts(items, "zzz"), []);
});

test("分类筛选 + 与搜索叠加", () => {
  const { items } = normalizePrompts(payload);
  assert.deepEqual(filterByCategory(items, "coding").map((item) => item.slug), ["review-diff", "fix-tests"]);
  assert.equal(filterByCategory(items, "").length, 3);
  assert.deepEqual(
    filterPrompts(filterByCategory(items, "coding"), "周报").map((item) => item.slug),
    [],
    "分类先过滤，搜索再过滤",
  );
});

test("列表副标题带分类与标签", () => {
  const { items } = normalizePrompts(payload);
  const subtitle = promptSubtitle(items[0]!);
  assert.match(subtitle, /coding/);
  assert.match(subtitle, /review/);
  assert.match(subtitle, /git/);
  // 没有子分类与标签时只剩分类，不留多余分隔符
  assert.equal(promptSubtitle(items[2]!), "writing");
});
