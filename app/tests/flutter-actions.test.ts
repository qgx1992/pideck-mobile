/**
 * Flutter 组件可用性的静态体检。
 *
 * 为什么需要它：Flutter 页上的每个按钮/手势最终都是「发一个动作名给 WebView」，
 * 而动作名在两边各写一份字面量（Dart 发、`main.ts` 的 dispatch 接）。少接一个 case
 * 的症状是**点了完全没反应**，而且不报错、不崩、日志安静 —— 真机上才发现。
 *
 * 这里把三条链路都扫一遍：
 *   1. Dart 发给聊天页的动作 ⊆ `dispatchFlutterChatAction` 的 case；
 *   2. Dart 发给侧栏的动作 ⊆ `dispatchFlutterSidebarAction` 的 case；
 *   3. 顶栏 chip 的 key 集合 = Dart 认得的 key 集合 = dispatch 里 `key === "..."` 集合。
 *
 * 动态动作名（`question.kind` 这种来自快照的）无法静态枚举，列在 DYNAMIC_ALLOW 里，
 * 新增时请同时确认 Web 侧有对应分支。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

const APP_ROOT = fileURLToPath(new URL("..", import.meta.url));
const MAIN_TS = join(APP_ROOT, "src/main.ts");
const FLUTTER_LIB = join(APP_ROOT, "..", "flutter_module", "lib");

/**
 * 动作名来自快照字段 / 由调用方传入、无法静态枚举的分支。
 *
 * - `question.kind`：提问卡的类型（confirm / select / …）来自快照；
 * - `type`：侧栏与设置页的行内通用转发（`_send('x')` 与 `sendSettingsAction('x')`
 *   才是真正枚举动作的地方，已由上面两条用例覆盖）。
 */
const DYNAMIC_ALLOW = new Set(["question.kind", "type"]);

/** 某个函数的函数体：从 `function X(` 截到下一个顶层 `function`。 */
function functionBody(source: string, name: string): string {
  const start = source.indexOf(`function ${name}(`);
  assert.ok(start >= 0, `找不到函数 ${name}`);
  const end = source.indexOf("\nfunction ", start + 1);
  return source.slice(start, end < 0 ? source.length : end);
}

function caseNames(source: string, fn: string): Set<string> {
  const body = functionBody(source, fn);
  return new Set([...body.matchAll(/case "([A-Za-z0-9]+)":/g)].map((m) => m[1]!));
}

function dartFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...dartFiles(full));
    else if (entry.endsWith(".dart")) out.push(full);
  }
  return out;
}

/** 从一个表达式里抠出动作名字面量：支持 `'x'`、`cond ? 'a' : 'b'`。 */
function literals(expression: string): string[] {
  const found = [...expression.matchAll(/'([A-Za-z][A-Za-z0-9]*)'/g)].map((m) => m[1]!);
  return found;
}

/** Dart 里所有 `'type': <expr>` 的动作名。 */
function dartChatActions(): { names: Set<string>; dynamic: Set<string> } {
  const names = new Set<string>();
  const dynamic = new Set<string>();
  for (const file of dartFiles(FLUTTER_LIB)) {
    const source = readFileSync(file, "utf-8");
    for (const match of source.matchAll(/'type':\s*([^,\n}]+)/g)) {
      const expression = match[1]!;
      const found = literals(expression);
      if (found.length === 0) {
        if (!expression.includes("'")) dynamic.add(expression.trim());
      }
      for (const name of found) names.add(name);
    }
  }
  return { names, dynamic };
}

