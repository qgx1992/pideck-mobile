/**
 * 启动页：设备管理。
 *
 * 上游 web 客户端一次只连一台桌面（它自己的令牌存在 localStorage），
 * 所以「多设备」在壳这一层做成：启动页列出所有配对过的机器 → 选一台 →
 * 带着它的 baseUrl/令牌进 `web.html`。换设备就是回到这一页再选。
 */

import { Capacitor, CapacitorHttp } from "@capacitor/core";
import { App } from "@capacitor/app";
import {
	deviceIdFor,
	getActiveDevice,
	loadDevices,
	normalizeBaseUrl,
	parsePairingInput,
	saveDevices,
	setActiveDevice,
	upsertDevice,
	type DeviceRecord,
} from "./device.ts";

type ProbeState = "checking" | "online" | "offline" | "auth";

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;
const devicesEl = $("devices");
const inputEl = $<HTMLInputElement>("pair-input");
const addBtn = $<HTMLButtonElement>("add-btn");
const scanBtn = $<HTMLButtonElement>("scan-btn");
const toastEl = $("toast");
const scanOverlay = $("scan-overlay");

let devices = loadDevices();
const probeStates = new Map<string, ProbeState>();

let toastTimer: ReturnType<typeof setTimeout> | undefined;
function toast(message: string): void {
	toastEl.textContent = message;
	toastEl.classList.add("show");
	if (toastTimer) clearTimeout(toastTimer);
	toastTimer = setTimeout(() => toastEl.classList.remove("show"), 2600);
}

function persist(next: DeviceRecord[]): void {
	devices = next;
	saveDevices(next);
	render();
}

