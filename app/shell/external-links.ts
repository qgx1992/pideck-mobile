/**
 * 外链交给系统浏览器。
 *
 * 上游点外链用的是 `window.open(url, "_blank")`。Capacitor 的 WebView 里
 * `_blank` 要么被吞掉（没有任何反应），要么在同一 WebView 里打开——
 * 用户就再也回不到会话了。这里统一拦下来丢给系统 Intent。
 */

import { Capacitor, registerPlugin } from "@capacitor/core";

interface NavigationBarApi {
	openUrl(options: { url: string }): Promise<void>;
}

const NavigationBar = registerPlugin<NavigationBarApi>("NavigationBar");

const EXTERNAL = /^https?:\/\//i;

export function installExternalLinks(): void {
	const original = window.open.bind(window);
	window.open = ((url?: string | URL, target?: string, features?: string) => {
		const href = typeof url === "string" ? url : url?.toString();
		if (Capacitor.isNativePlatform() && href && EXTERNAL.test(href)) {
			void NavigationBar.openUrl({ url: href }).catch(() => undefined);
			return null;
		}
		return original(url as string, target, features);
	}) as typeof window.open;
}
