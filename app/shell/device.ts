/**
 * 设备注册表（多设备）。
 *
 * 上游 web 客户端只认一个端点（相对 `/api/*`），这里把「当前设备」放在
 * localStorage 里，由 `boot.ts` 在页面加载前把它接进 fetch / EventSource。
 * 切换设备 = 回到 launcher（`index.html`）改 activeDevice 再进 `web.html`。
 */

export interface DeviceRecord {
	/** 稳定 id，用 baseUrl 派生（同一台机器只有一个入口） */
	id: string;
	/** 展示名，可改；默认取 baseUrl 的 host:port */
	name: string;
	/** 形如 http://192.168.1.11:8765（末尾不带斜杠） */
	baseUrl: string;
	token: string;
	addedAt: number;
	/** 最近一次连通时间（launcher 里显示） */
	lastSeenAt?: number;
}

export const DEVICES_KEY = "pideck.devices";
export const ACTIVE_DEVICE_KEY = "pideck.activeDevice";
/**
 * 上游 `web/webApi.ts:19` 的 `WEB_TOKEN_STORAGE_KEY`。
 * 必须与上游保持一致：boot 在页面模块执行前把 token 写进去，
 * 上游启动时从 localStorage 读，之后所有请求带 Authorization。
 * （不用 `?token=` 传：那样每次刷新都会用 URL 里的旧令牌覆盖存储。）
 */
export const UPSTREAM_TOKEN_KEY = "pideck-web-token";

export function normalizeBaseUrl(input: string): string {
	let url = input.trim();
	if (!url) return "";
	if (!/^https?:\/\//i.test(url)) url = `http://${url}`;
	return url.replace(/\/+$/, "");
}

/** 认 `http://host:port/?token=xxx`、`http://host:port`、`host:port` 三种贴法。 */
export function parsePairingInput(raw: string): { baseUrl: string; token: string } | null {
	const text = raw.trim();
	if (!text) return null;
	let token = "";
	let baseUrl = "";
	try {
		const url = new URL(/^https?:\/\//i.test(text) ? text : `http://${text}`);
		token = url.searchParams.get("token") ?? "";
		baseUrl = normalizeBaseUrl(`${url.protocol}//${url.host}`);
	} catch {
		return null;
	}
	if (!baseUrl) return null;
	return { baseUrl, token };
}

export function loadDevices(): DeviceRecord[] {
	try {
		const raw = localStorage.getItem(DEVICES_KEY);
		if (!raw) return [];
		const parsed = JSON.parse(raw) as unknown;
		if (!Array.isArray(parsed)) return [];
		return parsed.filter((item): item is DeviceRecord => {
			const record = item as DeviceRecord;
			return Boolean(record && typeof record.baseUrl === "string" && record.baseUrl.length > 0);
		});
	} catch {
		return [];
	}
}

export function saveDevices(devices: DeviceRecord[]): void {
	localStorage.setItem(DEVICES_KEY, JSON.stringify(devices));
}

export function deviceIdFor(baseUrl: string): string {
	return normalizeBaseUrl(baseUrl).replace(/^https?:\/\//, "").replace(/[^a-zA-Z0-9._:-]+/g, "-");
}

export function upsertDevice(devices: DeviceRecord[], next: { baseUrl: string; token: string; name?: string }): DeviceRecord[] {
	const baseUrl = normalizeBaseUrl(next.baseUrl);
	const id = deviceIdFor(baseUrl);
	const existing = devices.find((device) => device.id === id);
	const merged: DeviceRecord = {
		id,
		name: next.name?.trim() || existing?.name || baseUrl.replace(/^https?:\/\//, ""),
		baseUrl,
		token: next.token || existing?.token || "",
		addedAt: existing?.addedAt ?? Date.now(),
		lastSeenAt: existing?.lastSeenAt,
	};
	return existing ? devices.map((device) => (device.id === id ? merged : device)) : [...devices, merged];
}

export function getActiveDevice(): DeviceRecord | null {
	const id = localStorage.getItem(ACTIVE_DEVICE_KEY);
	if (!id) return null;
	return loadDevices().find((device) => device.id === id) ?? null;
}

export function setActiveDevice(device: DeviceRecord): void {
	localStorage.setItem(ACTIVE_DEVICE_KEY, device.id);
}
