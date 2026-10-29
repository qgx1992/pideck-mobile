/** 语法高亮：分词正确性与「不丢字符」不变式。 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>");
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;

const { highlight, highlightInto, normalizeLang, supportsLang } = await import("../src/ui/highlight.ts");

const join = (tokens: Array<{ text: string }>): string => tokens.map((token) => token.text).join("");

const samples: Record<string, string> = {
  json: '{"name": "pideck", "count": 42, "ok": true, "list": [1, 2.5, -3e4], "none": null}',
  js: 'const x = 1;\n// 注释\nfunction add(a, b) { return a + b; }\nconst s = "hi \\" there";',
  python: 'def main(x):\n    # 注释\n    s = """三引号"""\n    return {"k": 1.5} if x else None',
  shell: '#!/bin/bash\nset -e\necho "hello $USER" # 行尾注释',
  sql: "SELECT id, name FROM users WHERE age > 18 -- 注释\nAND name = 'tom';",
  diff: "--- a/x.ts\n+++ b/x.ts\n@@ -1,2 +1,3 @@\n-const a = 1;\n+const a = 2;\n const b = 3;",
  html: '<div class="box" data-id=\'7\'><!-- 注释 --><span>hi</span></div>',
  go: 'package main\n\nfunc main() {\n\tfmt.Println("hi", 42)\n}',
  text: "任意 **纯文本** 1 + 1",
};

test("所有 token 拼起来必须和输入完全一致（不丢字符）", () => {
  for (const [lang, code] of Object.entries(samples)) {
    assert.equal(join(highlight(code, lang)), code, `${lang} 丢字符了`);
  }
});

test("未知语言按纯文本处理，同样不丢字符", () => {
  const code = "whatever ✨ \"quoted\" 123";
  const tokens = highlight(code, "brainfuck");
  assert.equal(join(tokens), code);
  assert.equal(supportsLang("brainfuck"), false);
});

test("没有语言标注时不乱染色（普通文字里的 foo( 不算函数）", () => {
  const code = "看这里 foo(1) 和 123";
  const tokens = highlight(code, "");
  assert.equal(tokens.length, 1);
  assert.equal(tokens[0]?.kind, "plain");
  assert.equal(join(tokens), code);
});

test("JSON：键名与字符串区分开", () => {
  const tokens = highlight(samples.json!, "json");
  const keys = tokens.filter((token) => token.kind === "key").map((token) => token.text);
  assert.deepEqual(keys, ['"name"', '"count"', '"ok"', '"list"', '"none"']);
  assert.ok(tokens.some((token) => token.kind === "number"));
  assert.ok(tokens.some((token) => token.kind === "literal" && token.text === "true"));
  // 值的字符串不该被判成 key
  assert.equal(tokens.filter((token) => token.kind === "string")[0]?.text, '"pideck"');
});

test("JS：关键字 / 注释 / 函数名 / 字符串", () => {
  const tokens = highlight(samples.js!, "js");
  const kinds = new Set(tokens.map((token) => token.kind));
  assert.ok(kinds.has("keyword"));
  assert.ok(kinds.has("comment"));
  assert.ok(kinds.has("function"));
  assert.ok(kinds.has("string"));
  assert.equal(tokens.find((token) => token.kind === "function")?.text, "add");
  // 字符串里的转义引号不能提前结束
  assert.ok(tokens.some((token) => token.kind === "string" && token.text.includes('\\"')));
});

test("Python：三引号整段算字符串，装饰器/关键字正常", () => {
  const tokens = highlight(samples.python!, "python");
  assert.ok(tokens.some((token) => token.kind === "string" && token.text === '"""三引号"""'));
  assert.ok(tokens.some((token) => token.kind === "keyword" && token.text === "def"));
  // Python 的 None/True/False 归为字面量（和 JS 的 true/false 一致）
  assert.ok(tokens.some((token) => token.kind === "literal" && token.text === "None"));
});

test("Shell：shebang 与行尾注释都是注释", () => {
  const tokens = highlight(samples.shell!, "shell");
  const comments = tokens.filter((token) => token.kind === "comment").map((token) => token.text);
  assert.equal(comments[0], "#!/bin/bash");
  assert.ok(comments.some((text) => text.startsWith("# 行尾注释")));
});

test("SQL：关键字大小写不敏感", () => {
  const tokens = highlight(samples.sql!, "sql");
  const keywords = tokens.filter((token) => token.kind === "keyword").map((token) => token.text.toLowerCase());
  assert.ok(keywords.includes("select"));
  assert.ok(keywords.includes("from"));
  assert.ok(keywords.includes("where"));
});

test("diff：新增/删除/位置行分别着色", () => {
  const tokens = highlight(samples.diff!, "diff");
  const byKind = (kind: string) => tokens.filter((token) => token.kind === kind).map((token) => token.text);
  assert.ok(byKind("add").includes("+const a = 2;"));
  assert.ok(byKind("del").includes("-const a = 1;"));
  assert.ok(byKind("meta").some((text) => text.startsWith("@@")));
});

test("HTML：标签名与属性名分开", () => {
  const tokens = highlight(samples.html!, "html");
  const tags = tokens.filter((token) => token.kind === "tag").map((token) => token.text);
  // 开闭标签都会命中：<div><span></span></div>
  assert.deepEqual(tags, ["div", "span", "span", "div"]);
  const attrs = tokens.filter((token) => token.kind === "attr").map((token) => token.text);
  assert.ok(attrs.includes("class"));
  assert.ok(attrs.includes("data-id"));
  assert.ok(tokens.some((token) => token.kind === "comment"));
});

test("语言别名与 normalizeLang", () => {
  assert.equal(normalizeLang("TypeScript"), "typescript");
  assert.equal(normalizeLang("language-json"), "json");
  assert.equal(normalizeLang(""), "text");
  assert.equal(normalizeLang("unknown-lang"), "text");
  assert.equal(supportsLang("py"), true);
  assert.equal(supportsLang("rs"), true);
  assert.equal(supportsLang(""), false);
});

test("highlightInto 用 textContent 落 DOM，不产生 HTML 标签", () => {
  const box = document.createElement("code");
  highlightInto(box, '<script>alert(1)</script> const a = 1;', "js");
  assert.equal(box.querySelector("script"), null);
  assert.ok(box.textContent?.includes("<script>"));
  assert.ok(box.querySelectorAll("span.tok").length > 0);
  assert.equal(box.textContent, '<script>alert(1)</script> const a = 1;');
});
