/**
 * 图片压缩：把用户选的照片/截图变成能塞进 prompt 的 data URL。
 *
 * 为什么一定要压：手机随手一张 4000px 的照片 base64 后有十几 MB，
 * PiDeck 的 body 上限会直接 413，而用户只会看到「发送失败」。
 */

import {
  JPEG_QUALITY,
  MAX_EDGE,
  base64Payload,
  estimateBytes,
  fitWithin,
  mimeOfDataUrl,
} from "../ui/image-attach-model.ts";
import type { PendingImage } from "../ui/image-attach-model.ts";

type Bitmap = ImageBitmap | HTMLImageElement;

async function loadBitmap(file: File): Promise<Bitmap> {
  if (typeof createImageBitmap === "function") {
    try {
      return await createImageBitmap(file);
    } catch {
      // HEIC 之类解不开时退回 <img>
    }
  }
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve();
      image.onerror = () => reject(new Error("这张图片解不开（格式不支持？）"));
      image.src = url;
    });
    return image;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ""));
    reader.onerror = () => reject(new Error("读取图片失败"));
    reader.readAsDataURL(file);
  });
}

/**
 * 压一张图。
 *
 * GIF 不走 canvas（会丢动画），直接原样读成 data URL；
 * 其它格式统一转 JPEG——照片压缩率高，截图里的文字 0.82 也看得清。
 */
export async function compressImageFile(file: File, maxEdge = MAX_EDGE): Promise<PendingImage> {
  const name = file.name || "image";

  if (file.type === "image/gif") {
    const dataUrl = await readAsDataUrl(file);
    return { dataUrl, mimeType: "image/gif", width: 0, height: 0, bytes: estimateBytes(dataUrl), name };
  }

  const bitmap = await loadBitmap(file);
  const { width, height } = fitWithin(bitmap.width, bitmap.height, maxEdge);
  if (width === 0 || height === 0) throw new Error("图片尺寸为 0");

  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("这个环境不能处理图片（canvas 2d 不可用）");
  // 透明底统一铺白：JPEG 没有 alpha，否则会变成黑底
  context.fillStyle = "#ffffff";
  context.fillRect(0, 0, width, height);
  context.drawImage(bitmap as CanvasImageSource, 0, 0, width, height);

  const dataUrl = canvas.toDataURL("image/jpeg", JPEG_QUALITY);
  if (!dataUrl.startsWith("data:image/jpeg")) throw new Error("图片压缩失败");

  return {
    dataUrl,
    mimeType: mimeOfDataUrl(dataUrl),
    width,
    height,
    bytes: estimateBytes(dataUrl),
    name,
  };
}

/** 供测试/排查用：data URL 里的 base64 长度。 */
export function payloadLength(dataUrl: string): number {
  return base64Payload(dataUrl).length;
}
