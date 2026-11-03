/**
 * `web.html` 的引导脚本，必须比上游模块先执行（vite 把它 inject 到 head 最前面）。
 *
 * 做五件事：
 *   1. 把当前设备的令牌写进上游读的那个 localStorage key——必须在 `webApi.ts`
 *      模块顶层读 `?token=` 之前写好，否则会被上一次的旧值覆盖回去；
 *   2. 装 fetch / EventSource 桥（相对 `/api/*` → 设备地址，SSE → 原生）；
 *   3. 关掉上游的 service worker（Capacitor 下它会缓存旧 assets，表现为「更新了还是老页面」）；
 *   4. 外链交给系统浏览器、blob 下载交给原生保存；
 *   5. 起后台看护（有会话在跑时拉起前台服务，跑完发通知）。
 */

import { App } from "@capacitor/app";
import { Capacitor } from "@capacitor/core";

import { UPSTREAM_TOKEN_KEY, getActiveDevice } from "./device.ts";
import { installDownloadBridge } from "./downloads.ts";
import { installExternalLinks } from "./external-links.ts";
import { getSseTransport, isNativeSseAvailable } from "./native-sse.ts";
import { installFetchBridge } from "./fetch-bridge.ts";
import { installEventSource } from "./event-source.ts";
import { startRunWatch } from "./run-watch.ts";

const authHeaders = (token: string): Record<string, string> => (token ? { authorization: `Bearer ${token}` } : {});

const device = getActiveDevice();

if (!device) {
	// 直接打开 /web.html 但没有配对记录：回启动页
	location.replace("/");
} else {
	if (device.token) localStorage.setItem(UPSTREAM_TOKEN_KEY, device.token);
	installFetchBridge(device.baseUrl);
	installEventSource(device.baseUrl, authHeaders(device.token));
	installExternalLinks();
	installDownloadBridge();

	const container = navigator as unknown as { serviceWorker?: ServiceWorkerContainer };
	if (container.serviceWorker) {
		void container.serviceWorker
			.getRegistrations()
			.then((registrations) => Promise.all(registrations.map((registration) => registration.unregister())))
			.catch(() => undefined);
		// 上游 `webPwa.ts` 在 window load 时 register("/sw.js")：给个永远失败的桩，别让它把旧包缓存住
		Object.defineProperty(navigator, "serviceWorker", {
			configurable: true,
			get: () => ({
				register: () => Promise.reject(new Error("service worker disabled in app shell")),
				getRegistrations: () => Promise.resolve([]),
				addEventListener: () => undefined,
				removeEventListener: () => undefined,
			}),
		});
	}

	(window as unknown as { __pideckDevice?: unknown }).__pideckDevice = {
		id: device.id,
		name: device.name,
		baseUrl: device.baseUrl,
	};

	// 排查用：真机上看一眼「消息是逐字出来还是一次性出来」时，
	// 控制台跑 `__pideckDebug.sseIds()`（等它返回一堆 uuid = 走的是原生栈）。
	(window as unknown as { __pideckDebug?: unknown }).__pideckDebug = {
		nativeSse: isNativeSseAvailable(),
		nativeHttp: Capacitor.isNativePlatform(),
		sseIds: () => getSseTransport().list(),
	};

	// 返回键：上游用 pushState 叠层（抽屉/面板各一层），所以能回就回一层；
	// 回到根还没有历史时回启动页。不注册监听的话 Capacitor 会直接 finish()。
	void App.addListener("backButton", ({ canGoBack }) => {
		if (canGoBack) window.history.back();
		else location.replace("/");
	});

	// 看护：会话一开跑就拉前台服务（后台启动会被系统拒绝，所以必须在前台就开始）
	const runWatch = startRunWatch(device);
	document.addEventListener("visibilitychange", () => {
		if (document.visibilityState === "visible") runWatch.poke();
	});
	window.addEventListener("pagehide", () => runWatch.stop());
}
