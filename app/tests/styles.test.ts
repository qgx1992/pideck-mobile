/**
 * 样式表静态检查：守住「假图标」这类只在真机上才看得见的事故。
 *
 * 背景：`.process > summary::before` 里曾写着 `content: "\25B8"`（▸），
 * 某个环节把 `\25` 当八进制（0o25 = 0x15）吃掉，留下 `0x15` + 字面量 `B8`。
 * 0x15 是不可见控制符，于是「执行过程」折叠条前面平白多出 "B8"。
 *
 * 后来箭头改成内联 SVG（ui/chevron.ts），但这条 ::before 没删，
 * 伪元素和 SVG 并存，屏幕上就是 `B8 ▸ 执行过程`。
 *
 * 为什么只能静态查：伪元素不进 DOM 文本树，
 * ui.test.ts 与 smoke-ui.mjs 里 `textContent.includes("B8")` 的断言永远是假的。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const CSS_PATH = fileURLToPath(new URL("../src/styles.css", import.meta.url));
const raw = readFileSync(CSS_PATH, "utf-8");
/** 去掉注释后的正文：解释性文字里提到 B8 不算违规 */
const css = raw.replace(/\/\*[\s\S]*?\*\//g, "");

/** 所有 `content: "..."` 声明的取值 */
function contentValues(source: string): Array<{ value: string; line: number }> {
  const out: Array<{ value: string; line: number }> = [];
  for (const match of source.matchAll(/content\s*:\s*("[^"]*"|'[^']*')/g)) {
    const line = source.slice(0, match.index).split("\n").length;
    out.push({ value: match[1], line });
  }
  return out;
}

test("styles.css 不含不可见控制字符", () => {
  const hits = [...raw.matchAll(/[\u0000-\u0008\u000b\u000e-\u001f]/g)];
  const where = hits.slice(0, 3).map((hit) => {
    const line = raw.slice(0, hit.index).split("\n").length;
    return `第 ${line} 行 0x${(hit[0] as string).charCodeAt(0).toString(16)}`;
  });
  assert.equal(hits.length, 0, `发现 ${hits.length} 个控制字符：${where.join(" / ")}`);
});

test("content 声明里没有转义被吞掉的碎片", () => {
  for (const { value, line } of contentValues(css)) {
    // ▸ = U+25B8；`\25` 被当八进制吃掉后只剩 "B8"
    assert.ok(
      !/B8/i.test(value),
      `第 ${line} 行 content: ${value} —— 疑似 \\25B8 被八进制吞掉的残留`,
    );
  }
});

test("执行过程折叠头的箭头只由 SVG 提供，不来自 ::before", () => {
  const pseudo = /\.process[^{}]*summary::before|\.process[^{}]*summary:before/.test(css);
  assert.equal(pseudo, false, ".process summary 不应再有 ::before（箭头由 ui/chevron.ts 画）");
  // 反向确认：rules 里没有任何残留的「展开时旋转伪元素」
  assert.equal(/\.process\[open\][^{}]*summary::before/.test(css), false);
});
