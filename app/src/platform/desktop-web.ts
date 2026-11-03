/**
 * 「用桌面网页版打开」（Capacitor 原生插件 `DesktopWeb` 的薄封装）。
 *
 * 对应原生：app/android/app/src/main/java/dev/pideck/mobile/DesktopWebPlugin.java
 * 承载页： {@link DesktopWebActivity}（裸 WebView，加载远程 origin 的页面）
 *
 * 为什么要有它：桌面端 0.9.9 自带了给浏览器用的 `web.html`（同一套 UI 的去 Electron 构建，
 * 已按手机走查：`viewport-fit=cover`、`env(safe-area-inset-*)`、`pointer:coarse`、
 * 760px 以下抽屉侧栏）。这一入口把那份 UI 原样搬进手机，用来替代「客户端自己再写一遍」。
 *
 * 只传 URL（含 `?token=`）——桌面端对 GET 认 `?token=`，页面自己会把它存进 localStorage。
 * 非 Android / 老包（没有这个插件）时静默失败，调用方先 `isDesktopWebAvailable()` 探一下。
 */

import { Capacitor, registerPlugin } from "@capacitor/core";

export interface DesktopWebBridge {
  open(options: { url: string; title?: string }): Promise<{ opened: boolean }>;
  available(): Promise<{ available: boolean }>;
}

const bridge = Capacitor.isNativePlatform() ? registerPlugin<DesktopWebBridge>("DesktopWeb") : null;

/** 当前安装包是否带这个原生插件（网页里调试时永远是 false）。 */
export async function isDesktopWebAvailable(): Promise<boolean> {
  if (!bridge) return false;
  try {
    const result = await bridge.available();
    return result?.available === true;
  } catch {
    return false;
  }
}

/**
 * 把「桌面端 origin + token」拼成网页版入口 URL。
 *
 * - 去掉 baseUrl 尾斜杠，避免 `//?token=`；
 * - token 用 `encodeURIComponent`：桌面端轮换出来的 token 可能是 UUID，也可能带 `-`/`_`；
 * - `desktop=1` 是给页面/我们自己的标记（桌面端不认识会忽略），方便日后排查是手机打开的。
 */
export function desktopWebUrl(baseUrl: string, token: string): string {
  const base = baseUrl.trim().replace(/\/+$/, "");
  const query = new URLSearchParams();
  if (token) query.set("token", token);
  query.set("desktop", "1");
  return `${base}/?${query.toString()}`;
}

/** 打开桌面网页版；返回是否真的启动了（浏览器里调用返回 false）。 */
export async function openDesktopWeb(options: {
  baseUrl: string;
  token: string;
  title?: string;
}): Promise<boolean> {
  if (!bridge) return false;
  const url = desktopWebUrl(options.baseUrl, options.token);
  try {
    const result = await bridge.open({ url, title: options.title });
    return result?.opened === true;
  } catch (error) {
    console.warn("[desktop-web] 打开失败", error);
    return false;
  }
}
