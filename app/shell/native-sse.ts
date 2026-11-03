/**
 * 原生 SSE 传输（GET / POST）。
 *
 * 原样沿用 0.3.x 的 `app/src/platform/sse-stream.ts`，补两件事：
 *   1. `open()` 支持 method / body —— 上游 web 的 `/api/chat` 是 **POST + SSE**，
 *      CapacitorHttp 不支持流式响应体，只能走原生；
 *   2. 协议侧多传 method / body（见 `SsePlugin.java`）。
 *
 * 返回的 `Response` 与真 fetch 等价（含 4xx/5xx，错误体照样可读）。
 */
import { Capacitor, registerPlugin } from "@capacitor/core";

export interface SseOpenPayload {
	id: string;
	status: number;
	headers?: Record<string, string>;
}

export interface SseDataPayload {
	id: string;
	data: string;
}

export interface SseEndPayload {
	id: string;
	status?: number;
}

export interface SseErrorPayload {
	id: string;
	message: string;
	code?: string;
}

export interface SseTransportHandlers {
	open?: (payload: SseOpenPayload) => void;
	data?: (payload: SseDataPayload) => void;
	end?: (payload: SseEndPayload) => void;
	error?: (payload: SseErrorPayload) => void;
}

export interface SseOpenOptions {
	id: string;
	url: string;
	headers: Record<string, string>;
	/** 默认 GET；`/api/chat` 用 POST */
	method?: string;
	/** POST 体（已序列化的字符串） */
	body?: string;
	connectTimeoutMs?: number;
	readTimeoutMs?: number;
}

export interface SseTransport {
	open(options: SseOpenOptions): Promise<void>;
	close(options: { id: string }): Promise<void>;
	subscribe(handlers: SseTransportHandlers): Promise<() => void>;
}

