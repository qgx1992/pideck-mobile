/**
 * 极简 Markdown 渲染（零依赖）。
 *
 * 为什么需要：PiDeck 的回答是 Markdown（Web 端用 react-markdown + remark-gfm +
 * remarkBreaks + 代码高亮渲染），而手机端之前直接把 block.text 塞进 textContent，
 * 于是 **粗体**、```json 代码块、列表、表格全变成纯文本。
 *
 * 设计取舍：
 *   - 只认回答里真正会出现的语法：标题、粗/斜/删除线、行内代码、围栏代码块、
 *     有序/无序/任务列表、引用、分隔线、表格、链接、单换行（对齐 remarkBreaks）
 *   - **全程用 createElement + textContent 组 DOM**，不拼 innerHTML，避免 XSS
 *   - 链接只放行 http/https/mailto 和站内相对路径，`javascript:` 之类直接降级成纯文本
 *   - 流式渲染要能扛半截输入：未闭合的围栏当代码块吃到最后
 */

import { highlightInto } from "./highlight.ts";

export interface MarkdownOptions {
  /** 点击链接时的处理（默认交给系统浏览器 / WebView） */
  onLinkClick?: (href: string) => void;
  /** 代码块「复制」按钮，默认用剪贴板 API */
  onCopy?: (code: string) => void;
}

/** 判断链接是否安全（只放行常见协议与站内相对路径）。 */
export function isSafeHref(href: string): boolean {
  const value = href.trim();
  if (!value) return false;
  if (value.startsWith("#") || value.startsWith("/") || value.startsWith("./") || value.startsWith("../")) {
    return true;
  }
  return /^(https?:|mailto:)/i.test(value);
}

function el(tag: string, className?: string): HTMLElement {
  const node = document.createElement(tag);
  if (className) node.className = className;
  return node;
}

/* ------------------------------------------------------------------ */
/* 块级解析                                                            */
/* ------------------------------------------------------------------ */

const RE_FENCE = /^(\s*)(`{3,}|~{3,})\s*([\w+#.-]*)/;
const RE_HEADING = /^(#{1,6})\s+(.*)$/;
const RE_HR = /^\s*([-*_])(?:\s*\1){2,}\s*$/;
const RE_QUOTE = /^\s*>\s?/;
const RE_LIST = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;
const RE_TABLE_SEP = /^\s*\|?[\s:-]*-[\s|:-]*\|?\s*$/;

function isBlockStart(line: string): boolean {
  return (
    RE_FENCE.test(line) ||
    RE_HEADING.test(line) ||
    RE_HR.test(line) ||
    RE_QUOTE.test(line) ||
    RE_LIST.test(line)
  );
}

/** 渲染一段 Markdown，返回可直接 append 的片段。 */
export function renderMarkdown(text: string, options: MarkdownOptions = {}): DocumentFragment {
  const fragment = document.createDocumentFragment();
  const lines = text.replace(/\r\n?/g, "\n").split("\n");

  let index = 0;
  while (index < lines.length) {
    const line = lines[index] ?? "";

    if (!line.trim()) {
      index += 1;
      continue;
    }

    // 围栏代码块
    const fence = RE_FENCE.exec(line);
    if (fence) {
      const [, , marker, lang] = fence;
      const fenceChar = marker?.[0] ?? "`";
      const fenceLen = marker?.length ?? 3;
      const body: string[] = [];
      index += 1;
      while (index < lines.length) {
        const current = lines[index] ?? "";
        const closing = new RegExp(`^\\s*${fenceChar === "`" ? "`" : "~"}{${fenceLen},}\\s*$`);
        if (closing.test(current)) {
          index += 1;
          break;
        }
        body.push(current);
        index += 1;
      }
      fragment.append(codeBlock(body.join("\n"), lang ?? "", options));
      continue;
    }

    // 标题
    const heading = RE_HEADING.exec(line);
    if (heading) {
      const level = Math.min(6, Math.max(1, heading[1]?.length ?? 1));
      const node = el(`h${level}`, `md-h md-h${level}`);
      appendInline(node, heading[2] ?? "", options);
      fragment.append(node);
      index += 1;
      continue;
    }

    // 分隔线
    if (RE_HR.test(line)) {
      fragment.append(el("hr", "md-hr"));
      index += 1;
      continue;
    }

    // 引用：连续 > 行合并，内部递归渲染
    if (RE_QUOTE.test(line)) {
      const body: string[] = [];
      while (index < lines.length && RE_QUOTE.test(lines[index] ?? "")) {
        body.push((lines[index] ?? "").replace(RE_QUOTE, ""));
        index += 1;
      }
      const quote = el("blockquote", "md-quote");
      quote.append(renderMarkdown(body.join("\n"), options));
      fragment.append(quote);
      continue;
    }

    // 表格：表头 + 分隔行
    if (line.includes("|") && RE_TABLE_SEP.test(lines[index + 1] ?? "")) {
      const header = splitRow(line);
      const rows: string[][] = [];
      index += 2;
      while (index < lines.length && (lines[index] ?? "").includes("|")) {
        const current = lines[index] ?? "";
        if (!current.trim()) break;
        rows.push(splitRow(current));
        index += 1;
      }
      fragment.append(table(header, rows, options));
      continue;
    }

    // 列表
    if (RE_LIST.test(line)) {
      const result = list(lines, index, options);
      fragment.append(result.node);
      index = result.next;
      continue;
    }

    // 段落：吃到空行或下一个块起点
    const paragraph: string[] = [];
    while (index < lines.length) {
      const current = lines[index] ?? "";
      if (!current.trim()) break;
      if (paragraph.length > 0 && isBlockStart(current)) break;
      paragraph.push(current);
      index += 1;
    }
    const para = el("p", "md-p");
    appendInline(para, paragraph.join("\n"), options);
    fragment.append(para);
  }

  return fragment;
}

