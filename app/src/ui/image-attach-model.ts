/**
 * 图片附件与历史消息里的图片（纯逻辑，便于单测）。
 *
 * 为什么要单独一层：
 *   - 压缩参数（长边 / 质量 / 张数上限）是产品决策，散在 canvas 代码里没法测；
 *   - 历史消息里的图片形状随 PiDeck 版本变化（parts / content / images，
 *     type 可能是 image 也可能是 file），解析规则要能被回归覆盖。
 */

import type { ImageAttachment, WireMessage } from "@core/types.ts";

/** 一条消息最多带几张图（桌面端 Web Composer 同量级） */
export const MAX_IMAGES = 4;
/** 压缩后长边上限：够看清文字，又不会把 prompt 撑爆 */
export const MAX_EDGE = 1600;
/** JPEG 质量：0.82 在截图上几乎无损 */
export const JPEG_QUALITY = 0.82;

export interface PendingImage {
  /** 完整 data URL（含 `data:image/jpeg;base64,` 前缀），用于预览与发送 */
  dataUrl: string;
  mimeType: string;
  width: number;
  height: number;
  /** base64 解码后的字节数，用来提示体积 */
  bytes: number;
  name?: string;
}

/** 等比缩到长边不超过 maxEdge；本来就更小则原样返回。 */
export function fitWithin(
  width: number,
  height: number,
  maxEdge = MAX_EDGE,
): { width: number; height: number } {
  const longest = Math.max(width, height);
  if (!Number.isFinite(longest) || longest <= 0) return { width: 0, height: 0 };
  if (longest <= maxEdge) return { width: Math.round(width), height: Math.round(height) };
  const scale = maxEdge / longest;
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

/** `data:image/png;base64,AAA` → `AAA` */
export function base64Payload(dataUrl: string): string {
  const index = dataUrl.indexOf(",");
  return index >= 0 ? dataUrl.slice(index + 1) : dataUrl;
}

export function mimeOfDataUrl(dataUrl: string): string {
  const match = /^data:([^;,]+)[;,]/.exec(dataUrl);
  return match?.[1] ?? "image/jpeg";
}

export function isImageDataUrl(value: unknown): value is string {
  return typeof value === "string" && /^data:image\/[a-z0-9.+-]+;base64,/i.test(value);
}

/** base64 长度 → 字节数（4 字符 3 字节，尾部有 padding） */
export function estimateBytes(dataUrl: string): number {
  const payload = base64Payload(dataUrl);
  const padding = payload.endsWith("==") ? 2 : payload.endsWith("=") ? 1 : 0;
  return Math.max(0, Math.floor((payload.length * 3) / 4) - padding);
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function canAddImage(current: number): boolean {
  return current < MAX_IMAGES;
}

/** 发给服务端的形状（与桌面端 SendPromptInput.images 对齐）。 */
export function toImageAttachment(image: PendingImage): ImageAttachment {
  return { data: base64Payload(image.dataUrl), mimeType: image.mimeType };
}

function pushSource(out: string[], value: unknown, mimeHint?: string): void {
  if (isImageDataUrl(value)) {
    out.push(value);
    return;
  }
  // 裸 base64 + 旁边的 mediaType
  if (typeof value === "string" && value.length > 64 && mimeHint?.startsWith("image/") && !value.startsWith("data:")) {
    out.push(`data:${mimeHint};base64,${value}`);
  }
}

/**
 * 从历史消息里挖出图片的 data URL。
 *
 * 覆盖的三种形状（都实测/文档见过）：
 *   { parts: [{ type: "image", data: "data:image/png;base64,…" }] }
 *   { parts: [{ type: "file", mediaType: "image/png", url: "data:…" | 裸 base64 }] }
 *   { images: [{ data: "…" | url: "…", mimeType: "image/png" }] }
 */
export function collectImageSources(message: WireMessage): string[] {
  const out: string[] = [];
  const visit = (value: unknown, depth = 0): void => {
    if (depth > 4 || value === null || value === undefined) return;
    if (typeof value === "string") {
      pushSource(out, value);
      return;
    }
    if (Array.isArray(value)) {
      for (const item of value) visit(item, depth + 1);
      return;
    }
    if (typeof value !== "object") return;
    const record = value as Record<string, unknown>;
    const type = typeof record.type === "string" ? record.type : "";
    const mime =
      (typeof record.mediaType === "string" ? record.mediaType : "") ||
      (typeof record.mimeType === "string" ? record.mimeType : "") ||
      (typeof record.mime === "string" ? record.mime : "");

    // 只对图片/文件类节点取载荷，避免把 tool 的 input 里的字符串当图片
    const looksImage = type === "image" || type === "file" || type === "" || mime.startsWith("image/");
    if (looksImage) {
      pushSource(out, record.url, mime);
      pushSource(out, record.data, mime);
      pushSource(out, record.image, mime);
      pushSource(out, record.source, mime);
      if (record.source && typeof record.source === "object") {
        const source = record.source as Record<string, unknown>;
        pushSource(out, source.data, typeof source.mediaType === "string" ? source.mediaType : mime);
        pushSource(out, source.url, mime);
      }
    }
    for (const key of ["parts", "content", "images", "attachments"]) {
      if (record[key] !== undefined) visit(record[key], depth + 1);
    }
  };

  visit(message.parts, 0);
  visit((message as { images?: unknown }).images, 0);
  if (out.length === 0) visit(message.content, 0);
  // 去重：回放与历史可能带同一张
  return [...new Set(out)];
}
