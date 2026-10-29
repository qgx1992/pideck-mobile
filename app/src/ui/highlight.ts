/**
 * 语法高亮（零依赖）。
 *
 * Web 端用 rehype-highlight（highlight.js 那套），我们不想为一个代码块引几十 KB，
 * 所以自己按语言写了个极简分词器：
 *   - 只区分「长什么样」：字符串、数字、注释、关键字、字面量、函数名、键名、标签/属性、diff 行
 *   - **强制不变式**：所有 token 的 text 拼起来必须和输入逐字节相等，
 *     这样流式渲染、复制、搜索都不会掉字符（有单测守着）
 *   - 输出 token 数组，由调用方决定怎么落 DOM（我们只塞 textContent，不拼 HTML）
 */

export type TokenKind =
  | "plain"
  | "comment"
  | "string"
  | "number"
  | "keyword"
  | "literal"
  | "function"
  | "key"
  | "tag"
  | "attr"
  | "add"
  | "del"
  | "meta";

export interface HighlightToken {
  text: string;
  kind: TokenKind;
}

interface LangConfig {
  /** 行注释前缀 */
  lineComments?: string[];
  /** 块注释 [开始, 结束] */
  blockComments?: Array<[string, string]>;
  /** 字符串引号字符 */
  quotes?: string[];
  /** 支持 Python 那种三引号 */
  tripleQuotes?: boolean;
  keywords?: string[];
  literals?: string[];
  /** SQL 之类大小写不敏感 */
  caseInsensitive?: boolean;
  /** JSON：紧跟着冒号的字符串标记为 key */
  jsonKeys?: boolean;
  /** 差异补丁：按行着色 */
  diff?: boolean;
  /** 类 HTML/XML 标签 */
  markup?: boolean;
}

const JS_KEYWORDS = [
  "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch", "case",
  "break", "continue", "new", "class", "extends", "super", "this", "try", "catch", "finally", "throw",
  "typeof", "instanceof", "in", "of", "await", "async", "yield", "import", "from", "export", "default",
  "static", "get", "set", "delete", "void", "interface", "type", "enum", "implements", "public",
  "private", "protected", "readonly", "as", "satisfies", "declare", "namespace", "abstract",
];

const PY_KEYWORDS = [
  "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue", "import",
  "from", "as", "with", "try", "except", "finally", "raise", "lambda", "yield", "await", "async",
  "pass", "global", "nonlocal", "assert", "del", "in", "is", "not", "and", "or", "self",
];

const SHELL_KEYWORDS = [
  "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
  "return", "local", "export", "readonly", "set", "unset", "shift", "source", "cd", "echo", "printf",
  "exit", "trap", "wait", "sudo", "git", "npm", "node", "python", "pip",
];

const SQL_KEYWORDS = [
  "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
  "table", "alter", "drop", "index", "join", "left", "right", "inner", "outer", "on", "group", "by",
  "order", "having", "limit", "offset", "as", "and", "or", "not", "null", "primary", "key",
  "foreign", "references", "distinct", "union", "case", "when", "then", "else", "end",
];

const GO_KEYWORDS = [
  "package", "import", "func", "return", "if", "else", "for", "range", "switch", "case", "default",
  "break", "continue", "go", "defer", "chan", "select", "type", "struct", "interface", "map", "var",
  "const", "nil", "make", "new",
];

const RUST_KEYWORDS = [
  "fn", "let", "mut", "pub", "use", "mod", "impl", "trait", "struct", "enum", "match", "if", "else",
  "for", "while", "loop", "return", "break", "continue", "where", "as", "in", "ref", "move", "async",
  "await", "dyn", "crate", "self", "super",
];

const JAVA_KEYWORDS = [
  "public", "private", "protected", "class", "interface", "extends", "implements", "static", "final",
  "void", "int", "long", "double", "float", "boolean", "char", "String", "new", "return", "if", "else",
  "for", "while", "switch", "case", "break", "continue", "try", "catch", "finally", "throw", "throws",
  "package", "import", "this", "super", "null",
];

