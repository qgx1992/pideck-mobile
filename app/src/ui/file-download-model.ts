/**
 * 「把文件下载到手机」的纯逻辑。
 *
 * 能不能下载这件事**不由客户端决定**：桌面端的 `/api/file-content` 只会吐文本
 * （二进制返回 `{binary:true}`，超 2MB 只给前 512KB），所以这里唯一要做的是
 * 把「能下 / 下不了 / 下了会坏」判断清楚，别让用户存到一个半截文件。
 */

import type { FileContentResult } from "@core/api/client.ts";

/** 路径最后一段（兼容 Windows 与 POSIX 分隔符）。 */
export function baseName(path: string): string {
  const trimmed = path.replace(/[\\/]+$/, "");
  const index = Math.max(trimmed.lastIndexOf("/"), trimmed.lastIndexOf("\\"));
  const name = index >= 0 ? trimmed.slice(index + 1) : trimmed;
  return name || "文件";
}

/**
 * 保存时用的 MIME。Android 只拿它决定「下载/查看」的归属应用，
 * 所以除少数会明确影响行为的类型外，一律给 text/plain 最稳。
 */
export function mimeForName(name: string): string {
  const dot = name.lastIndexOf(".");
  const ext = dot > 0 ? name.slice(dot + 1).toLowerCase() : "";
  switch (ext) {
    case "md":
    case "markdown":
      return "text/markdown";
    case "json":
      return "application/json";
    case "jsonl":
      return "application/x-ndjson";
    case "yaml":
    case "yml":
      return "application/yaml";
    case "toml":
      return "application/toml";
    case "csv":
      return "text/csv";
    case "html":
    case "htm":
      return "text/html";
    case "css":
      return "text/css";
    case "xml":
      return "application/xml";
    case "svg":
      return "image/svg+xml";
    case "js":
    case "mjs":
    case "cjs":
      return "text/javascript";
    case "pdf":
      return "application/pdf";
    default:
      return "text/plain";
  }
}

/** 人类可读的字节数（1.2 KB / 3.4 MB）。 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * 这次「能下吗」。
 *
 * 返回 null 表示可以直接下（此时 `result.content` 就是文件全量文本），
 * 返回字符串表示要给用户看的原因 —— 一句能读懂的话，不要带错误码。
 */
export function downloadBlockedReason(
  result: Pick<FileContentResult, "kind" | "size">,
  name: string,
): string | null {
  switch (result.kind) {
    case "binary":
      return `${name} 是二进制文件，桌面端的文件接口只提供文本，下载不了`;
    case "truncated":
      return `${name} 有 ${formatBytes(result.size)}，接口只给前 512 KB，下载下来是半截，先别下`;
    case "tooLarge":
      return `${name} 有 ${formatBytes(result.size)}，超过 2 MB 读不全，下载不了`;
    case "text":
      return null;
  }
}

/** 保存成功后的提示语。 */
export function downloadDoneText(name: string, location: string): string {
  return location ? `已保存 ${name} 到 ${location}` : `已保存 ${name}`;
}

/**
 * UTF-8 文本 → 标准 base64（不带换行）。
 *
 * 不能直接 `btoa(text)`：它按 latin1 处理，中文会炸。先 TextEncoder 转字节，
 * 再按 32KB 分块喂 `String.fromCharCode` —— 一次性展开大数组会爆栈。
 * 放在这里（而不是 platform/）是为了能被 node 测到：`save-file.ts` 依赖 Capacitor，
 * 在 node 里根本 import 不了。
 */
export function textToBase64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  const chunk = 0x8000;
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += chunk) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunk));
  }
  return btoa(binary);
}
