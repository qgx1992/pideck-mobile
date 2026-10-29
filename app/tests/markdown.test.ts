/** Markdown 渲染：块级 + 行内 + 安全性。 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>");
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.Node = dom.window.Node;
g.HTMLElement = dom.window.HTMLElement;

const { renderMarkdown, isSafeHref, markdownToText } = await import("../src/ui/markdown.ts");

function render(md: string): HTMLElement {
  const host = document.createElement("div");
  host.append(renderMarkdown(md));
  return host;
}

test("粗体 / 斜体 / 删除线 / 行内代码", () => {
  const host = render("这是 **加粗** 和 *斜体* 以及 ~~删除~~ 和 `code()`");
  assert.equal(host.querySelectorAll("strong").length, 1);
  assert.equal(host.querySelector("strong")?.textContent, "加粗");
  assert.equal(host.querySelector("em")?.textContent, "斜体");
  assert.equal(host.querySelector("del")?.textContent, "删除");
  assert.equal(host.querySelector(".md-inline-code")?.textContent, "code()");
});

test("围栏代码块：保留内容、带语言与复制按钮", () => {
  const host = render('```json\n{"a": 1, "b": [2, 3]}\n```');
  const code = host.querySelector(".md-code");
  assert.ok(code, "应有代码块");
  assert.equal(code?.textContent, '{"a": 1, "b": [2, 3]}');
  assert.equal(code?.getAttribute("data-lang"), "json");
  assert.equal(host.querySelector(".md-codelang")?.textContent, "json");
  assert.ok(host.querySelector(".md-copy"), "应有复制按钮");
  // 代码块里的字符不该被当成 markdown
  assert.equal(host.querySelectorAll("strong").length, 0);
});

test("流式场景：未闭合的围栏也要渲染成代码块", () => {
  const host = render("先看代码：\n```python\nprint(1)\nprint(2");
  const code = host.querySelector(".md-code");
  assert.ok(code);
  assert.equal(code?.textContent, "print(1)\nprint(2");
});

test("标题 / 分隔线 / 引用", () => {
  const host = render("# 一级\n\n## 二级\n\n---\n\n> 引用一句话");
  assert.equal(host.querySelector(".md-h1")?.textContent, "一级");
  assert.equal(host.querySelector(".md-h2")?.textContent, "二级");
  assert.ok(host.querySelector(".md-hr"));
  assert.equal(host.querySelector(".md-quote")?.textContent?.trim(), "引用一句话");
});

test("无序 / 有序 / 嵌套列表", () => {
  const host = render("- 一\n- 二\n  - 二点一\n\n1. 甲\n2. 乙");
  const nested = host.querySelector(".md-list--nested");
  assert.ok(nested, "缩进 2 空格应算嵌套");
  assert.equal(nested?.textContent, "二点一");
  const unordered = host.querySelector("ul.md-list");
  assert.equal(unordered?.children.length, 2, "有序列表不能被吸进 ul");
  const ordered = host.querySelector("ol.md-list");
  assert.equal(ordered?.querySelectorAll("li").length, 2);
});

test("任务列表渲染成勾选框", () => {
  const host = render("- [x] 已完成\n- [ ] 未完成");
  const boxes = host.querySelectorAll("input.md-task");
  assert.equal(boxes.length, 2);
  assert.equal(boxes[0]?.hasAttribute("checked"), true);
  assert.equal(boxes[1]?.hasAttribute("checked"), false);
});

test("表格", () => {
  const host = render("| 名称 | 值 |\n| --- | --- |\n| a | 1 |\n| b | 2 |");
  const table = host.querySelector(".md-table");
  assert.ok(table);
  assert.equal(table?.querySelectorAll("th").length, 2);
  assert.equal(table?.querySelectorAll("tbody tr").length, 2);
  assert.equal(table?.querySelector("td")?.textContent, "a");
});

test("链接：安全协议保留，javascript: 降级成纯文本", () => {
  const host = render("[官网](https://example.com) 和 [坏链接](javascript:alert(1))");
  const links = host.querySelectorAll("a.md-link");
  assert.equal(links.length, 1);
  assert.equal(links[0]?.getAttribute("href"), "https://example.com");
  assert.ok(host.textContent?.includes("坏链接"));
  assert.equal(host.innerHTML.includes("javascript:"), false, "不能写进 DOM");
});

test("isSafeHref 白名单", () => {
  assert.equal(isSafeHref("https://a.com"), true);
  assert.equal(isSafeHref("http://a.com"), true);
  assert.equal(isSafeHref("mailto:a@b.c"), true);
  assert.equal(isSafeHref("/local/path"), true);
  assert.equal(isSafeHref("#hash"), true);
  assert.equal(isSafeHref("javascript:alert(1)"), false);
  assert.equal(isSafeHref("data:text/html,x"), false);
});

test("单换行变 <br>（对齐 Web 端 remarkBreaks）", () => {
  const host = render("第一行\n第二行");
  assert.equal(host.querySelectorAll("br").length, 1);
});

test("HTML 标签不会被当成标签解析", () => {
  const host = render("<img src=x onerror=alert(1)> 正常文字");
  assert.equal(host.querySelector("img"), null);
  assert.ok(host.textContent?.includes("<img"));
});

test("markdownToText 去掉标记", () => {
  assert.equal(markdownToText("**粗** 和 `代码`"), "粗 和 代码");
  assert.equal(markdownToText("## 标题"), "标题");
  assert.equal(markdownToText("[文字](https://x.com)"), "文字");
});
