/**
 * 原生 SSE 传输：把 Android 侧 `SsePlugin` 的事件流，包装成一个与 fetch 同形状的 `Response`。
 *
 * ## 为什么不再用 WebView 的 fetch 读流
 *
 *   - WebView 的 fetch 受 CORS 约束：PiDeck 的预检不放行 `authorization`，
 *     跨域 SSE 只能把 token 塞进 query，token 会落进对端访问日志
 *   - App 切后台后 WebView 被冻结 → SSE 静默断流，只能靠 RunWatchService 轮询补
 *   - CapacitorHttp 不支持流式响应体，所以「非 SSE 走原生栈、SSE 留在 WebView」
 *     等于把最核心的功能放在最弱的一条栈上
 *
 * ## 分层
 *
 *   `SseTransport`   纯接口：open / close / subscribe（单测用假实现，不碰 Capacitor）
 *   `createNativeSseFetch`  把事件流拼成 ReadableStream，产出真正的 `Response`
 *   `createCapacitorSseTransport`  唯一碰 @capacitor/core 的地方
 *
 * 上层（`src/api/client.ts` 的 `streamSse`）完全不用改：它本来就只依赖
 * `response.ok` / `response.body.getReader()`，字节流交给 `Utf8StreamDecoder` 增量解码。
 *
 * 注意原生侧已经按 **字符** 切好（Java 的 InputStreamReader 不会切坏多字节字符），
 * 这里只负责重新编码成字节交给下游解码器，不参与切分判断。
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
  /** timeout / network / io / unknown */
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
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
}

/** 原生 SSE 的最小接口。生产实现见文件末尾，测试里注入假实现。 */
export interface SseTransport {
  open(options: SseOpenOptions): Promise<void>;
  close(options: { id: string }): Promise<void>;
  subscribe(handlers: SseTransportHandlers): Promise<() => void>;
}

export interface NativeSseFetchOptions {
  /** 事件 id 生成器（测试可注入固定值） */
  newId?: () => string;
  /** 等响应头的最长时间；超时按连接失败处理 */
  openTimeoutMs?: number;
  connectTimeoutMs?: number;
  /** 0 = 不设读超时，假死交给 SessionChannel 的看门狗 */
  readTimeoutMs?: number;
}

/** 原生 SSE 的计数器，用于「到底走没走原生栈」的排查。 */
export interface NativeSseStats {
  opened: number;
  failed: number;
  active: number;
}

const stats: NativeSseStats = { opened: 0, failed: 0, active: 0 };

export function getNativeSseStats(): NativeSseStats {
  return { ...stats };
}

function toUrl(input: RequestInfo | URL): string {
  if (typeof input === "string") return input;
  if (typeof URL !== "undefined" && input instanceof URL) return input.toString();
  return (input as Request).url;
}

function headersToRecord(headers: HeadersInit | undefined): Record<string, string> {
  if (!headers) return {};
  if (typeof Headers !== "undefined" && headers instanceof Headers) {
    const record: Record<string, string> = {};
    headers.forEach((value, key) => {
      record[key] = value;
    });
    return record;
  }
  if (Array.isArray(headers)) return Object.fromEntries(headers);
  return { ...(headers as Record<string, string>) };
}

function createId(): string {
  const cryptoObj = globalThis.crypto as Crypto | undefined;
  if (cryptoObj?.randomUUID) return cryptoObj.randomUUID();
  return `sse_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 用原生传输实现一个 `fetch`。
 *
 * 返回的 `Response` 与真 fetch 等价：`ok` / `status` / `headers` / `body`（ReadableStream）。
 * 4xx/5xx 也照常返回 Response（错误体照样可读），由上层 `client.toError` 决定怎么分类 ——
 * 这一点必须保持一致，否则 401 会退化成「网络错误」，会话就不会切到「需要重新配对」。
 */
export function createNativeSseFetch(
  transport: SseTransport,
  options: NativeSseFetchOptions = {},
): typeof fetch {
  const newId = options.newId ?? createId;
  const openTimeoutMs = options.openTimeoutMs ?? 20_000;

  const impl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const id = newId();
    const url = toUrl(input);
    const headers = headersToRecord(init?.headers);
    const signal = init?.signal ?? undefined;
    if (signal?.aborted) throw new DOMException("Aborted", "AbortError");

    const encoder = new TextEncoder();
    let controller: ReadableStreamDefaultController<Uint8Array> | null = null;
    let settled = false;
    let unsubscribe: (() => void) | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let opened = false;
    let tornDown = false;
    let counted = false;

    let resolveOpen!: (payload: SseOpenPayload) => void;
    let rejectOpen!: (error: unknown) => void;
    const openPromise = new Promise<SseOpenPayload>((resolve, reject) => {
      resolveOpen = resolve;
      rejectOpen = reject;
    });
    // 连接失败时 openPromise 可能无人 await（例如 open() 自己就抛了），先吃掉拒绝
    openPromise.catch(() => undefined);

    const stream = new ReadableStream<Uint8Array>({
      start(next) {
        controller = next;
      },
      cancel() {
        // 上层 reader.cancel()（含 abort 触发的取消）
        void teardown();
      },
    });

    function closeStream(): void {
      if (settled) return;
      settled = true;
      try {
        controller?.close();
      } catch {
        // 流已被 cancel，忽略
      }
    }

    function failStream(error: unknown): void {
      if (settled) return;
      settled = true;
      try {
        controller?.error(error);
      } catch {
        // 同上
      }
    }

    async function teardown(): Promise<void> {
      // abort 与 end 可能先后到，native close 只发一次
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
      if (counted && stats.active > 0) stats.active -= 1;
      counted = false;
      try {
        await transport.close({ id });
      } catch {
        // 连接可能已经断了，忽略
      }
    }

    function onAbort(): void {
      closeStream();
      void teardown();
    }

    try {
      stats.opened += 1;
      stats.active += 1;
      counted = true;

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
            // 流已关闭（abort 与数据同时到达）
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

      // 204/304 不允许带 body，构造 Response 时会直接抛
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
      stats.failed += 1;
      failStream(error);
      await teardown();
      throw error;
    }
  };

  return impl as typeof fetch;
}

/* ------------------------------------------------------------------ */
/* Capacitor 绑定                                                      */
/* ------------------------------------------------------------------ */

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

/**
 * 当前环境能不能用原生 SSE。
 *
 * 拿不到插件（浏览器开发、旧版 APK）就返回 false，调用方退回 WebView 的 fetch ——
 * 这样「装了旧包的用户」不会因为多一条代码路径就整个断流。
 */
export function isNativeSseAvailable(): boolean {
  if (!Capacitor.isNativePlatform()) return false;
  return Capacitor.isPluginAvailable(PLUGIN_NAME);
}

let transport: SseTransport | null = null;

export function createCapacitorSseTransport(): SseTransport {
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

/** 当前在跑的原生连接 id（排查用；web 环境返回空数组）。 */
export async function listNativeSseConnections(): Promise<string[]> {
  if (!isNativeSseAvailable()) return [];
  try {
    const result = await registerPlugin<SseNativePlugin>(PLUGIN_NAME).list();
    return Array.isArray(result.ids) ? result.ids : [];
  } catch {
    return [];
  }
}