/** Dart 设置页里所有 `_act('x')` / `widget.act('x')` 的动作名。 */
function dartSettingsActions(): Set<string> {
  const names = new Set<string>();
  for (const file of dartFiles(join(FLUTTER_LIB, "settings"))) {
    const source = readFileSync(file, "utf-8");
    for (const match of source.matchAll(/\.act\(\s*'([A-Za-z0-9]+)'/g)) names.add(match[1]!);
  }
  return names;
}

/** Dart 侧栏里所有 `_send('x', …)` 的动作名。 */
function dartSidebarActions(): Set<string> {
  const names = new Set<string>();
  for (const file of dartFiles(join(FLUTTER_LIB, "sidebar"))) {
    const source = readFileSync(file, "utf-8");
    for (const match of source.matchAll(/_send\(\s*'([A-Za-z0-9]+)'/g)) names.add(match[1]!);
  }
  return names;
}

const mainTs = readFileSync(MAIN_TS, "utf-8");

test("聊天页动作：Dart 发出的每个 type 都有 Web 侧分支", () => {
  // 页面里发出去的动作可能落在两个 dispatcher 上：聊天动作归 chat，开关抽屉 /
  // 会话改名之类归 sidebar（0.2.13 起数据动作由 Dart 执行完再带 native 回传给
  // sidebar dispatcher 重画）。所以「有人接」= 两个 dispatcher 的并集。
  const handled = new Set([
    ...caseNames(mainTs, "dispatchFlutterChatAction"),
    ...caseNames(mainTs, "dispatchFlutterSidebarAction"),
  ]);
  const { names, dynamic } = dartChatActions();
  const missing = [...names].filter((name) => !handled.has(name)).sort();
  assert.deepEqual(missing, [], `这些动作 Dart 会发、main.ts 没人接：${missing.join(", ")}`);
  for (const expr of dynamic) {
    assert.ok(DYNAMIC_ALLOW.has(expr), `动态动作名 ${expr} 没登记，新增时请确认 Web 侧有分支并加进 DYNAMIC_ALLOW`);
  }
});

test("侧栏动作：Dart 发出的每个 type 都有 Web 侧分支", () => {
  const handled = caseNames(mainTs, "dispatchFlutterSidebarAction");
  const missing = [...dartSidebarActions()].filter((name) => !handled.has(name)).sort();
  assert.deepEqual(missing, [], `这些侧栏动作 Dart 会发、main.ts 没人接：${missing.join(", ")}`);
});

test("设置页动作：Dart 发出的每个 type 都有 Web 侧分支", () => {
  const handled = caseNames(mainTs, "dispatchFlutterSettingsAction");
  const missing = [...dartSettingsActions()].filter((name) => !handled.has(name)).sort();
  assert.deepEqual(missing, [], `这些设置页动作 Dart 会发、main.ts 没人接：${missing.join(", ")}`);
});

test("动作无孤儿：Web 侧接了的 case 至少有一个 Dart 发送点", () => {
  const { names } = dartChatActions();
  const handled = caseNames(mainTs, "dispatchFlutterChatAction");
  // `close` 是宿主按钮（返回网页版）触发的，不在 Dart 的 'type' 字面量里
  const hostOnly = new Set(["close"]);
  const orphans = [...handled].filter((name) => !names.has(name) && !hostOnly.has(name)).sort();
  assert.deepEqual(orphans, [], `这些 case 没有任何 Dart 发送点（多半是改名后留下的死分支）：${orphans.join(", ")}`);
});

test("顶栏 chip：Web 产出、Dart 认得、dispatch 处理三者对齐", () => {
  const headerTs = readFileSync(join(APP_ROOT, "src/platform/flutter-chat-header.ts"), "utf-8");
  const produced = new Set([...headerTs.matchAll(/key: "([a-z]+)"/g)].map((m) => m[1]!));
  const handled = new Set(
    [...functionBody(mainTs, "dispatchFlutterChatAction").matchAll(/key === "([a-z]+)"/g)].map((m) => m[1]!),
  );
  assert.ok(produced.size > 0, "没扫到任何 chip key，正则可能失效了");
  const missing = [...produced].filter((key) => !handled.has(key)).sort();
  assert.deepEqual(missing, [], `这些 chip 点下去没有分支：${missing.join(", ")}`);
});