const C_KEYWORDS = [
  "int", "char", "float", "double", "void", "long", "short", "unsigned", "signed", "struct", "union",
  "enum", "typedef", "static", "const", "return", "if", "else", "for", "while", "do", "switch", "case",
  "break", "continue", "goto", "sizeof", "include", "define", "class", "public", "private", "protected",
  "namespace", "using", "template", "auto", "bool", "true", "false", "nullptr",
];

const CONFIGS: Record<string, LangConfig> = {
  json: { quotes: ['"'], literals: ["true", "false", "null"], jsonKeys: true },
  jsonc: { quotes: ['"'], literals: ["true", "false", "null"], jsonKeys: true, lineComments: ["//"], blockComments: [["/*", "*/"]] },
  js: {
    quotes: ['"', "'", "`"],
    lineComments: ["//"],
    blockComments: [["/*", "*/"]],
    keywords: JS_KEYWORDS,
    literals: ["true", "false", "null", "undefined", "NaN", "Infinity"],
  },
  python: {
    quotes: ['"', "'"],
    tripleQuotes: true,
    lineComments: ["#"],
    keywords: PY_KEYWORDS,
    literals: ["True", "False", "None"],
  },
  shell: {
    quotes: ['"', "'"],
    lineComments: ["#"],
    keywords: SHELL_KEYWORDS,
    literals: ["true", "false"],
  },
  sql: {
    quotes: ["'", '"'],
    lineComments: ["--"],
    blockComments: [["/*", "*/"]],
    keywords: SQL_KEYWORDS,
    literals: ["null", "true", "false"],
    caseInsensitive: true,
  },
  go: {
    quotes: ['"', "`"],
    lineComments: ["//"],
    blockComments: [["/*", "*/"]],
    keywords: GO_KEYWORDS,
    literals: ["true", "false", "nil", "iota"],
  },
  rust: {
    quotes: ['"'],
    lineComments: ["//"],
    blockComments: [["/*", "*/"]],
    keywords: RUST_KEYWORDS,
    literals: ["true", "false", "None", "Some", "Ok", "Err"],
  },
  java: {
    quotes: ['"', "'"],
    lineComments: ["//"],
    blockComments: [["/*", "*/"]],
    keywords: JAVA_KEYWORDS,
    literals: ["true", "false", "null"],
  },
  c: {
    quotes: ['"', "'"],
    lineComments: ["//"],
    blockComments: [["/*", "*/"]],
    keywords: C_KEYWORDS,
    literals: ["true", "false", "nullptr", "NULL"],
  },
  diff: { diff: true },
  html: { markup: true },
  xml: { markup: true },
  svg: { markup: true },
  vue: { markup: true },
  text: {},
};

const JS_LIKE = CONFIGS.js as LangConfig;
CONFIGS.ts = JS_LIKE;
CONFIGS.tsx = JS_LIKE;
CONFIGS.jsx = JS_LIKE;
CONFIGS.javascript = JS_LIKE;
CONFIGS.typescript = JS_LIKE;
CONFIGS.py = CONFIGS.python as LangConfig;
CONFIGS.sh = CONFIGS.shell as LangConfig;
CONFIGS.bash = CONFIGS.shell as LangConfig;
CONFIGS.zsh = CONFIGS.shell as LangConfig;
CONFIGS.console = CONFIGS.shell as LangConfig;
CONFIGS.rs = CONFIGS.rust as LangConfig;
CONFIGS.golang = CONFIGS.go as LangConfig;
CONFIGS.cpp = CONFIGS.c as LangConfig;
CONFIGS.csharp = CONFIGS.java as LangConfig;
CONFIGS.cs = CONFIGS.java as LangConfig;
CONFIGS.kt = CONFIGS.java as LangConfig;
CONFIGS.patch = CONFIGS.diff as LangConfig;

/** 把 ```lang 的写法收敛成配置键（去大小写、去别名）。 */
export function normalizeLang(lang: string): string {
  const key = lang.trim().toLowerCase().replace(/^language-/, "");
  if (!key) return "text";
  return CONFIGS[key] ? key : "text";
}

/** 是否为受支持语言（否则按纯文本渲染）。 */
export function supportsLang(lang: string): boolean {
  const key = normalizeLang(lang);
  return CONFIGS[key] !== undefined && key !== "text";
}