function hostOf(device: DeviceRecord): string {
	return device.baseUrl.replace(/^https?:\/\//, "");
}

function authHeaders(device: DeviceRecord): Record<string, string> {
	return device.token ? { authorization: `Bearer ${device.token}` } : {};
}

async function probe(device: DeviceRecord): Promise<ProbeState> {
	probeStates.set(device.id, "checking");
	render();
	const url = `${device.baseUrl}/api/state`;
	try {
		let status: number;
		if (Capacitor.isNativePlatform()) {
			const response = await CapacitorHttp.request({
				url,
				method: "GET",
				headers: authHeaders(device),
				responseType: "text",
				connectTimeout: 4_000,
				readTimeout: 8_000,
			});
			status = response.status;
		} else {
			const response = await fetch(url, { headers: authHeaders(device) });
			status = response.status;
		}
		const state: ProbeState = status === 200 ? "online" : status === 401 || status === 403 ? "auth" : "offline";
		probeStates.set(device.id, state);
		if (state === "online") {
			const next = devices.map((item) =>
				item.id === device.id ? { ...item, lastSeenAt: Date.now() } : item,
			);
			devices = next;
			saveDevices(next);
		}
		return state;
	} catch {
		probeStates.set(device.id, "offline");
		return "offline";
	} finally {
		render();
	}
}

const STATE_LABEL: Record<ProbeState, string> = {
	checking: "检测中…",
	online: "在线",
	offline: "连不上",
	auth: "令牌失效",
};

function renderDevice(device: DeviceRecord, activeId: string | null): HTMLElement {
	const state = probeStates.get(device.id);
	const card = document.createElement("div");
	card.className = "device" + (device.id === activeId ? " active" : "");
	card.dataset.id = device.id;

	const head = document.createElement("div");
	head.className = "device-head";
	const dot = document.createElement("span");
	dot.className = "dot" + (state === "online" ? " online" : state === "offline" || state === "auth" ? " offline" : "");
	const name = document.createElement("div");
	name.className = "device-name";
	name.textContent = device.name;
	head.append(dot, name);
	if (device.id === activeId) {
		const badge = document.createElement("span");
		badge.className = "brand-sub";
		badge.textContent = "当前";
		head.append(badge);
	}
	card.append(head);

	const url = document.createElement("div");
	url.className = "device-url";
	url.textContent = hostOf(device) + (device.token ? "" : "  · 无令牌");
	card.append(url);

	const row = document.createElement("div");
	row.className = "row";

	const enter = document.createElement("button");
	enter.className = "primary";
	enter.textContent = "进入";
	enter.addEventListener("click", () => {
		setActiveDevice(device);
		location.href = "/web.html";
	});

	const test = document.createElement("button");
	test.textContent = state ? STATE_LABEL[state] : "测试";
	test.addEventListener("click", () => void probe(device));

	const rename = document.createElement("button");
	rename.className = "ghost";
	rename.textContent = "改名";
	rename.addEventListener("click", () => {
		const next = prompt("设备名称", device.name);
		if(!next || !next.trim()) return;
		persist(devices.map((item) => (item.id === device.id ? { ...item, name: next.trim() } : item)));
	});

	const remove = document.createElement("button");
	remove.className = "danger";
	remove.textContent = "删除";
	remove.addEventListener("click", () => {
		if (!confirm(`删除「${device.name}」？`)) return;
		probeStates.delete(device.id);
		persist(devices.filter((item) => item.id !== device.id));
	});

	row.append(enter, test, rename, remove);
	card.append(row);
	return card;
}

function render(): void {
	const activeId = getActiveDevice()?.id ?? null;
	devicesEl.replaceChildren();
	if (devices.length === 0) {
		const empty = document.createElement("div");
		empty.className = "empty";
		empty.textContent = "还没有配对的设备。在桌面端打开「Web 服务」面板，扫码或粘贴链接。";
		devicesEl.append(empty);
		return;
	}
	for (const device of devices) devicesEl.append(renderDevice(device, activeId));
}

function addFromText(raw: string): boolean {
	const parsed = parsePairingInput(raw);
	if (!parsed) {
		toast("链接格式不对，需要 http://主机:端口/?token=…");
		return false;
	}
	const id = deviceIdFor(parsed.baseUrl);
	const existing = devices.find((item) => item.id === id);
	persist(upsertDevice(devices, { baseUrl: parsed.baseUrl, token: parsed.token, name: existing?.name }));
	const device = devices.find((item) => item.id === id);
	if (device) void probe(device);
	inputEl.value = "";
	toast(existing ? "已更新这台设备" : "已添加，点「进入」开始用");
	return true;
}

addBtn.addEventListener("click", () => addFromText(inputEl.value));
inputEl.addEventListener("keydown", (event) => {
	if (event.key === "Enter") addFromText(inputEl.value);
});

/** 扫码：优先 Google 原生全屏扫描器，无 GMS 机型退到插件自带的取景器。 */
async function scanPairingCode(): Promise<string | null> {
	const module = await import("@capacitor-mlkit/barcode-scanning");
	const { BarcodeScanner, BarcodeFormat } = module;
	const supported = await BarcodeScanner.isSupported().catch(() => ({ supported: false }));
	if (!supported.supported) throw new Error("这台设备不支持扫码，请直接粘贴链接");

	let permission = await BarcodeScanner.checkPermissions();
	if (permission.camera !== "granted") permission = await BarcodeScanner.requestPermissions();
	if (permission.camera !== "granted") throw new Error("没有相机权限");

	try {
		const result = await BarcodeScanner.scan({ formats: [BarcodeFormat.QrCode] });
		const text = result.barcodes.map((item) => item.rawValue ?? item.displayValue ?? "").find(Boolean);
		return text ?? null;
	} catch (error) {
		if (/cancel|取消|abort/i.test(String(error))) return null;
	}

	// 没有 Google code scanner 模块时的兜底：插件取景器 + 自绘取景框
	scanOverlay.classList.add("show");
	const handles: Array<{ remove(): Promise<void> }> = [];
	let timer: ReturnType<typeof setTimeout> | undefined;
	try {
		return await new Promise<string | null>((resolve) => {
			const finish = (value: string | null): void => {
				if (timer) clearTimeout(timer);
				resolve(value);
			};
			void BarcodeScanner.addListener("barcodesScanned", (event) => {
				const text = event.barcodes.map((item) => item.rawValue ?? "").find(Boolean);
				if (text) finish(text);
			})
				.then((handle) => {
					handles.push(handle);
				})
				.catch(() => finish(null));
			void BarcodeScanner.startScan({ formats: [BarcodeFormat.QrCode] }).catch(() => finish(null));
			timer = setTimeout(() => finish(null), 60_000);
		});
	} finally {
		if (timer) clearTimeout(timer);
		scanOverlay.classList.remove("show");
		await BarcodeScanner.stopScan().catch(() => undefined);
		for (const handle of handles) await handle.remove().catch(() => undefined);
		await BarcodeScanner.removeAllListeners().catch(() => undefined);
	}
}

scanBtn.addEventListener("click", async () => {
	scanBtn.disabled = true;
	try {
		const text = await scanPairingCode();
		if (text) addFromText(text);
	} catch (error) {
		toast(error instanceof Error ? error.message : String(error));
	} finally {
		scanBtn.disabled = false;
	}
});

// 返回键：启动页没有可关的层，直接退出
void App.addListener("backButton", () => {
	void App.exitApp();
});

// 从聊天页退回来时重新测一次，顺手刷新「最后一次在线」
document.addEventListener("visibilitychange", () => {
	if (document.visibilityState !== "visible") return;
	render();
	for (const device of devices) void probe(device);
});

render();
for (const device of devices) void probe(device);

export type { ProbeState };
export { normalizeBaseUrl };
