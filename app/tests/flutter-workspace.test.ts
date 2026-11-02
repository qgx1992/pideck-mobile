/**
 * 工作区抽屉快照（`app/src/platform/flutter-workspace.ts`）。
 *
 * 这里只测「纯函数那半」：排序、路径换算、预览截断、空快照的形状。
 * 真正取数（git status / 目录列表）在 `main.ts`，走的是 mock 的发送链路测试。
 */

import assert from "node:assert/strict";
import { test } from "node:test";

import {
  PREVIEW_MAX_CHARS,
  crumbsToPath,
  emptyWorkspaceSnapshot,
  lastSegment,
  sortWorkspaceEntries,
  truncatePreview,
} from "../src/platform/flutter-workspace.ts";

test("空快照：关着、默认看文件、各列表为空", () => {
  const snapshot = emptyWorkspaceSnapshot();
  assert.equal(snapshot.open, false);
  // 默认页是「文件」：抽屉多数时候是用来找文件的，「改动」经常是空的
  assert.equal(snapshot.tab, "files");
  assert.equal(snapshot.loading, false);
  assert.deepEqual(snapshot.changes, []);
  assert.deepEqual(snapshot.entries, []);
  assert.deepEqual(snapshot.todos, []);
  assert.equal(snapshot.preview, null);
});

test("排序：目录在前，同类型按中文数字序", () => {
  const entries = sortWorkspaceEntries([
    { name: "src", path: "src", dir: true },
    { name: "10-说明.md", path: "10-说明.md", dir: false },
    { name: "app", path: "app", dir: true },
    { name: "2-说明.md", path: "2-说明.md", dir: false },
  ]);
  assert.deepEqual(
    entries.map((item) => item.name),
    ["app", "src", "2-说明.md", "10-说明.md"],
  );
});

test("路径换算：面包屑跳过项目名，最后一段取自路径", () => {
  assert.equal(crumbsToPath(["PiDeck", "app", "src"]), "app/src");
  assert.equal(crumbsToPath(["PiDeck"]), "");
  assert.equal(crumbsToPath([]), "");
  assert.equal(lastSegment("app/src/main.ts"), "main.ts");
  assert.equal(lastSegment("app\\src"), "src");
  assert.equal(lastSegment(""), "");
});

test("预览截断：短内容原样，长内容截到上限并标 truncated", () => {
  const short = truncatePreview("hello");
  assert.equal(short.content, "hello");
  assert.equal(short.truncated, false);

  const long = truncatePreview("x".repeat(PREVIEW_MAX_CHARS + 10));
  assert.equal(long.content.length, PREVIEW_MAX_CHARS);
  assert.equal(long.truncated, true);
});