const IDENT_START = /[A-Za-z_$@]/;
const IDENT_PART = /[A-Za-z0-9_$]/;
const DIGIT = /[0-9]/;
const NUMBER_RE = /^(?:0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?)/;

/** 差异补丁按行着色：+ 新增、- 删除、@@ 位置、其它上下文。 */
function highlightDiff(code: string): HighlightToken[] {
  const tokens: HighlightToken[] = [];
  const lines = code.split("\n");
  lines.forEach((line, index) => {
    let kind: TokenKind = "plain";
    if (line.startsWith("+")) kind = "add";
    else if (line.startsWith("-")) kind = "del";
    else if (line.startsWith("@@")) kind = "meta";
    else if (line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("+++") || line.startsWith("---")) {
      kind = "meta";
    }
    tokens.push({ text: line, kind });
    if (index < lines.length - 1) tokens.push({ text: "\n", kind: "plain" });
  });
  return tokens;
}

/** HTML/XML：注释、标签、属性名、属性值。 */
function highlightMarkup(code: string): HighlightToken[] {
  const tokens: HighlightToken[] = [];
  let plain = "";
  const flushPlain = (): void => {
    if (plain) {
      tokens.push({ text: plain, kind: "plain" });
      plain = "";
    }
  };

  let i = 0;
  while (i < code.length) {
    if (code.startsWith("<!--", i)) {
      const end = code.indexOf("-->", i + 4);
      const stop = end === -1 ? code.length : end + 3;
      flushPlain();
      tokens.push({ text: code.slice(i, stop), kind: "comment" });
      i = stop;
      continue;
    }

    const ch = code[i] ?? "";
    if (ch === "<") {
      // 整个尖括号段拆成：标签名 / 属性名 / 字符串 / 其它
      const tagEnd = code.indexOf(">", i);
      const stop = tagEnd === -1 ? code.length : tagEnd + 1;
      const segment = code.slice(i, stop);
      flushPlain();
      const tagMatch = /^<\/?([A-Za-z][\w:-]*)/.exec(segment);
      let cursor = 0;
      if (tagMatch) {
        tokens.push({ text: segment.slice(0, 1 + (segment[1] === "/" ? 1 : 0)), kind: "plain" });
        tokens.push({ text: tagMatch[1] ?? "", kind: "tag" });
        cursor = tagMatch[0].length;
      }
      while (cursor < segment.length) {
        const rest = segment.slice(cursor);
        const stringMatch = /^"[^"]*"|^'[^']*'/.exec(rest);
        if (stringMatch) {
          tokens.push({ text: stringMatch[0], kind: "string" });
          cursor += stringMatch[0].length;
          continue;
        }
        const attrMatch = /^[A-Za-z_:][\w:.-]*/.exec(rest);
        if (attrMatch) {
          tokens.push({ text: attrMatch[0], kind: "attr" });
          cursor += attrMatch[0].length;
          continue;
        }
        tokens.push({ text: rest[0] ?? "", kind: "plain" });
        cursor += 1;
      }
      i = stop;
      continue;
    }

    plain += ch;
    i += 1;
  }
  flushPlain();
  return tokens;
}

/**
 * 分词。返回的 token 文本拼起来 === 输入（有单测校验）。
 */
