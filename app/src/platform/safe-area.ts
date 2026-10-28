/**
 * 安全区处理。
 *
 * 关键事实：本项目在 styles.xml 里 opt-out 了 Android 15 的强制 edge-to-edge，
 * 并且调用了 setOverlaysWebView(false) —— 也就是说 WebView **不在**状态栏/导航栏下面。
 * 此时 WebView 里的 env(safe-area-inset-top) 返回的是「屏幕挖孔高度」而不是状态栏高度，
 * 在刘海屏上会多出一截空白（顶部 header、侧栏顶端都会受影响）。
 *
 * 所以：Android 下把安全区直接归零；iOS 仍用 env()（iOS 是全屏 WebView，需要它）。
 * 浏览器里保持 env()（桌面浏览器通常为 0，无害）。
 */

import { Capacitor } from "@capacitor/core";

export type SafeAreaPlatform = "android" | "ios" | "web";

export function detectPlatform(): SafeAreaPlatform {
  if (!Capacitor.isNativePlatform()) return "web";
  const platform = Capacitor.getPlatform();
  return platform === "android" ? "android" : "ios";
}

/**
 * 把平台写到 <html data-platform>，由 CSS 决定是否使用 env()。
 * 必须在首屏渲染前调用，避免先多一截空白再跳回。
 */
export function initSafeArea(): SafeAreaPlatform {
  const platform = detectPlatform();
  document.documentElement.dataset.platform = platform;
  return platform;
}
