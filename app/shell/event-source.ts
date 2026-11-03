/**
 * `EventSource` 的 polyfill：跑在原生 SSE 上。
 *
 * 上游 web 有两处用它：`/api/events`（项目/会话/运行态推送）与
 * `/api/sessions/:id/stream`（外部回复打字机）。WebView 里的 EventSource
 * 同样受 CORS 约束（token 只能拼 query），切后台还会被冻结，所以整体换到原生栈。
 *
 * 行为对齐浏览器：
 *   - `onopen` / `onmessage` / `onerror` + `addEventListener("state", ...)` 这类命名事件
 *   - SSE 帧解析（`data:` 多行拼接、`event:`、`id:`、注释行、`\r\n`/`\n`）
 *   - 断线自动重连（指数退避，封顶 15s）；上游依赖「重连即重放」重建合并器
 *   - `close()` 之后不再重连
 */

import { createNativeSseFetch, toAbsoluteUrl, getSseTransport } from "./native-sse.ts";
import { SseFrameParser } from "./sse-frames.ts";

type Listener = (event: Event & { data?: string; lastEventId?: string }) => void;

export class NativeEventSource {
	static readonly CONNECTING = 0;
	static readonly OPEN = 1;
	static readonly CLOSED = 2;

	readonly CONNECTING = 0;
	readonly OPEN = 1;
	readonly CLOSED = 2;

	readonly url: string;
	readyState = 0;
	withCredentials = false;
	onopen: ((event: Event) => void) | null = null;
	onmessage: ((event: MessageEvent<string>) => void) | null = null;
	onerror: ((event: Event) => void) | null = null;

	private readonly baseUrl: string;
	/** 额外请求头（Authorization）：上游把 token 拼在 query 里，原生栈可以直接带上头 */
	private readonly headers: Record<string, string>;
	private readonly listeners = new Map<string, Set<Listener>>();
	private readonly parser = new SseFrameParser();
	private readonly nativeFetch = createNativeSseFetch(getSseTransport());
	private closed = false;
	private attempt = 0;
	private retryTimer: ReturnType<typeof setTimeout> | undefined;
	private abortController: AbortController | null = null;

	constructor(url: string, baseUrl: string, headers: Record<string, string> = {}) {
		this.url = url;
		this.baseUrl = baseUrl;
		this.headers = headers;
		void this.connect();
	}

	addEventListener(type: string, listener: Listener): void {
		const set = this.listeners.get(type) ?? new Set<Listener>();
		set.add(listener);
		this.listeners.set(type, set);
	}

	removeEventListener(type: string, listener: Listener): void {
		this.listeners.get(type)?.delete(listener);
	}

	close(): void {
		this.closed = true;
		this.readyState = 2;
		if (this.retryTimer !== undefined) {
			clearTimeout(this.retryTimer);
			this.retryTimer = undefined;
		}
		this.abortController?.abort();
		this.abortController = null;
	}

	private emit(type: string, event: Event & { data?: string; lastEventId?: string }): void {
		const set = this.listeners.get(type);
		if (set) for (const listener of set) listener(event);
	}

	private scheduleReconnect(): void {
		if (this.closed) return;
		this.readyState = 0;
		const delay = Math.min(15_000, 1_000 * 2 ** Math.min(this.attempt, 4));
		this.attempt += 1;
		this.retryTimer = setTimeout(() => {
			this.retryTimer = undefined;
			void this.connect();
		}, delay);
	}

	private async connect(): Promise<void> {
		if (this.closed) return;
		this.readyState = 0;
		this.abortController = new AbortController();
		try {
			const response = await this.nativeFetch({
				url: toAbsoluteUrl(this.url, this.baseUrl),
				method: "GET",
				headers: { accept: "text/event-stream", ...this.headers },
				signal: this.abortController.signal,
			});

			if (response.status === 401 || response.status >= 500) {
				// 令牌轮换 / 服务端重启：照常重试，上游的 auth-failed 广播在轮询层
				this.onerror?.call(this, new Event("error"));
				this.emit("error", new Event("error"));
				void response.body?.cancel();
				this.scheduleReconnect();
				return;
			}

			this.attempt = 0;
			this.readyState = 1;
			this.onopen?.call(this, new Event("open"));
			this.emit("open", new Event("open"));

			const reader = response.body?.getReader();
			if (!reader) {
				this.scheduleReconnect();
				return;
			}
			const decoder = new TextDecoder();
			for (;;) {
				const { done, value } = await reader.read();
				if (done) break;
				for (const frame of this.parser.push(decoder.decode(value, { stream: true }))) {
					const event = new MessageEvent(frame.type, { data: frame.data, lastEventId: frame.id });
					if (frame.type === "message") this.onmessage?.call(this, event as MessageEvent<string>);
					this.emit(frame.type, event as Event & { data?: string; lastEventId?: string });
				}
			}
			if (this.closed) return;
			this.onerror?.call(this, new Event("error"));
			this.emit("error", new Event("error"));
			this.scheduleReconnect();
		} catch (error) {
			if (this.closed) return;
			this.onerror?.call(this, new Event("error"));
			this.emit("error", new Event("error"));
			this.scheduleReconnect();
		}
	}
}

export function installEventSource(baseUrl: string, headers: Record<string, string> = {}): void {
	class BridgedEventSource extends NativeEventSource {
		constructor(url: string) {
			super(url, baseUrl, headers);
		}
	}
	const target = window as unknown as { EventSource: typeof EventSource };
	target.EventSource = BridgedEventSource as unknown as typeof EventSource;
}