export function highlight(code: string, lang: string): HighlightToken[] {
  const key = normalizeLang(lang);
  const config = CONFIGS[key] ?? CONFIGS.text;
  if (!config) return [{ text: code, kind: "plain" }];
  if (config.diff) return highlightDiff(code);
  if (config.markup) return highlightMarkup(code);

  // 没有语言标注（或语言未知）就整段原样输出：
  // 别把普通文字里的 "foo(" 当成函数名染色
  const hasRules =
    (config.keywords?.length ?? 0) > 0 ||
    (config.quotes?.length ?? 0) > 0 ||
    (config.lineComments?.length ?? 0) > 0 ||
    (config.blockComments?.length ?? 0) > 0 ||
    (config.literals?.length ?? 0) > 0;
  if (!hasRules) return [{ text: code, kind: "plain" }];

  const keywords = new Set(
    (config.keywords ?? []).map((word) => (config.caseInsensitive ? word.toLowerCase() : word)),
  );
  const literals = new Set(
    (config.literals ?? []).map((word) => (config.caseInsensitive ? word.toLowerCase() : word)),
  );
  const quotes = new Set(config.quotes ?? []);
  const lineComments = (config.lineComments ?? []).slice().sort((a, b) => b.length - a.length);
  const blockComments = config.blockComments ?? [];

  const tokens: HighlightToken[] = [];
  let plain = "";
  const flushPlain = (): void => {
    if (plain) {
      tokens.push({ text: plain, kind: "plain" });
      plain = "";
    }
  };
  const push = (text: string, kind: TokenKind): void => {
    flushPlain();
    tokens.push({ text, kind });
  };

  let i = 0;
  while (i < code.length) {
    const rest = code.slice(i);

    // 注释
    const lineComment = lineComments.find((marker) => rest.startsWith(marker));
    if (lineComment) {
      const newline = code.indexOf("\n", i);
      const stop = newline === -1 ? code.length : newline;
      push(code.slice(i, stop), "comment");
      i = stop;
      continue;
    }

    const blockComment = blockComments.find(([start]) => rest.startsWith(start));
    if (blockComment) {
      const [start, end] = blockComment;
      const close = code.indexOf(end, i + start.length);
      const stop = close === -1 ? code.length : close + end.length;
      push(code.slice(i, stop), "comment");
      i = stop;
      continue;
    }

    // 字符串
    const quote = rest[0] ?? "";
    if (quotes.has(quote)) {
      // Python 三引号
      if (config.tripleQuotes && rest.startsWith(quote.repeat(3))) {
        const marker = quote.repeat(3);
        const close = code.indexOf(marker, i + 3);
        const stop = close === -1 ? code.length : close + 3;
        push(code.slice(i, stop), "string");
        i = stop;
        continue;
      }
      let j = i + 1;
      while (j < code.length) {
        const ch = code[j] ?? "";
        if (ch === "\\") {
          j += 2;
          continue;
        }
        if (ch === quote) {
          j += 1;
          break;
        }
        // 单行字符串不跨行（模板字符串除外）
        if (ch === "\n" && quote !== "`") break;
        j += 1;
      }
      const text = code.slice(i, j);
      // JSON：字符串后面跟冒号的是键
      if (config.jsonKeys) {
        let k = j;
        while (k < code.length && /\s/.test(code[k] ?? "")) k += 1;
        push(text, code[k] === ":" ? "key" : "string");
      } else {
        push(text, "string");
      }
      i = j;
      continue;
    }

    // 数字（十六进制 / 八进制 / 小数 / 指数）
    if (DIGIT.test(quote)) {
      const numberMatch = NUMBER_RE.exec(rest);
      const text = numberMatch?.[0] ?? quote;
      push(text, "number");
      i += text.length;
      continue;
    }

    // 标识符
    if (IDENT_START.test(quote)) {
      let j = i + 1;
      while (j < code.length && IDENT_PART.test(code[j] ?? "")) j += 1;
      const word = code.slice(i, j);
      const lookup = config.caseInsensitive ? word.toLowerCase() : word;

      if (keywords.has(lookup)) {
        push(word, "keyword");
      } else if (literals.has(lookup)) {
        push(word, "literal");
      } else {
        // 后面紧跟括号的当成函数名
        let k = j;
        while (k < code.length && (code[k] === " " || code[k] === "\t")) k += 1;
        push(word, code[k] === "(" ? "function" : "plain");
      }
      i = j;
      continue;
    }

    plain += quote;
    i += 1;
  }

  flushPlain();
  return tokens;
}

/** 渲染到容器（只用 textContent，绝不拼 HTML）。 */
export function highlightInto(container: HTMLElement, code: string, lang: string): void {
  container.textContent = "";
  const tokens = highlight(code, lang);
  for (const token of tokens) {
    if (token.kind === "plain") {
      container.append(container.ownerDocument.createTextNode(token.text));
      continue;
    }
    const span = container.ownerDocument.createElement("span");
    span.className = `tok tok-${token.kind}`;
    span.textContent = token.text;
    container.append(span);
  }
}
