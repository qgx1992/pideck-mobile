/**
 * 通用弹层快照（`app/src/platform/flutter-sheet.ts`）。
 *
 * 这里只测「行怎么拼」——真正容易错的是 id 前缀：新建会话面板里后端行是 `backend:`、
 * 工作区行是 `project:`，解析侧靠前缀分流。前缀写错会变成「点了没反应」而不是报错。
 */

import assert from "node:assert/strict";
import test from "node:test";

import { backendSheetItems } from "../src/platform/flutter-sheet.ts";

const options = [
  { key: "pi", label: "Pi", hint: "内置 pi agent" },
  { key: "acp:gemini", label: "ACP · Gemini CLI", hint: "外部 CLI" },
];

test("后端行：id 带 backend: 前缀，选中的那行打勾", () => {
  const items = backendSheetItems(options, "acp:gemini");
  assert.deepEqual(
    items.map((item) => item.id),
    ["backend:pi", "backend:acp:gemini"],
  );
  assert.equal(items[0]?.selected, false);
  assert.equal(items[1]?.selected, true);
  assert.equal(items[1]?.actionable, true);
});

test("后端行：hint 落到 description，分组与禁用按需附加", () => {
  const items = backendSheetItems(options, "pi", { group: "后端", disabled: true });
  assert.equal(items[0]?.description, "内置 pi agent");
  assert.equal(items[0]?.group, "后端");
  assert.equal(items[0]?.disabled, true);
  // 没有 hint 时不产出空的 description 字段（Dart 侧据此判断要不要画第二行）
  const bare = backendSheetItems([{ key: "dsh", label: "DSH", hint: "" }], "dsh");
  assert.equal("description" in (bare[0] ?? {}), false);
});

test("后端行：自定义前缀用于「切换后端」与「新建会话」共用的同一份列表", () => {
  const items = backendSheetItems(options, "pi", { prefix: "pick:" });
  assert.deepEqual(
    items.map((item) => item.id),
    ["pick:pi", "pick:acp:gemini"],
  );
});
