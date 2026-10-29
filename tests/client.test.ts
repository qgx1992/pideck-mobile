import test from "node:test";
import assert from "node:assert/strict";
import { PiDeckClient, collectRun } from "../src/api/client.ts";
import { PiDeckAuthError, PiDeckHttpError, PiDeckOfflineError, PiDeckRuntimeError } from "../src/api/errors.ts";

const BASE = "http://127.0.0.1:8765";
const TOKEN = "client-test-token-value";

interface Captured {
  url: string;
  init: RequestInit;
}

function clientWith(handler: (captured: Captured) => Response | Promise<Response>) {
  const calls: Captured[] = [];
  const client = new PiDeckClient({
    baseUrl: BASE,
    token: TOKEN,
    fetchImpl: (async (input: RequestInfo | URL, init?: RequestInit) => {
      const captured = { url: String(input), init: init ?? {} };
      calls.push(captured);
      return handler(captured);
    }) as typeof fetch,
  });
  return { client, calls };
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

test("GET 只带 query token，不发 Authorization（避免触发 CORS 预检）", async () => {
  const { client, calls } = clientWith(() => json({ ok: true, service: "PiDeck", host: "0.0.0.0", port: 8765 }));
  await client.health();
  const call = calls[0]!;
  assert.match(call.url, new RegExp(`token=${TOKEN}`));
  const headers = call.init.headers as Record<string, string>;
  // PiDeck 的预检只放行 content-type；GET 带 Authorization 会升级成需要预检的请求，
  // 预检失败在 WebView 里表现为 "Failed to fetch"。
  assert.equal(headers.authorization, undefined);
});

test("SSE 也只带 query token", async () => {
  const LF = String.fromCharCode(10);
  const doneBody = ["data: [DONE]", "", ""].join(LF);
  const { client, calls } = clientWith(
    () => new Response(doneBody, { status: 200, headers: { "content-type": "text/event-stream" } }),
  );
  await client.streamSession("s1", { onEvent: () => undefined });
  const headers = calls[0]!.init.headers as Record<string, string>;
  assert.equal(headers.authorization, undefined);
  assert.match(calls[0]!.url, new RegExp(`token=${TOKEN}`));
});

test("POST 只走 Bearer 头，URL 不带 token", async () => {
  const { client, calls } = clientWith(() => json({ result: { accepted: true } }));
  await client.sendPrompt("s1", { requestId: "r1", message: "hi" });
  const call = calls[0]!;
  assert.equal(call.url.includes("token="), false);
  const headers = call.init.headers as Record<string, string>;
  assert.equal(headers["content-type"], "application/json");
  assert.deepEqual(JSON.parse(String(call.init.body)), { requestId: "r1", message: "hi" });
});

test("401 映射为 PiDeckAuthError 并带 code", async () => {
  const { client } = clientWith(() => json({ code: "webError.unauthorized", error: "Missing or invalid web service token" }, 401));
  await assert.rejects(
    () => client.getState(),
    (error: unknown) => {
      assert.ok(error instanceof PiDeckAuthError);
      assert.equal(error.status, 401);
      assert.equal(error.code, "webError.unauthorized");
      return true;
    },
  );
});

test("409 映射为 PiDeckRuntimeError（target 过期）", async () => {
  const { client } = clientWith(() =>
    json({ code: "webError.runtimeTargetRequired", error: "A matching Session runtime target is required" }, 409),
  );
  await assert.rejects(
    () => client.abort("s1", { sessionId: "s1", agentId: "a1", runtimeGeneration: 1 }),
    (error: unknown) => error instanceof PiDeckRuntimeError,
  );
});

test("其它 4xx/5xx 映射为 PiDeckHttpError 并保留状态码", async () => {
  const { client } = clientWith(() => json({ code: "webError.internal", error: "boom" }, 500));
  await assert.rejects(
    () => client.getMessages("s1"),
    (error: unknown) => error instanceof PiDeckHttpError && error.status === 500 && error.code === "webError.internal",
  );
});

test("网络失败映射为 PiDeckOfflineError", async () => {
  const client = new PiDeckClient({
    baseUrl: BASE,
    token: TOKEN,
    fetchImpl: (async () => {
      throw new TypeError("fetch failed");
    }) as typeof fetch,
  });
  await assert.rejects(() => client.health(), (error: unknown) => error instanceof PiDeckOfflineError);
});

test("304 返回 notModified，不覆盖本地状态", async () => {
  const { client } = clientWith(() => new Response(null, { status: 304, headers: { etag: '"abc"' } }));
  const response = await client.getState('"abc"');
  assert.equal(response.notModified, true);
  assert.deepEqual(response.state.projects, []);
});

test("state 响应被归一化并带回 etag", async () => {
  const { client } = clientWith(() =>
    json(
      { projects: [{ id: "p1", kind: "chat" }], sessions: [{ id: "s1", projectId: "p1", title: "t" }], runtimes: [], pendingUiRequests: [] },
      200,
      { etag: '"v1"' },
    ),
  );
  const response = await client.getState();
  assert.equal(response.etag, '"v1"');
  assert.equal(response.state.projects.length, 1);
  assert.equal(response.state.sessions[0]?.id, "s1");
});

test("runtime 调用需要 target，并把它放进 body", async () => {
  const { client, calls } = clientWith(() => json({ result: { ok: true } }));
  await client.compact("s1", { sessionId: "s1", agentId: "a1", runtimeGeneration: 3 });
  const body = JSON.parse(String(calls[0]!.init.body)) as Record<string, unknown>;
  assert.deepEqual(body.target, { sessionId: "s1", agentId: "a1", runtimeGeneration: 3 });

  await assert.rejects(
    () => client.compact("s1", undefined as never),
    (error: unknown) => (error as { kind?: string }).kind === "protocol",
  );
});

test("activate 不需要 target", async () => {
  const { client, calls } = clientWith(() => json({ result: { activated: true } }));
  await client.activate("s1");
  const body = JSON.parse(String(calls[0]!.init.body)) as Record<string, unknown>;
  assert.equal("target" in body, false);
});

function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { "content-type": "text/event-stream; charset=utf-8" },
  });
}

