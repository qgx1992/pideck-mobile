/** @ 文件引用与 / 斜线命令的视图模型。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  buildMentionView,
  filterNodes,
  isDirectory,
  joinPath,
  mentionText,
  sortNodes,
} from "../src/ui/mention-model.ts";
import {
  BUILTIN_SLASH,
  commandToItem,
  filterSlashItems,
  groupSlashItems,
  mergeSlashItems,
  promptToItem,
  slashText,
} from "../src/ui/slash-model.ts";

const nodes = [
  { name: "src", relativePath: "src", type: "directory", hasChildren: true },
  { name: "aaa.txt", relativePath: "aaa.txt", type: "file" },
  { name: "README.md", relativePath: "README.md", type: "file" },
];

test("目录排在前，同类按名称排序", () => {
  const sorted = sortNodes(nodes as never);
  assert.deepEqual(
    sorted.map((n) => n.name),
    ["src", "aaa.txt", "README.md"],
  );
});

test("过滤按相对路径、大小写不敏感；空查询返回全部", () => {
  assert.equal(filterNodes(nodes as never, "readme").length, 1);
  assert.equal(filterNodes(nodes as never, "sr").length, 1);
  assert.equal(filterNodes(nodes as never, "  ").length, 3);
});

test("面包屑按栈生成，depth 与栈对齐", () => {
  const view = buildMentionView("demo", ["src", "ui"], nodes as never);
  assert.deepEqual(
    view.crumbs.map((c) => [c.label, c.depth]),
    [
      ["demo", 0],
      ["src", 1],
      ["ui", 2],
    ],
  );
});

test("joinPath 拼相对路径，空栈是空串", () => {
  assert.equal(joinPath([]), "");
  assert.equal(joinPath(["src", "ui"]), "src/ui");
});

test("点击文件插入 @相对路径 并以空格结尾", () => {
  assert.equal(mentionText({ name: "a", relativePath: "src/ui/a.ts", type: "file" } as never), "@src/ui/a.ts ");
  assert.equal(mentionText({ name: "a", relativePath: "ui\\a.ts", type: "file" } as never), "@ui/a.ts ", "反斜杠要转正斜杠");
});

test("只有 directory 能进入", () => {
  assert.equal(isDirectory(nodes[0] as never), true);
  assert.equal(isDirectory(nodes[1] as never), false);
});

test("斜线命令：运行时优先，同名不重复", () => {
  const items = mergeSlashItems({
    runtime: [{ name: "goal", description: "目标模式" }],
    prompts: [{ name: "goal", description: "重复的" }, { name: "review", description: "代码审查" }],
    builtin: BUILTIN_SLASH,
  });
  assert.deepEqual(
    items.map((i) => i.name),
    ["goal", "compact", "review"],
  );
  assert.equal(items[0]?.source, "runtime", "同名以运行时为准");
});

test("promptToItem / commandToItem 对缺字段的输入返回 null", () => {
  assert.equal(promptToItem(null), null);
  assert.equal(promptToItem({ title: "无名字" }), null);
  assert.equal(commandToItem({ name: "  " }), null);
  assert.equal(commandToItem({ name: "compact", description: "压缩" })?.name, "compact");
});

test("按来源分组，顺序为 命令 → 内置 → 提示词", () => {
  const items = mergeSlashItems({
    runtime: [{ name: "plan" }],
    prompts: [{ name: "review" }],
    builtin: BUILTIN_SLASH,
  });
  assert.deepEqual(
    groupSlashItems(items).map((s) => s.title),
    ["命令", "内置", "提示词"],
  );
});

test("搜索命中名字或描述", () => {
  const items = mergeSlashItems({ runtime: [{ name: "plan", description: "计划模式" }], builtin: BUILTIN_SLASH });
  assert.equal(filterSlashItems(items, "计划").length, 1);
  assert.equal(filterSlashItems(items, "PLAN").length, 1);
  assert.equal(filterSlashItems(items, "zzz").length, 0);
});

test("插入文本以斜线开头并带空格", () => {
  assert.equal(slashText({ name: "compact", description: "", source: "builtin" }), "/compact ");
});
