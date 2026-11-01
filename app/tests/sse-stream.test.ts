/**
 * 原生 SSE 传输（platform/sse-stream.ts）。
 *
 * 这里用假 transport 顶替 Capacitor 插件，验证「事件流 → Response」这层转换：
 * 走的是真 ReadableStream / Response，所以能挡住
 * 「流没关 / 事件串到别的连接 / 401 被错判成网络错误」这类回归。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { PiDeckClient } from "../../src/api/client.ts";
import {
  createNativeSseFetch,
  getNativeSseStats,
  type SseOpenOptions,
  type SseTransport,
  type SseTransportHandlers,
} from "../src/platform/sse-stream.ts";

const ID = "sse-test-1";

class FakeTransport implements SseTransport {
  handlers: SseTransportHandlers = {};
  opened: SseOpenOptions[] = [];
  closed: string[] = [];
  unsubscribed = 0;

  async open(options: SseOpenOptions): Promise<void> {
    this.opened.push(options);
  }

  async close(options: { id: string }): Promise<void> {
    this.closed.push(options.id);
  }

  async subscribe(handlers: SseTransportHandlers): Promise<() => void> {
    this.handlers = handlers;
    return () => {
      this.unsubscribed += 1;
    };
  }

  emitOpen(status: number, headers: Record<string, string> = {}): void {
    this.handlers.open?.({ id: ID, status, headers });
  }

  emitData(data: string): void {
    this.handlers.data?.({ id: ID, data });
  }

  emitEnd(): void {
    this.handlers.end?.({ id: ID, status: 200 });
  }

  emitError(message: string, code = "io"): void {
    this.handlers.error?.({ id: ID, message, code });
  }
}

function setup() {
  const transport = new FakeTransport();
  const fetchFn = createNativeSseFetch(transport, { newId: () => ID, openTimeoutMs: 50 });
  return { transport, fetchFn };
}

/** 让 impl 内部那两次 await（subscribe / open）跑完 */
function tick(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

async function readAll(response: Response): Promise<string> {
  assert.ok(response.body, "response body 必须存在");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let out = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value) out += decoder.decode(value, { stream: true });
  }
  return out + decoder.decode();
}

test("正常流：打开 → 增量 → 结束，拼回原文", async () => {
  const { transport, fetchFn } = setup();

  const pending = fetchFn("http://pi/api/sessions/a/stream", {
    headers: { accept: "text/event-stream" },
  });
  await tick();
  transport.emitOpen(200, { "content-type": "text/event-stream" });
  const response = await pending;

  assert.equal(response.status, 200);
  assert.equal(response.ok, true);
  assert.equal(response.headers.get("content-type"), "text/event-stream");

  const text = readAll(response);
  transport.emitData('data: {"type":"delta","text":"你');
  transport.emitData('好"}\n\n');
  transport.emitData("data: [DONE]\n\n");
  transport.emitEnd();

  assert.equal(await text, 'data: {"type":"delta","text":"你好"}\n\ndata: [DONE]\n\n');
  // 结束后必须把原生连接关掉，否则线程和 socket 会一直挂着
  assert.deepEqual(transport.closed, [ID]);
  assert.equal(transport.unsubscribed, 1);
});

test("请求头与超时参数原样传给原生侧", async () => {
  const { transport, fetchFn } = setup();
  const pending = fetchFn("http://pi/api/events?token=t", {
    headers: { accept: "text/event-stream", "cache-control": "no-cache" },
  });
  await tick();
  transport.emitOpen(200);
  const response = await pending;

  assert.equal(transport.opened.length, 1);
  const call = transport.opened[0]!;
  assert.equal(call.url, "http://pi/api/events?token=t");
  assert.equal(call.headers.accept, "text/event-stream");
  assert.equal(call.headers["cache-control"], "no-cache");
  assert.equal(call.id, ID);

  // 收尾：不关的话原生连接会一直占着（也顺便验证 open 之后的清理路径）
  const text = readAll(response);
  transport.emitEnd();
  assert.equal(await text, "");
  assert.equal(getNativeSseStats().active, 0);
});

test("事件 id 不匹配的帧被丢弃（多连接互不串台）", async () => {
  const { transport, fetchFn } = setup();
  const pending = fetchFn("http://pi/api/events", { headers: { accept: "text/event-stream" } });
  await tick();
  transport.emitOpen(200);
  const response = await pending;

  const text = readAll(response);
  transport.handlers.data?.({ id: "别的一条连接", data: "不应该出现" });
  transport.emitData("ok");
  transport.emitEnd();

  assert.equal(await text, "ok");
});