test("streamSession 解析分片帧并识别 [DONE]", async () => {
  const { client } = clientWith(() =>
    sseResponse([
      'data: {"type":"text-start","id":"t1"}\n\ndata: {"type":"text-del',
      'ta","id":"t1","delta":"你好"}\n\n: ping\n\n',
      "data: [DONE]\n\n",
    ]),
  );
  const frames: string[] = [];
  await client.streamSession("s1", {
    onEvent: () => undefined,
    onFrame: (frame) => frames.push(frame.type),
  });
  assert.deepEqual(frames, ["text-start", "text-delta", "finish"]);
});

test("collectRun 在 finish 后自动收尾并返回块", async () => {
  const { client } = clientWith(() =>
    sseResponse([
      'data: {"type":"start","messageId":"m1"}\n\n',
      'data: {"type":"text-start","id":"t1"}\n\n',
      'data: {"type":"text-delta","id":"t1","delta":"ok"}\n\n',
      'data: {"type":"text-end","id":"t1"}\n\n',
      'data: {"type":"finish"}\n\ndata: [DONE]\n\n',
    ]),
  );
  const reducer = await collectRun(client, "s1");
  assert.equal(reducer.status, "finished");
  const block = reducer.blocks[0];
  assert.ok(block && block.kind === "text");
  assert.equal(block.text, "ok");
});

test("normalizeBaseUrl 容错：补 scheme、去尾斜杠、丢 query", () => {
  const client = new PiDeckClient({ baseUrl: "192.168.1.5:8765/?token=zzz", token: TOKEN });
  assert.equal(client.baseUrl, "http://192.168.1.5:8765");
});

test("streamSse 每收到一个分片就回调 onActivity（用于检测连接假死）", async () => {
  const LF = String.fromCharCode(10);
  const frames = [
    'data: {"type":"text-start","id":"t1"}' + LF + LF,
    ": ping" + LF + LF,
    "data: [DONE]" + LF + LF,
  ];
  const { client } = clientWith(() => sseResponse(frames));
  let activity = 0;
  await client.streamSession("s1", {
    onEvent: () => undefined,
    onActivity: () => {
      activity += 1;
    },
  });
  assert.equal(activity, 3, "三个分片应触发三次活动回调（含心跳）");
});
