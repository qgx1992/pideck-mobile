/**
 * 系统栏（状态栏 / 底部手势条）跟随页面主题。
 *
 * 为什么要自己同步：上游客户端把主题写在 `document.documentElement.dataset.theme`
 * （`web-main.tsx:24`、`webTheme.ts:50`），WebView 之外的状态栏图标颜色由系统决定——
 * 切到浅色主题后状态栏还是浅色图标，白底白字直接看不见。
 *
 * 图标明暗两处口径不同，别弄反：
 *   - `StatusBar.setStyle({ style: Style.Dark })` = **浅色图标**（给深色背景用）
 *   - `NavigationBar.setColor({ lightIcons: true })` = **浅色图标**（给深色背景用）
 */

import { Capacitor, registerPlugin } from "@capacitor/core";
import { StatusBar, Style } from "@capacitor/status-bar";

interface NavigationBarPlugin {
	setColor(options: { color: string; lightIcons: boolean }): Promise<unknown>;
}

const NavigationBar = registerPlugin<NavigationBarPlugin>("NavigationBar");

const FALLBACK = { dark: "#121212", light: "#ffffff" };

/** `data-theme` 是唯一权威；没有就退回系统偏好。 */
export function isDarkTheme(): boolean {
	const theme = document.documentElement.dataset.theme;
	if (theme === "dark") return true;
	if (theme === "light") return false;
	return window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? true;
}

/** 主题底色：上游的 `--color-bg-app`（`foundation.css:96` 浅色 / `:585` 深色）。 */
export function themeBackground(dark = isDarkTheme()): string {
	const value = getComputedStyle(document.documentElement).getPropertyValue("--color-bg-app").trim();
	return value || (dark ? FALLBACK.dark : FALLBACK.light);
}

/** 按当前（或指定）主题刷一遍系统栏。非原生平台直接跳过。 */
export async function applySystemBars(forced?: "dark" | "light"): Promise<void> {
	if (!Capacitor.isNativePlatform()) return;
	const dark = forced ? forced === "dark" : isDarkTheme();
	const color = forced ? FALLBACK[forced] : themeBackground(dark);

	// 三个调用互不依赖，任何一个失败都不该挡住另外两个
	// （Android 15 强制 e2e 后 setBackgroundColor 会被忽略，但图标必须换）
	await Promise.allSettled([
		StatusBar.setStyle({ style: dark ? Style.Dark : Style.Light }),
		StatusBar.setBackgroundColor({ color }),
		NavigationBar.setColor({ color, lightIcons: dark }),
	]);
}

/**
 * 监听主题变化。上游切换主题只改 `data-theme`，所以盯属性就够了；
 * 顺带在回到前台时补一次（系统深浅色可能在后台变过）。
 */
export function startSystemBars(): void {
	if (!Capacitor.isNativePlatform()) return;

	let queued = false;
	const schedule = (): void => {
		if (queued) return;
		queued = true;
		requestAnimationFrame(() => {
			queued = false;
			void applySystemBars();
		});
	};

	new MutationObserver(schedule).observe(document.documentElement, {
		attributes: true,
		attributeFilter: ["data-theme", "class"],
	});
	window.matchMedia?.("(prefers-color-scheme: dark)").addEventListener?.("change", schedule);
	document.addEventListener("visibilitychange", () => {
		if (document.visibilityState === "visible") schedule();
	});

	void applySystemBars();
}
