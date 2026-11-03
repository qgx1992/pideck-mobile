/**
 * 「点通知 → 进那个会话」。
 *
 * 原生侧早就准备好了：`RunWatchService.notifyFinished/notifyAsk` 往 Intent 里塞
 * `pideckSessionId`（`MainActivity.EXTRA_SESSION_ID`），`MainActivity` 在 onCreate /
 * onNewIntent 里接住，`RunWatchPlugin.consumeLaunchSession()` 交给 JS 取走。
 * 缺的一直是 JS 这一头 —— 没有人取，所以点通知只是把 App 唤醒，停在启动页或上一次的界面。
 *
 * 这里的做法：
 *   · 冷启动：`boot.ts` 先把会话 id 落到 URL（上游 `WebChatApp.tsx:214` 会用
 *     `sessionFromPath()` 恢复选中），再补几拍 popstate 兜住「上游还没挂载」的时序；
 *   · 热启动：监听 `appStateChange`/`resume`，取走原生那个一次性 id，就地切会话；
 *   · 设备不对（通知来自另一台被看护的设备）：先切 activeDevice，再整页重进。
 *
 * 还有一层：Capacitor 的 `html5mode` 会把无扩展名的路径回落到 `index.html`，
 * 所以「停在 /s/<id> 上被系统重建 WebView」时加载的是启动页而不是 404 —— 启动页
 * 认这个路径，转写成待跳转记录后送进 `web.html`（见 `launcher.ts` 的 `handleSessionRoute`）。
 */

import { App } from "@capacitor/app";
import { registerPlugin } from "@capacitor/core";

import { type DeviceRecord, getActiveDevice, loadDevices, setActiveDevice } from "./device.ts";

/** 待跳转的会话（跨页面传递：启动页 → web.html）。 */
export const PENDING_LAUNCH_KEY = "pideck.pendingLaunch";

/** 与上游 `web/webSessionRoute.ts:9` 一致。 */
export const SESSION_ROUTE_PREFIX = "/s/";

export interface PendingLaunch {
	sessionId: string;
	/** 通知来自哪台设备；空表示无所谓（例如手动深链） */
	deviceId?: string;
}

interface RunWatchLaunchApi {
	consumeLaunchSession(): Promise<{ sessionId?: string }>;
}

const RunWatch = registerPlugin<RunWatchLaunchApi>("RunWatch");

/** 从 pathname 解析会话 id（与上游同规则：只认 `[A-Za-z0-9_-]`）。 */
export function sessionFromPath(pathname: string): string {
	if (!pathname.startsWith(SESSION_ROUTE_PREFIX)) return "";
	const id = pathname.slice(SESSION_ROUTE_PREFIX.length).split("/")[0] ?? "";
	return /^[A-Za-z0-9_-]+$/.test(id) ? id : "";
}

/** 会话 id → 路由路径。 */
export function sessionTarget(sessionId: string): string {
	return sessionId ? `${SESSION_ROUTE_PREFIX}${encodeURIComponent(sessionId)}` : "/";
}

function sanitize(value: unknown): PendingLaunch | null {
	if (!value || typeof value !== "object") return null;
	const record = value as { sessionId?: unknown; deviceId?: unknown };
	if (typeof record.sessionId !== "string" || !/^[A-Za-z0-9_-]+$/.test(record.sessionId)) return null;
	const deviceId = typeof record.deviceId === "string" && record.deviceId ? record.deviceId : undefined;
	return { sessionId: record.sessionId, deviceId };
}

export function readPendingLaunch(): PendingLaunch | null {
	try {
		const raw = localStorage.getItem(PENDING_LAUNCH_KEY);
		return raw ? sanitize(JSON.parse(raw) as unknown) : null;
	} catch {
		return null;
	}
}

export function writePendingLaunch(launch: PendingLaunch): void {
	try {
		localStorage.setItem(PENDING_LAUNCH_KEY, JSON.stringify(launch));
	} catch {
		/* 隐私模式/配额满：当次跳转失效，不该把启动流程带崩 */
	}
}

export function clearPendingLaunch(): void {
	try {
		localStorage.removeItem(PENDING_LAUNCH_KEY);
	} catch {
		/* 同上 */
	}
}

/** 通知里的设备还是不是当前设备；不是就给出要切到哪一台。 */
export function pickLaunchDevice(
	launch: PendingLaunch,
	devices: readonly DeviceRecord[],
	activeDeviceId: string | undefined,
): { device: DeviceRecord | null; needsSwitch: boolean } {
	if (!launch.deviceId || launch.deviceId === activeDeviceId) return { device: null, needsSwitch: false };
	const device = devices.find((item) => item.id === launch.deviceId) ?? null;
	return { device, needsSwitch: device !== null };
}

