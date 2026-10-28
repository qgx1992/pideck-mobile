/**
 * 主题：浅色 / 暗色 / 跟随系统。
 *
 * 三件事必须同时改，否则会出现「界面是深色但状态栏黑字看不见」这类问题：
 *   1. <html data-theme> → CSS 变量
 *   2. document.documentElement.style.colorScheme → 表单控件、滚动条
 *   3. 原生状态栏文字颜色 → @capacitor/status-bar
 * 另外 Android 侧把主题设成深色（见 styles.xml），系统就不会去绘制浅色状态栏。
 */

import { Capacitor } from "@capacitor/core";
import { StatusBar, Style } from "@capacitor/status-bar";
import { setNavigationBarColor } from "./navigation-bar.ts";

export type ThemeMode = "light" | "dark" | "system";

export interface ThemeColors {
  background: string;
  foreground: string;
}

const KEY = ["pideck", "app", "theme"].join("-");

/** 与 styles.css 的 --bg / --text 保持一致 */
const COLORS: Record<"light" | "dark", ThemeColors> = {
  dark: { background: "#18181b", foreground: "#e7e7ea" },
  light: { background: "#f7f7f9", foreground: "#1c1c22" },
};

let mode: ThemeMode = loadMode();
const listeners = new Set<(resolved: "light" | "dark") => void>();
let mediaQuery: MediaQueryList | null = null;

function loadMode(): ThemeMode {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw === "light" || raw === "dark" || raw === "system") return raw;
  } catch {
    /* ignore */
  }
  return "system";
}

export function getThemeMode(): ThemeMode {
  return mode;
}

export function resolveTheme(target: ThemeMode = mode): "light" | "dark" {
  if (target !== "system") return target;
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return "dark";
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

/** 应用主题到 DOM 与原生状态栏。 */
export function applyTheme(target: ThemeMode = mode): "light" | "dark" {
  const resolved = resolveTheme(target);

  const root = document.documentElement;
  root.dataset.theme = resolved;
  root.style.colorScheme = resolved;

  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", COLORS[resolved].background);

  void syncNativeBars(resolved);

  for (const listener of listeners) listener(resolved);
  return resolved;
}

export function setThemeMode(next: ThemeMode): "light" | "dark" {
  mode = next;
  try {
    localStorage.setItem(KEY, next);
  } catch {
    /* ignore */
  }
  return applyTheme(next);
}

export function onThemeChange(listener: (resolved: "light" | "dark") => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * 状态栏文字颜色：暗色主题用浅色字（Style.Dark），浅色主题用深色字（Style.Light）。
 * 命名容易搞反：Style.Dark = 「深色背景用的样式」= 浅色文字。
 */
/**
 * 同步原生顶部状态栏 + 底部导航栏。
 *
 * 两者是不同 API，必须都设，否则会出现「顶部对了底部还是白条」：
 *   - 状态栏：@capacitor/status-bar（Style.Dark = 深色背景配浅色字）
 *   - 导航栏：本项目自带插件 NavigationBar（见 platform/navigation-bar.ts）
 */
async function syncNativeBars(resolved: "light" | "dark"): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  const background = COLORS[resolved].background;
  // 深色主题 → 背景深、图标要浅
  const wantLightIcons = resolved === "dark";

  try {
    await StatusBar.setStyle({ style: wantLightIcons ? Style.Dark : Style.Light });
    if (Capacitor.getPlatform() === "android") {
      await StatusBar.setBackgroundColor({ color: background });
    }
    await StatusBar.setOverlaysWebView({ overlay: false });
  } catch (error) {
    // 状态栏插件不可用不应影响 App 启动
    console.warn("[theme] 状态栏同步失败", error);
  }

  await setNavigationBarColor(background, wantLightIcons);
}

/** 启动时调用一次：读偏好、监听系统主题变化。 */
export function initTheme(): void {
  applyTheme();

  if (typeof window !== "undefined" && typeof window.matchMedia === "function") {
    mediaQuery = window.matchMedia("(prefers-color-scheme: light)");
    const handler = (): void => {
      if (mode === "system") applyTheme();
    };
    if (typeof mediaQuery.addEventListener === "function") {
      mediaQuery.addEventListener("change", handler);
    } else {
      // 老 WebView 只有 addListener
      (mediaQuery as unknown as { addListener: (cb: () => void) => void }).addListener(handler);
    }
  }
}

export function themeLabel(target: ThemeMode): string {
  return target === "light" ? "浅色" : target === "dark" ? "深色" : "跟随系统";
}

export const THEME_MODES: ThemeMode[] = ["system", "light", "dark"];
