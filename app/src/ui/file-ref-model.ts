/**
 * 正文里的文件路径 → 可点击的引用。
 *
 * 为什么要有：助手回答里动辄出现 `src/api/client.ts:246` 这种路径，Web 端点一下就
 * 打开预览，手机端原来只能干看着去右侧抽屉里翻。
 *
 * 两个必须小心的点：
 *   1. **不能把 URL 当路径**：`https://github.com/a/b.md` 里 `github.com/a/b.md`
 *      长得很像项目文件，但它不是 —— 靠「前一个字符是 / 或 :」把这类排除。
 *   2. **不能假设文件存在**：客户端没法同步 stat，所以这里只做「长得像不像」，
 *      点下去再由 `/api/file-content` 决定，读不到就 toast 说没找到。
 */

export interface FileRef {
  /** 路径本身（保留原样，含 Windows 反斜杠） */
  path: string;
  /** 冒号后面的行号（`:12` 或 `:12:5`，只取行） */
  line?: number;
}

/**
 * 文件路径的扫描式（带一个「前导字符」捕获组，避免用 lookbehind 的兼容性问题）。
 *
 * 认的结构：`(盘符:)?(段/)+名字.扩展名(:行号)(:列号)?`
 * 要求「至少一层目录 + 真扩展名」，所以 `README.md`、`package.json` 这种裸文件名不认
 * —— 它们在正文里太常见，做成链接会满屏都是可点的东西。
 */
export const FILE_REF_RE =
  /(^|[^\w:/\\])((?:[A-Za-z]:[\\/])?(?:[\w.-]+[\\/])+[\w.-]+\.[A-Za-z][A-Za-z0-9]{0,7}(?::\d+){0,2})/g;

function looksLikePath(token: string): boolean {
  if (!token) return false;
  if (token.includes("://") || token.includes("//")) return false;
  // `..` 段在项目相对路径里没有意义，直接不当成文件引用
  if (/(^|[\\/])\.\.([\\/]|$)/.test(token)) return false;
  const segments = token.split(/[\\/]/).filter(Boolean);
  if (segments.length < 2) return false;
  const last = segments[segments.length - 1] ?? "";
  return /\.[A-Za-z]/.test(last);
}

/** 把 `a/b.ts:12:5` 拆成 `{ path: "a/b.ts", line: 12 }`；不像路径就返回 null。 */
export function splitFileRef(token: string): FileRef | null {
  const trimmed = token.trim();
  if (!looksLikePath(trimmed)) return null;
  const match = /^(.*?)(?::(\d+))(?::\d+)?$/.exec(trimmed);
  if (!match) return { path: trimmed };
  const path = match[1] ?? trimmed;
  if (!looksLikePath(path)) return null;
  const line = Number(match[2]);
  return Number.isFinite(line) && line > 0 ? { path, line } : { path };
}

export interface FileRefHit extends FileRef {
  /** 在原文里的起始下标 */
  index: number;
  /** 匹配到的原文长度 */
  length: number;
}

/** URL 的范围：路径链接化时必须绕开它们（`https://x.com/a/b.md` 很像项目文件） */
const RE_URL = /(?:https?|ftp):\/\/[^\s<>()"'\u3000-\u303f\uff00-\uffef]+/gi;

/** 扫一遍文本，返回所有像文件路径的片段（按出现顺序）。 */
export function findFileRefs(text: string): FileRefHit[] {
  const urlRanges: Array<[number, number]> = [];
  for (const match of text.matchAll(RE_URL)) {
    urlRanges.push([match.index ?? 0, (match.index ?? 0) + match[0].length]);
  }
  const inUrl = (start: number): boolean =>
    urlRanges.some(([from, to]) => start >= from && start < to);

  const hits: FileRefHit[] = [];
  const regex = new RegExp(FILE_REF_RE.source, "g");
  let match = regex.exec(text);
  while (match) {
    const lead = match[1] ?? "";
    const token = match[2] ?? "";
    const start = match.index + lead.length;
    const ref = splitFileRef(token);
    if (ref && !inUrl(start)) {
      hits.push({ ...ref, index: start, length: token.length });
    }
    match = regex.exec(text);
  }
  return hits;
}

/**
 * 绝对路径压成项目相对路径（`/api/file-content` 只认相对项目根的 path）。
 *
 * 压不掉就原样返回（把反斜杠统一成正斜杠）—— 服务端会自己拒绝，好过我们猜。
 */
export function toProjectRelative(path: string, projectRoot: string): string {
  const unified = path.replace(/\\/g, "/");
  const root = projectRoot.replace(/\\/g, "/").replace(/\/+$/, "");
  if (!root) return unified;
  if (unified.startsWith(`${root}/`)) return unified.slice(root.length + 1);
  // Windows 磁盘路径大小写不敏感；只在两边都是盘符路径时才忽略大小写，
  // 免得在 Linux/macOS 上把两个不同目录误认成同一个
  const isDrive = /^[A-Za-z]:\//.test(root) && /^[A-Za-z]:\//.test(unified);
  if (isDrive && unified.toLowerCase().startsWith(`${root.toLowerCase()}/`)) {
    return unified.slice(root.length + 1);
  }
  return unified.replace(/^\/+/, "");
}

/** 路径里的文件名（预览页标题用）。 */
export function fileNameOf(path: string): string {
  const parts = path.replace(/\\/g, "/").split("/").filter(Boolean);
  return parts[parts.length - 1] ?? path;
}

/** 路径里的目录（预览页返回列表用）。 */
export function dirStackOf(path: string): string[] {
  const parts = path.replace(/\\/g, "/").split("/").filter(Boolean);
  parts.pop();
  return parts;
}