test("401 带 JSON 错误体：照常返回 Response，body 可读（鉴权要靠它分类）", async () => {
  const { transport, fetchFn } = setup();
  const pending = fetchFn("http://pi/api/sessions/a/stream", {
    headers: { accept: "text/event-stream" },
  });
  await tick();
  transport.emitOpen(401, { "content-type": "application/json" });
  const response = await pending;

  assert.equal(response.ok, false);
  assert.equal(response.status, 401);

  const parsed = response.json();
  transport.emitData('{"code":"unauthorized","error":"令牌无效"}');
  transport.emitEnd();

  assert.deepEqual(await parsed, { code: "unauthorized", error: "令牌无效" });
});

test("连接阶段报错：fetch 直接 reject，并关掉原生连接", async () => {
  const { transport, fetchFn } = setup();
  const pending = fetchFn("http://pi/api/events", { headers: { accept: "text/event-stream" } });
  await tick();
  transport.emitError("Failed to connect", "network");

  await assert.rejects(pending, /Failed to connect/);
  assert.deepEqual(transport.closed, [ID]);
});

test("连上之后报错：读取端以流错误形式抛出", async () => {
  const { transport, fetchFn } = setup();
  const pending = fetchFn("http://pi/api/events", { headers: { accept: "text/event-stream" } });
  await tick();
  transport.emitOpen(200);
  const response = await pending;

  const reading = readAll(response);
  transport.emitData("data: 1\n\n");
  transport.emitError("Socket closed", "io");

  await assert.rejects(reading, /Socket closed/);
});

test("只有响应头没有正文：流立即结束", async () => {
  const { transport, fetchFn } = setup();
  const pending = fetchFn("http://pi/api/events", { headers: { accept: "text/event-stream" } });
  await tick();
  transport.emitOpen(200);
  const response = await pending;

  const text = readAll(response);
  transport.emitEnd();
  assert.equal(await text, "");
});

test("204 不带 body（否则 Response 构造会抛）", async () => {
  const { transport, fetchFn } = setup();
  const pending = fetchFn("http://pi/api/events", { headers: { accept: "text/event-stream" } });
  await tick();
  transport.emitOpen(204);
  const response = await pending;

  assert.equal(response.status, 204);
  assert.equal(response.body, null);
  assert.deepEqual(transport.closed, [ID]);
});

test("abort：读端正常结束，原生连接被关掉（不产生未处理错误）", async () => {
  const { transport, fetchFn } = setup();
  const controller = new AbortController();
  const pending = fetchFn("http://pi/api/events", {
    headers: { accept: "text/event-stream" },
    signal: controller.signal,
  });
  await tick();
  transport.emitOpen(200);
  const response = await pending;

  const text = readAll(response);
  controller.abort();

  assert.equal(await text, "");
  assert.deepEqual(transport.closed, [ID]);
});

test("已 abort 的 signal 直接拒绝，不碰原生连接", async () => {
  const { transport, fetchFn } = setup();
  const controller = new AbortController();
  controller.abort();

  await assert.rejects(
    fetchFn("http://pi/api/events", { headers: { accept: "text/event-stream" }, signal: controller.signal }),
    /Aborted/,
  );
  assert.deepEqual(transport.opened, []);
  assert.deepEqual(transport.closed, []);
});

test("等响应头超时按连接失败处理", async () => {
  const transport = new FakeTransport();
  const fetchFn = createNativeSseFetch(transport, { newId: () => ID, openTimeoutMs: 10 });
  const pending = fetchFn("http://pi/api/events", { headers: { accept: "text/event-stream" } });

  await assert.rejects(pending, /超时/);
  assert.deepEqual(transport.closed, [ID]);
});

test("端到端：PiDeckClient.streamSession 通过原生 transport 收到帧", async () => {
  const { transport, fetchFn } = setup();
  const client = new PiDeckClient({
    baseUrl: "http://pi",
    token: "tok",
    fetchImpl: fetchFn,
  });

  const frames: string[] = [];
  let opened = false;
  const running = client.streamSession("s1", {
    onEvent: () => undefined,
    onOpen: () => {
      opened = true;
    },
    onFrame: (frame) => frames.push(frame.type),
  });

  await tick();
  transport.emitOpen(200, { "content-type": "text/event-stream" });
  await tick();
  transport.emitData('data: {"type":"start"}\n\n');
  transport.emitData('data: {"type":"delta","text":"hi"}\n\n');
  transport.emitData("data: [DONE]\n\n");
  transport.emitEnd();
  await running;

  assert.equal(opened, true);
  assert.deepEqual(frames, ["start", "delta", "finish"]);
});

test("统计计数在流结束后归零（排查用）", async () => {
  const { transport, fetchFn } = setup();
  const pending = fetchFn("http://pi/api/events", { headers: { accept: "text/event-stream" } });
  await tick();
  transport.emitOpen(200);
  const response = await pending;
  const text = readAll(response);
  transport.emitEnd();
  await text;

  const stats = getNativeSseStats();
  assert.equal(stats.active, 0);
  assert.ok(stats.opened > 0);
});