/** 取走原生那个一次性会话 id（没有就返回 null）。 */
export async function consumeNativeLaunch(): Promise<PendingLaunch | null> {
	try {
		const result = await withTimeout(RunWatch.consumeLaunchSession(), 1500);
		const sessionId = result?.sessionId ?? "";
		return /^[A-Za-z0-9_-]+$/.test(sessionId) ? { sessionId } : null;
	} catch {
		return null;
	}
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | null> {
	return Promise.race([promise, new Promise<null>((resolve) => setTimeout(() => resolve(null), ms))]);
}

/**
 * 把地址栏指到会话，并补几拍 `popstate`。
 *
 * 为什么不是只改 URL：上游的恢复 effect（`WebChatApp.tsx:211-226`）只在会话列表到达时
 * 重跑一遍，抢不到就会把 URL 冲回 `/`；popstate 那条路（`:237-243`）则是直接
 * `setActiveSessionId()`，挂载后一定生效。两个都做，谁先到都算成功。
 */
let lastRouted = { sessionId: "", at: 0 };

export function routeToSession(sessionId: string, mode: "push" | "replace" = "replace"): void {
	// appStateChange 与 resume 会连着来两拍，同一会话 2 秒内只跳一次
	if (sessionId === lastRouted.sessionId && Date.now() - lastRouted.at < 2000) return;
	lastRouted = { sessionId, at: Date.now() };

	const target = sessionTarget(sessionId);
	if (location.pathname !== target) {
		if (mode === "push") history.pushState(null, "", target);
		else history.replaceState(null, "", target);
	}
	for (const delay of [0, 250, 800, 2000]) {
		setTimeout(() => {
			// 用户中途点了别的会话就别再抢回来
			if (sessionFromPath(location.pathname) !== sessionId) return;
			window.dispatchEvent(new PopStateEvent("popstate"));
		}, delay);
	}
}

export interface LaunchResult {
	outcome: "none" | "opened" | "switched";
	/** 实际处理的会话 id（没处理就是 undefined） */
	sessionId?: string;
}

/**
 * 消费待跳转记录（先看本地记录，再看原生一次性 id）。
 *
 * @param options.mode      `push` 用于热启动（返回键还能回到原会话），`replace` 用于冷启动
 * @param options.onSwitch  需要换设备时的回调；默认整页重进 `web.html`
 */
export async function openPendingLaunch(options: {
	mode?: "push" | "replace";
	onSwitch?: (device: DeviceRecord) => void;
} = {}): Promise<LaunchResult> {
	const launch = readPendingLaunch() ?? (await consumeNativeLaunch());
	if (!launch) return { outcome: "none" };

	const active = getActiveDevice();
	const { device } = pickLaunchDevice(launch, loadDevices(), active?.id);
	if (device) {
		// 会话在另一台设备上：切换后必须整页重进，桥（baseUrl/令牌）是启动时装死的
		writePendingLaunch(launch);
		setActiveDevice(device);
		if (options.onSwitch) options.onSwitch(device);
		else location.replace("/web.html");
		return { outcome: "switched", sessionId: launch.sessionId };
	}

	clearPendingLaunch();
	routeToSession(launch.sessionId, options.mode ?? "replace");
	return { outcome: "opened", sessionId: launch.sessionId };
}

/** 启动页用：把 `/s/<id>` 这种路径转写成待跳转记录，再进聊天页。 */
export function takeSessionRouteFromUrl(): PendingLaunch | null {
	const sessionId = sessionFromPath(location.pathname);
	if (!sessionId) return null;
	const launch: PendingLaunch = { sessionId, deviceId: getActiveDevice()?.id };
	writePendingLaunch(launch);
	return launch;
}

/**
 * 热启动：App 从后台回来时取一次原生 pending（点通知进来会走这里）。
 * 返回卸载函数。
 */
export function installLaunchWatcher(options: { mode?: "push" | "replace" } = {}): () => void {
	const consume = (): void => {
		void openPendingLaunch({ mode: options.mode ?? "push" });
	};

	const handles = Promise.all([
		App.addListener("appStateChange", ({ isActive }) => {
			if (isActive) consume();
		}),
		App.addListener("resume", consume),
	]);

	return () => {
		void handles.then((list) => Promise.all(list.map((handle) => handle.remove()))).catch(() => undefined);
	};
}