export function toAbsoluteUrl(url: string, baseUrl: string): string {
	if (/^https?:\/\//i.test(url)) return url;
	return `${baseUrl.replace(/\/+$/, "")}${url.startsWith("/") ? url : `/${url}`}`;
}

function createId(): string {
	const cryptoObj = globalThis.crypto as Crypto | undefined;
	if (cryptoObj?.randomUUID) return cryptoObj.randomUUID();
	return `sse_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
}

export interface NativeSseFetchOptions {
	newId?: () => string;
	openTimeoutMs?: number;
	connectTimeoutMs?: number;
	readTimeoutMs?: number;
}

export interface NativeSseRequest {
	url: string;
	method?: string;
	headers?: Record<string, string>;
	body?: string;
	signal?: AbortSignal;
}

/** 用原生传输实现一次「流式 fetch」，产出真正的 `Response`。 */
export function createNativeSseFetch(transport: SseTransport, options: NativeSseFetchOptions = {}) {
	const newId = options.newId ?? createId;
	const openTimeoutMs = options.openTimeoutMs ?? 20_000;

	return async function nativeSseFetch(request: NativeSseRequest): Promise<Response> {
		const id = newId();
		const { url, headers = {}, signal } = request;
		if (signal?.aborted) throw new DOMException("Aborted", "AbortError");

		const encoder = new TextEncoder();
		let controller: ReadableStreamDefaultController<Uint8Array> | null = null;
		let settled = false;
		let unsubscribe: (() => void) | null = null;
		let timer: ReturnType<typeof setTimeout> | undefined;
		let opened = false;
		let tornDown = false;

		let resolveOpen!: (payload: SseOpenPayload) => void;
		let rejectOpen!: (error: unknown) => void;
		const openPromise = new Promise<SseOpenPayload>((resolve, reject) => {
			resolveOpen = resolve;
			rejectOpen = reject;
		});
		openPromise.catch(() => undefined);

		const stream = new ReadableStream<Uint8Array>({
			start(next) {
				controller = next;
			},
			cancel() {
				void teardown();
			},
		});

		function closeStream(): void {
			if (settled) return;
			settled = true;
			try {
				controller?.close();
			} catch {
				/* 流已 cancel */
			}
		}

		function failStream(error: unknown): void {
			if (settled) return;
			settled = true;
			try {
				controller?.error(error);
			} catch {
				/* 同上 */
			}
		}

		async function teardown(): Promise<void> {
			if (tornDown) return;
			tornDown = true;
			if (timer !== undefined) {
				clearTimeout(timer);
				timer = undefined;
			}
			signal?.removeEventListener("abort", onAbort);
			const off = unsubscribe;
			unsubscribe = null;
			off?.();
			try {
				await transport.close({ id });
			} catch {
				/* 连接可能已经断了 */
			}
		}

		function onAbort(): void {
			closeStream();
			void teardown();
		}

		try {
			unsubscribe = await transport.subscribe({
				open: (payload) => {
					if (payload.id !== id) return;
					opened = true;
					resolveOpen(payload);
				},
				data: (payload) => {
					if (payload.id !== id || settled) return;
					try {
						controller?.enqueue(encoder.encode(payload.data));
					} catch {
						/* abort 与数据同时到达 */
					}
				},
				end: (payload) => {
					if (payload.id !== id) return;
					closeStream();
					void teardown();
				},
				error: (payload) => {
					if (payload.id !== id) return;
					const error = new Error(payload.message || "SSE 连接失败");
					if (!opened) rejectOpen(error);
					failStream(error);
					void teardown();
				},
			});

			if (signal) signal.addEventListener("abort", onAbort, { once: true });

			await transport.open({
				id,
				url,
				headers,
				...(request.method ? { method: request.method } : {}),
				...(request.body !== undefined ? { body: request.body } : {}),
				...(options.connectTimeoutMs !== undefined ? { connectTimeoutMs: options.connectTimeoutMs } : {}),
				...(options.readTimeoutMs !== undefined ? { readTimeoutMs: options.readTimeoutMs } : {}),
			});

			timer = setTimeout(() => {
				rejectOpen(new Error("原生 SSE 等待响应头超时"));
			}, openTimeoutMs);

			const head = await openPromise;
			if (timer !== undefined) {
				clearTimeout(timer);
				timer = undefined;
			}

			if (head.status < 200 || head.status > 599) {
				throw new Error(`原生 SSE 返回异常状态码 ${head.status}`);
			}

			const bodiless = head.status === 204 || head.status === 304;
			if (bodiless) {
				closeStream();
				void teardown();
			}

			return new Response(bodiless ? null : (stream as unknown as BodyInit), {
				status: head.status,
				headers: head.headers ?? {},
			});
		} catch (error) {
			failStream(error);
			await teardown();
			throw error;
		}
	};
}

interface CapacitorListenerHandle {
	remove(): Promise<void>;
}

interface SseNativePlugin {
	open(options: SseOpenOptions): Promise<void>;
	close(options: { id: string }): Promise<void>;
	list(): Promise<{ ids: string[] }>;
	addListener(event: string, callback: (payload: unknown) => void): Promise<CapacitorListenerHandle>;
}

const PLUGIN_NAME = "Sse";

export function isNativeSseAvailable(): boolean {
	if (!Capacitor.isNativePlatform()) return false;
	return Capacitor.isPluginAvailable(PLUGIN_NAME);
}

let transport: SseTransport | null = null;

export function getSseTransport(): SseTransport {
	if (transport) return transport;
	const plugin = registerPlugin<SseNativePlugin>(PLUGIN_NAME);
	transport = {
		async open(options) {
			await plugin.open(options);
		},
		async close(options) {
			await plugin.close(options);
		},
		async subscribe(handlers) {
			const handles = await Promise.all([
				plugin.addListener("sseOpen", (payload) => handlers.open?.(payload as SseOpenPayload)),
				plugin.addListener("sseData", (payload) => handlers.data?.(payload as SseDataPayload)),
				plugin.addListener("sseEnd", (payload) => handlers.end?.(payload as SseEndPayload)),
				plugin.addListener("sseError", (payload) => handlers.error?.(payload as SseErrorPayload)),
			]);
			return () => {
				for (const handle of handles) void handle.remove?.();
			};
		},
	};
	return transport;
}
