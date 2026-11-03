#!/usr/bin/env node
/**
 * 真机渲染冒烟（手动跑，不进 CI）：
 * 用 jsdom 执行 `dist/web.html` 的构建产物，连**真的** PiDeck，
 * 确认「上游 web 客户端 + 我们的令牌注入」能正常起来。
 *
 *   node scripts/smoke-ui.mjs --base http://127.0.0.1:8765 --token <token>
 *
 * 令牌不会被打日志；也可以给 PIDECK_BASE / PIDECK_TOKEN。
 * 不给 token 时从桌面端设置里读（%APPDATA%/PiDeck/settings.json 的 webServiceToken），
 * 免去每次手抄二维码链接。
 */

import { readFileSync, readdirSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";
import { JSDOM } from "jsdom";

const APP_DIR = path.resolve(import.meta.dirname, "..");
const DIST = path.join(APP_DIR, "dist");

function arg(name) {
	const index = process.argv.indexOf(`--${name}`);
	return index >= 0 ? process.argv[index + 1] : undefined;
}

function readDesktopToken() {
	try {
		const file = path.join(os.homedir(), "AppData/Roaming/PiDeck/settings.json");
		const settings = JSON.parse(readFileSync(file, "utf8"));
		return typeof settings.webServiceToken === "string" ? settings.webServiceToken : "";
	} catch {
		return "";
	}
}

const baseUrl = (arg("base") ?? process.env.PIDECK_BASE ?? "http://127.0.0.1:8765").replace(/\/+$/, "");
const token = arg("token") ?? process.env.PIDECK_TOKEN ?? readDesktopToken();
if (!token) {
	console.error("缺 token：--token、PIDECK_TOKEN，或桌面端 settings.json 里没有 webServiceToken");
	process.exit(1);
}

const html = readFileSync(path.join(DIST, "web.html"), "utf8");
const entry = [...html.matchAll(/<script[^>]*src="([^"]+)"/g)].map((match) => match[1]).find((src) => /assets\/.+\.js$/.test(src));
if (!entry) {
	console.error("dist/web.html 里找不到入口脚本，先跑 npm run build");
	process.exit(1);
}

const dom = new JSDOM(html.replace(/<script[^>]*><\/script>/g, ""), {
	url: "http://localhost/",
	pretendToBeVisual: true,
});
const { window } = dom;
const g = globalThis;

for (const key of [
	"window",
	"document",
	"navigator",
	"location",
	"history",
	"localStorage",
	"sessionStorage",
	"HTMLElement",
	"HTMLAnchorElement",
	"HTMLInputElement",
	"Element",
	"Node",
	"NodeList",
	"MutationObserver",
	"CustomEvent",
	"Event",
	"KeyboardEvent",
	"MouseEvent",
	"Blob",
	"File",
	"FileReader",
	"Image",
	"DOMRect",
	"getComputedStyle",
	"requestIdleCallback",
	"cancelIdleCallback",
	"TextDecoder",
	"TextEncoder",
	"DOMParser",
	"XMLSerializer",
	"DocumentFragment",
	"HTMLTemplateElement",
	"HTMLDivElement",
	"HTMLTextAreaElement",
	"HTMLButtonElement",
	"HTMLCanvasElement",
	"CSSStyleDeclaration",
	"Range",
	"Selection",
	"NodeFilter",
	"TreeWalker",
	"PointerEvent",
	"InputEvent",
	"CompositionEvent",
	"FocusEvent",
	"WheelEvent",
	"DragEvent",
	"ClipboardEvent",
	"UIEvent",
	"AbortController",
	"AbortSignal",
	"Headers",
	"Request",
	"Response",
	"FormData",
	"URLSearchParams",
]) {
	if (key in window) {
		try {
			Object.defineProperty(g, key, { value: window[key], configurable: true, writable: true });
		} catch {
			// navigator 之类在 Node 里是只读的，已经有等价物，跳过
		}
	}
}
g.self = g;
g.requestAnimationFrame = (cb) => setTimeout(() => cb(Date.now()), 0);
g.cancelAnimationFrame = (id) => clearTimeout(id);
g.matchMedia = window.matchMedia = (query) => ({
	matches: false,
	media: query,
	onchange: null,
	addEventListener: () => undefined,
	removeEventListener: () => undefined,
	addListener: () => undefined,
	removeListener: () => undefined,
	dispatchEvent: () => false,
});
window.ResizeObserver ??= class {
	observe() {}
	unobserve() {}
	disconnect() {}
};
g.ResizeObserver = window.ResizeObserver;
window.IntersectionObserver ??= class {
	observe() {}
	unobserve() {}
	disconnect() {}
	takeRecords() {
		return [];
	}
};
g.IntersectionObserver = window.IntersectionObserver;
window.scrollTo = () => undefined;
window.HTMLElement.prototype.scrollIntoView = () => undefined;
window.Element.prototype.scrollTo = () => undefined;
window.Element.prototype.scroll = () => undefined;

// 设备注册表 + 上游令牌：等价于 boot.ts 在真机上做的事
const deviceId = baseUrl.replace(/^https?:\/\//, "");
window.localStorage.setItem(
	"pideck.devices",
	JSON.stringify([{ id: deviceId, name: "smoke", baseUrl, token, addedAt: Date.now() }]),
);
window.localStorage.setItem("pideck.activeDevice", deviceId);
window.localStorage.setItem("pideck-web-token", token);

// 上游所有请求都是相对路径；Node 的 fetch 不认相对 URL，这里补成绝对地址并带鉴权头
const nativeFetch = globalThis.fetch;
const toAbsolute = (raw) => {
	if (/^https?:\/\/(localhost|127\.0\.0\.1)([:/]|$)/.test(raw)) return baseUrl + raw.replace(/^https?:\/\/(localhost|127\.0\.0\.1)/, "");
	if (raw.startsWith("/")) return `${baseUrl}${raw}`;
	return raw;
};
window.fetch = (input, init = {}) => {
	const raw = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
	const headers = { ...(init.headers ?? {}), authorization: `Bearer ${token}` };
	return nativeFetch(toAbsolute(raw), { ...init, headers });
};
g.fetch = window.fetch;

// jsdom 没有 EventSource：用真实 fetch 流做一个够用的实现（上游只用 onopen/onmessage/onerror）
class SmokeEventSource extends window.EventTarget {
	constructor(url) {
		super();
		this.url = toAbsolute(url);
		this.readyState = 0;
		this.onopen = null;
		this.onmessage = null;
		this.onerror = null;
		this.abort = new AbortController();
		void this.#connect();
	}
	async #connect() {
		try {
			const response = await window.fetch(this.url, { headers: { accept: "text/event-stream" }, signal: this.abort.signal });
			if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
			this.readyState = 1;
			this.dispatchEvent(Object.assign(new window.Event("open"), {}));
			const reader = response.body.getReader();
			const decoder = new TextDecoder();
			let buffer = "";
			for (;;) {
				const { value, done } = await reader.read();
				if (done) break;
				buffer += decoder.decode(value, { stream: true });
				let index;
				while ((index = buffer.search(/\r\n\r\n|\n\n|\r\r/)) >= 0) {
					const raw = buffer.slice(0, index);
					buffer = buffer.slice(index + buffer.slice(index).match(/^(\r\n\r\n|\n\n|\r\r)/)[0].length);
					this.#handle(raw.trim() ? raw : "");
				}
			}
			this.readyState = 2;
		} catch (error) {
			if (this.abort.signal.aborted) return;
			this.readyState = 2;
			this.dispatchEvent(Object.assign(new window.Event("error"), { message: String(error) }));
		}
	}
	#handle(raw) {
		let name = "message";
		const data = [];
		for (const line of raw.split(/\r\n|\n|\r/)) {
			if (!line || line.startsWith(":")) continue;
			const colon = line.indexOf(":");
			const field = colon < 0 ? line : line.slice(0, colon);
			const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
			if (field === "event") name = value;
			else if (field === "data") data.push(value);
		}
		if (data.length === 0) return;
		this.dispatchEvent(Object.assign(new window.Event(name), { data: data.join("\n") }));
	}
	close() {
		this.readyState = 2;
		this.abort.abort();
	}
}
window.EventSource = SmokeEventSource;
g.EventSource = SmokeEventSource;