/** 表格单元格按 | 切分，去掉首尾空列（行首行尾的竖线）。 */
function splitRow(line: string): string[] {
  let trimmed = line.trim();
  if (trimmed.startsWith("|")) trimmed = trimmed.slice(1);
  if (trimmed.endsWith("|")) trimmed = trimmed.slice(0, -1);
  return trimmed.split("|").map((cell) => cell.trim());
}

function table(header: string[], rows: string[][], options: MarkdownOptions): HTMLElement {
  const wrap = el("div", "md-table-wrap");
  const node = el("table", "md-table");
  const thead = el("thead");
  const headRow = el("tr");
  for (const cell of header) {
    const th = el("th");
    appendInline(th, cell, options);
    headRow.append(th);
  }
  thead.append(headRow);
  node.append(thead);

  const tbody = el("tbody");
  for (const row of rows) {
    const tr = el("tr");
    for (let i = 0; i < header.length; i += 1) {
      const td = el("td");
      appendInline(td, row[i] ?? "", options);
      tr.append(td);
    }
    tbody.append(tr);
  }
  node.append(tbody);
  wrap.append(node);
  return wrap;
}

/** 列表：按缩进支持一层嵌套，任务列表渲染成勾选框。 */
function list(
  lines: string[],
  start: number,
  options: MarkdownOptions,
): { node: HTMLElement; next: number } {
  const first = RE_LIST.exec(lines[start] ?? "");
  const ordered = /\d/.test(first?.[2] ?? "");
  const root = el(ordered ? "ol" : "ul", "md-list");
  const baseIndent = (first?.[1] ?? "").length;

  let index = start;
  let currentLi: HTMLElement | null = null;
  let nested: HTMLElement | null = null;

  while (index < lines.length) {
    const line = lines[index] ?? "";
    if (!line.trim()) {
      // 空行后面如果还是列表项就继续，否则结束
      const next = lines[index + 1] ?? "";
      if (!RE_LIST.test(next)) break;
      index += 1;
      continue;
    }

    const match = RE_LIST.exec(line);
    if (!match) break;

    const indent = (match[1] ?? "").length;
    const content = match[3] ?? "";
    // 有序 / 无序混排时另起一个列表（否则 1. 2. 会被吸进上面的 ul）
    if (/\d/.test(match[2] ?? "") !== ordered) break;

    if (indent >= baseIndent + 2 && currentLi) {
      // 嵌套一层
      if (!nested) {
        nested = el(/\d/.test(match[2] ?? "") ? "ol" : "ul", "md-list md-list--nested");
        currentLi.append(nested);
      }
      const nestedItem = el("li");
      appendInline(nestedItem, content, options);
      nested.append(nestedItem);
      index += 1;
      continue;
    }
    if (indent < baseIndent) break;

    nested = null;
    currentLi = el("li");
    appendInline(currentLi, content, options);
    const task = /^\[([ xX])\]\s+([\s\S]*)$/.exec(currentLi.textContent ?? "");
    if (!ordered && task) {
      const checked = task[1]?.toLowerCase() === "x";
      currentLi.textContent = "";
      const box = el("input", "md-task");
      box.setAttribute("type", "checkbox");
      if (checked) box.setAttribute("checked", "");
      box.setAttribute("disabled", "");
      currentLi.append(box, document.createTextNode(" "));
      appendInline(currentLi, task[2] ?? "", options);
      currentLi.classList.add("md-li--task");
    }
    root.append(currentLi);
    index += 1;
  }

  return { node: root, next: index };
}

