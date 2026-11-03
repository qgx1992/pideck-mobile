/**
 * 把上游 web 客户端的相对 `/api/*` 请求接到原生 HTTP 栈。
 *
 * 为什么必须接管：
 *   - Capacitor 的 WebView 起点是 `https://localhost`，上游写的是相对路径 `/api/state`，
 *     不接管就会打到本地资源目录（404）；
 *   - 直接写绝对地址 `http://192.168.x.x:8765/api/...` 会变成跨域请求：PiDeck 的
 *     CORS 预检不放行 `authorization` 头（见 AGENTS.md 里 0.3.x 的踩坑），
 *     WebView fetch 必然被拦；原生栈没有 CORS 概念。
 *
 * 分流：
 *   - `Accept: text/event-stream`（`/api/chat` 的 POST 流）→ 原生 SSE（SsePlugin）
 *   - 其余 `/api/*` → CapacitorHttp.request（整体缓冲，不流式）
 */
import { Capacitor, CapacitorHttp } from "@capacitor/core";
import { createNativeSseFetch, isNativeSseAvailable, getSseTransport } from "./native-sse.ts";

type HeaderRecord = Record<string, string>;

function headersToRecord(headers: HeadersInit | undefined): HeaderRecord {
	if (!headers) return {};
	if (typeof Headers !== "undefined" && headers instanceof Headers) {
		const record: HeaderRecord = {};
		headers.forEach((value, key) => {
			record[key] = value;
		});
		return record;
	}
	if (Array.isArray(headers)) return Object.fromEntries(headers) as HeaderRecord;
	return { ...(headers as HeaderRecord) };
}

function isApiPath(url: string): boolean {
	return url.startsWith("/api/") || url.startsWith("/api?") || url === "/api";
}

async function readBody(body: BodyInit | null | undefined): Promise<string | undefined> {
	if (body === undefined || body === null) return undefined;
	if (typeof body === "string") return body;
	if (body instanceof URLSearchParams) return body.toString();
	if (typeof Blob !== "undefined" && body instanceof Blob) return await body.text();
	if (body instanceof ArrayBuffer) return new TextDecoder().decode(body);
	if (ArrayBuffer.isView(body)) return new TextDecoder().decode(body as ArrayBufferView as unknown as ArrayBuffer);
	if (typeof ReadableStream !== "undefined" && body instanceof ReadableStream) {
		return await new Response(body).text();
	}
	return undefined;
}

export function installFetchBridge(baseUrl: string): void {
	const originalFetch = window.fetch.bind(window);
	const nativeSseFetch = createNativeSseFetch(getSseTransport());

	window.fetch = async function bridgedFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
		const request = input instanceof Request ? input : null;
		const rawUrl = typeof input === "string" ? input : input instanceof URL ? input.toString() : request?.url ?? "";
		if (!isApiPath(rawUrl)) return originalFetch(input as RequestInfo, init);

		// 浏览器里跑（`npm run dev`）没有原生栈，退回真 fetch，方便在桌面上点界面。
		if (!Capacitor.isNativePlatform()) return originalFetch(input as RequestInfo, init);

		const method = (init?.method ?? request?.method ?? "GET").toUpperCase();
		const headers = { ...headersToRecord(request?.headers), ...headersToRecord(init?.headers) };
		const body = await readBody(init?.body ?? request?.body);
		const url = `${baseUrl.replace(/\/+$/, "")}${rawUrl}`;
		const accept = headers.accept ?? headers.Accept ?? "";
		const wantsStream = accept.includes("text/event-stream");

		if (wantsStream && isNativeSseAvailable()) {
			return nativeSseFetch({ url, method, headers, body, signal: init?.signal ?? request?.signal ?? undefined });
		}

		const response = await CapacitorHttp.request({
			url,
			method,
			headers,
			...(body !== undefined ? { data: body } : {}),
			responseType: "text",
			connectTimeout: 15_000,
			readTimeout: 120_000,
		});

		const status = response.status;
		const bodiless = status === 204 || status === 304;
		const payload = typeof response.data === "string" ? response.data : JSON.stringify(response.data ?? "");
		return new Response(bodiless ? null : payload, {
			status,
			headers: headersToRecord(response.headers as HeadersInit),
		});
	};
}
