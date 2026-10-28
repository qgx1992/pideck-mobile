/**
 * 平台相关的 fetch 分流。
 *
 * 背景（均已对着 PiDeck 0.9.7 实测）：
 *   1. CORS 预检只放行 `content-type`，不放行 `authorization`
 *      → 跨域 POST 带 Bearer 会被拦。
 *   2. workspace 路由（/api/files、/api/prompts、/api/skills、/api/git/*）**实际响应不带
 *      Access-Control-Allow-Origin**，只有 OPTIONS 预检带。
 *      → 这些接口在 WebView 里一律 `Failed to fetch`，跟用什么地址无关。
 *
 * 因此分流规则：
 *   - SSE（Accept: text/event-stream）：必须走 WebView，原生栈不支持流式响应体
 *   - 其它所有请求：走 CapacitorHttp 原生栈，没有 CORS 概念
 *   - 浏览器开发时：全部走普通 fetch，配合 Vite 的 /api 同源代理
 */

import { Capacitor, CapacitorHttp } from "@capacitor/core";

export function createAppFetch(): typeof fetch {
  if (!Capacitor.isNativePlatform()) return globalThis.fetch.bind(globalThis);
  return nativeFetch as typeof fetch;
}

function toUrl(input: RequestInfo | URL): string {
  if (typeof input === "string") return input;
  if (input instanceof URL) return input.toString();
  return input.url;
}

function headersToRecord(headers: HeadersInit | undefined): Record<string, string> {
  if (!headers) return {};
  if (headers instanceof Headers) {
    const record: Record<string, string> = {};
    headers.forEach((value, key) => {
      record[key] = value;
    });
    return record;
  }
  if (Array.isArray(headers)) return Object.fromEntries(headers);
  return { ...headers };
}

/** 该请求是否是 SSE（需要流式响应体）。导出以便单测。 */
export function isStreamingRequest(headers: Record<string, string>): boolean {
  const accept = headers.accept ?? headers.Accept ?? "";
  // 头值大小写不敏感，且可能带 charset 参数
  return String(accept).toLowerCase().includes("text/event-stream");
}

async function nativeFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const method = (init?.method ?? (typeof input === "object" && "method" in input ? input.method : "GET")).toUpperCase();
  const headers = headersToRecord(init?.headers);

  // SSE 只能留在 WebView：原生 HTTP 会把响应整体缓冲，流式增量就没了
  if (isStreamingRequest(headers)) {
    return globalThis.fetch(input as RequestInfo, init);
  }

  if (init?.signal?.aborted) {
    throw new DOMException("Aborted", "AbortError");
  }

  const url = toUrl(input);
  let data: unknown;
  if (typeof init?.body === "string") {
    try {
      data = JSON.parse(init.body);
    } catch {
      data = init.body;
    }
  }

  const result = await CapacitorHttp.request({
    url,
    method,
    headers,
    ...(data !== undefined ? { data } : {}),
    connectTimeout: 20_000,
    readTimeout: 300_000,
  });

  // 204 / 304 没有响应体，构造 Response 时不能给 body
  const hasBody = result.status !== 204 && result.status !== 304 && result.data !== undefined;
  const payload = !hasBody
    ? null
    : typeof result.data === "string"
      ? result.data
      : JSON.stringify(result.data ?? null);

  return new Response(payload, {
    status: result.status,
    headers: result.headers ?? {},
  });
}
