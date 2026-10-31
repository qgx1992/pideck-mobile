/**
 * 把文本存成手机上的文件。
 *
 * 背景：桌面端的 `/api/file-content` 只给文本（见 ui/file-download-model.ts），
 * 所以这里也只处理文本 —— 但「保存」这一步必须落到用户能看见的地方：
 *
 *   - 原生（Android）：走自研插件 `FileSaver`，API 29+ 用 MediaStore 写进公共
 *     「下载/PiDeck」，系统文件管理器能直接看到；更老的系统退到应用专属目录
 *     （不需要任何存储权限）。
 *   - 浏览器 / 开发态：Blob + `<a download>`，走浏览器自己的下载。
 *
 * 为什么不加 @capacitor/filesystem：它的 Android 侧写的是应用专属目录，
 * 用户在文件管理器里找不到；为了「看得见」反而要再写一堆 MediaStore 代码。
 */

import { Capacitor, registerPlugin } from "@capacitor/core";
import { textToBase64 } from "../ui/file-download-model.ts";

interface FileSaverNative {
  save(options: { name: string; mime: string; data: string }): Promise<{ ok: boolean; location?: string }>;
}

const native = Capacitor.isNativePlatform() ? registerPlugin<FileSaverNative>("FileSaver") : null;

export interface SaveOutcome {
  ok: boolean;
  /** 保存位置（原生给「下载/PiDeck/文件名」，浏览器给「浏览器下载」） */
  location: string;
  /** ok=false 时的原因 */
  message: string;
}

/**
 * UTF-8 文本 → 标准 base64（不带换行）。
 *
 * 实现放在 ui/file-download-model.ts：那边是纯函数，node 测试能直接跑；
 * 这里只做转出，免得两个地方各写一份。
 */
export { textToBase64 };

/** 浏览器/WebView 兜底：造个 Blob 让浏览器自己下载。 */
function saveInBrowser(name: string, mime: string, text: string): SaveOutcome {
  const blob = new Blob([text], { type: mime });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = name;
  anchor.rel = "noopener";
  anchor.style.display = "none";
  (document.getElementById("app") ?? document.body).append(anchor);
  anchor.click();
  anchor.remove();
  // 立刻 revoke 会让部分浏览器拿到空文件，留 10 秒
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
  return { ok: true, location: "浏览器下载", message: "" };
}

/**
 * 保存文本文件。
 *
 * 失败不抛异常，统一返回 `{ ok:false, message }` —— 调用方一律 toast 出来，
 * 不需要 try/catch 套一圈。
 */
export async function saveTextFile(name: string, mime: string, text: string): Promise<SaveOutcome> {
  if (!native) return saveInBrowser(name, mime, text);
  try {
    const result = await native.save({ name, mime, data: textToBase64(text) });
    return { ok: true, location: result.location ?? "", message: "" };
  } catch (error) {
    const message =
      error instanceof Error ? error.message : typeof error === "string" ? error : "保存失败";
    return { ok: false, location: "", message };
  }
}
