/**
 * 底部手势条 / 状态栏的安全区。
 *
 * 上游 Web 客户端已经把安全区写进了布局（`WebChatApp.tsx:1299` 的
 * `pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]`），但它依赖
 * `env(safe-area-inset-*)`——Android WebView 在 edge-to-edge 下**实测经常给 0**
 * （页面明明被手势条压住，env 仍是 0），于是输入框永远贴在屏幕最底、被小横条盖住。
 *
 * 这里的做法：由原生（`NavigationBar.getInsets`）量出「WebView 与系统栏的真实重叠」，
 * 再把 env() 没算到的差值补进那几个 utility 的 padding。不改 vendored 源码，
 * 也不碰不会重叠的设备（退出 e2e 成功时重叠本身就是 0）。
 */

import { Capacitor, registerPlugin } from "@capacitor/core";

import { buildSafeAreaCss, round } from "./safe-area-css.ts";

export { buildSafeAreaCss } from "./safe-area-css.ts";

interface Insets {
	top: number;
	bottom: number;
	statusBar: number;
	navBar: number;
	screenHeight: number;
}

const NavigationBar = registerPlugin<{ getInsets(): Promise<Insets> }>("NavigationBar");


/** 量一下这个设备上 `env(safe-area-inset-*)` 到底给了多少（不支持时是 0）。 */
export function measureEnvInset(side: "top" | "bottom"): number {
	if (typeof document === "undefined" || !document.body) return 0;
	const probe = document.createElement("div");
	probe.style.cssText = [
		"position:fixed",
		`${side}:0`,
		"left:0",
		"width:0",
		`height:env(safe-area-inset-${side}, 0px)`,
		"visibility:hidden",
		"pointer-events:none",
	].join(";");
	document.body.appendChild(probe);
	const height = probe.getBoundingClientRect().height;
	probe.remove();
	return Number.isFinite(height) ? height : 0;
}

const STYLE_ID = "pideck-safe-area";

/** 真机排查用：`__pideckDebug.insets()` 看到的就是这里报的值（dp）。 */
export const readNativeInsets = (): Promise<Insets> => NavigationBar.getInsets();
export function applySafeAreaInsets(insets: Insets, root: HTMLElement = document.documentElement): void {
	const extra = {
		top: Math.max(0, insets.top - measureEnvInset("top")),
		bottom: Math.max(0, insets.bottom - measureEnvInset("bottom")),
	};
	root.style.setProperty("--pideck-inset-top", `${round(insets.top)}px`);
	root.style.setProperty("--pideck-inset-bottom", `${round(insets.bottom)}px`);

	let style = document.getElementById(STYLE_ID) as HTMLStyleElement | null;
	if (!style) {
		style = document.createElement("style");
		style.id = STYLE_ID;
		document.head.appendChild(style);
	}
	style.textContent = buildSafeAreaCss(extra);
}

/**
 * 起安全区同步。返回一个 `refresh()`，键盘弹出/旋转后可以手动再量一次。
 */
export function startSafeArea(): { refresh: () => void } {
	if (!Capacitor.isNativePlatform()) return { refresh: () => undefined };

	let timer: ReturnType<typeof setTimeout> | undefined;
	const run = (): void => {
		NavigationBar.getInsets()
			.then((insets) => applySafeAreaInsets(insets))
			.catch(() => undefined);
	};
	// 布局落定 / 键盘收起后再量：resize 一连串事件里只有最后那次几何是准的
	const refresh = (): void => {
		if (timer !== undefined) clearTimeout(timer);
		timer = setTimeout(() => {
			timer = undefined;
			run();
		}, 150);
	};

	run();
	window.addEventListener("resize", refresh);
	window.addEventListener("orientationchange", refresh);
	window.visualViewport?.addEventListener("resize", refresh);

	return { refresh };
}