await import(pathToFileURL(path.join(DIST, entry)).href);

async function waitFor(check, timeoutMs = 15_000) {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		try {
			if (check()) return true;
		} catch {
			// 渲染中间态，继续等
		}
		await new Promise((resolve) => setTimeout(resolve, 100));
	}
	return false;
}

const ok = await waitFor(() => {
	const composer = window.document.querySelector("textarea");
	const text = window.document.body.textContent ?? "";
	return Boolean(composer) && text.length > 0;
});

if (!ok) {
	console.error("✗ 15 秒内没渲染出输入框。页面当前内容：");
	console.error((window.document.body.textContent ?? "").slice(0, 600));
	console.error("控制台错误：", (dom.window.__smokeErrors ?? []).slice(0, 5));
	process.exit(1);
}

const doc = window.document;
const sessions = await waitFor(() => (doc.body.textContent ?? "").includes("会话") || doc.querySelectorAll("button").length > 3, 5_000);
console.log(`✓ 已渲染输入框（textarea=${doc.querySelectorAll("textarea").length}），按钮 ${doc.querySelectorAll("button").length} 个`);
console.log(`✓ 侧栏/会话：${sessions ? "有内容" : "空（这台机器可能还没有会话）"}`);
console.log(`✓ CSS 产物：${readdirSync(path.join(DIST, "assets")).filter((name) => name.endsWith(".css")).join(", ") || "无"}`);