function codeBlock(code: string, lang: string, options: MarkdownOptions): HTMLElement {
  const wrap = el("div", "md-codeblock");
  const head = el("div", "md-codehead");
  head.append(el("span", "md-codelang"));
  (head.firstChild as HTMLElement).textContent = lang || "code";

  const copy = el("button", "md-copy");
  copy.setAttribute("type", "button");
  copy.textContent = "复制";
  copy.addEventListener("click", () => {
    if (options.onCopy) {
      options.onCopy(code);
    } else {
      void navigator.clipboard?.writeText(code).catch(() => undefined);
    }
    copy.textContent = "已复制";
    window.setTimeout(() => {
      copy.textContent = "复制";
    }, 1_200);
  });
  head.append(copy);

  const pre = el("pre", "md-pre");
  const codeEl = el("code", "md-code");
  codeEl.setAttribute("data-lang", lang || "text");
  // 语法高亮：受支持的语言按 token 上色，其余原样输出
  highlightInto(codeEl, code, lang);
  pre.append(codeEl);

  wrap.append(head, pre);
  return wrap;
}

/* ------------------------------------------------------------------ */
/* 行内解析                                                            */
/* ------------------------------------------------------------------ */

/**
 * 行内语法：`code`、**粗**、*斜*、~~删除~~、[文字](链接)、<自动链接>、单换行→<br>。
 * 用正则逐个扫描，命中就切一段纯文本出来，保证不会有字符串拼 HTML。
 */
const RE_INLINE = new RegExp(
  [
    "(?<code>`+)(?<codeBody>[\\s\\S]*?)\\k<code>",
    "\\*\\*(?<bold>[\\s\\S]+?)\\*\\*",
    "__(?<bold2>[\\s\\S]+?)__",
    "~~(?<strike>[\\s\\S]+?)~~",
    "\\*(?<em>[^*\\n]+?)\\*",
    "_(?<em2>[^_\\n]+?)_",
    "\\[(?<linkText>[^\\]\\n]*)\\]\\((?<linkUrl>[^)\\s]+)\\)",
    "<(?<auto>https?:\\/\\/[^>\\s]+)>",
  ].join("|"),
  "g",
);

/** 把行内 Markdown 追加到容器里。 */
export function appendInline(
  container: HTMLElement,
  source: string,
  options: MarkdownOptions,
): void {

  const pushText = (value: string): void => {
    if (!value) return;
    // 单换行按 GFM 断行处理（Web 端开了 remarkBreaks）
    const parts = value.split("\n");
    parts.forEach((part, i) => {
      if (i > 0) container.append(el("br"));
      if (part) container.append(document.createTextNode(part));
    });
  };

  const regex = new RegExp(RE_INLINE.source, "g");
  let last = 0;
  let match = regex.exec(source);

  while (match) {
    pushText(source.slice(last, match.index));
    const m = (match.groups ?? {}) as Record<string, string | undefined>;

    if (m.codeBody !== undefined) {
      const code = el("code", "md-inline-code");
      code.textContent = m.codeBody;
      container.append(code);
    } else if (m.bold !== undefined || m.bold2 !== undefined) {
      const strong = el("strong");
      appendInline(strong, m.bold ?? m.bold2 ?? "", options);
      container.append(strong);
    } else if (m.strike !== undefined) {
      const s = el("del");
      appendInline(s, m.strike, options);
      container.append(s);
    } else if (m.em !== undefined || m.em2 !== undefined) {
      const em = el("em");
      appendInline(em, m.em ?? m.em2 ?? "", options);
      container.append(em);
    } else if (m.linkText !== undefined) {
      const href = m.linkUrl ?? "";
      if (isSafeHref(href)) {
        const a = el("a", "md-link") as HTMLAnchorElement;
        a.href = href;
        a.setAttribute("rel", "noreferrer noopener");
        if (/^https?:/i.test(href)) a.setAttribute("target", "_blank");
        appendInline(a, m.linkText, options);
        if (options.onLinkClick) {
          a.addEventListener("click", (event) => {
            event.preventDefault();
            options.onLinkClick?.(href);
          });
        }
        container.append(a);
      } else {
        pushText(m.linkText);
      }
    } else if (m.auto !== undefined) {
      const href = m.auto;
      const a = el("a", "md-link") as HTMLAnchorElement;
      a.href = href;
      a.setAttribute("rel", "noreferrer noopener");
      a.textContent = href;
      if (options.onLinkClick) {
        a.addEventListener("click", (event) => {
          event.preventDefault();
          options.onLinkClick?.(href);
        });
      }
      container.append(a);
    } else {
      pushText(match[0]);
    }

    last = match.index + match[0].length;
    match = regex.exec(source);
  }

  pushText(source.slice(last));
}

/** 渲染到独立容器（聊天正文用）。 */
export function renderMarkdownInto(
  container: HTMLElement,
  text: string,
  options: MarkdownOptions = {},
): void {
  container.textContent = "";
  container.append(renderMarkdown(text, options));
}

const RE_FENCE_ANY = /```[\s\S]*?```/g;

/** 只取纯文本（复制、搜索、通知摘要用）。 */
export function markdownToText(text: string): string {
  return text
    .replace(RE_FENCE_ANY, "")
    .replace(/`{1,3}/g, "")
    .replace(/\*\*|__|~~/g, "")
    .replace(/^\s*#{1,6}\s+/gm, "")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .trim();
}
