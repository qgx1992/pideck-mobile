import assert from "node:assert/strict";
import { test } from "node:test";

import { looksLikeStream } from "./fetch-bridge.ts";

test("显式的 text/event-stream 一律走原生 SSE", () => {
	assert.equal(looksLikeStream("/api/state", "text/event-stream"), true);
	assert.equal(looksLikeStream("/api/sessions/runtimes", "text/event-stream, application/json"), true);
});

test("发送接口按路径识别（ai 的 DefaultChatTransport 不带 Accept 头）", () => {
	assert.equal(looksLikeStream("/api/chat", ""), true);
	assert.equal(looksLikeStream("/api/chat?sessionId=abc", ""), true);
	assert.equal(looksLikeStream("/api/chat", "application/json"), true);
});

test("外部流按路径识别", () => {
	assert.equal(looksLikeStream("/api/sessions/s-1/stream?token=x", ""), true);
	assert.equal(looksLikeStream("/api/sessions/s-1/stream", "text/event-stream"), true);
});

test("带 origin 的绝对地址也认", () => {
	assert.equal(looksLikeStream("https://localhost/api/chat", ""), true);
	assert.equal(looksLikeStream("http://10.10.0.172:8765/api/sessions/s-1/stream", ""), true);
});

test("普通请求不要误判成流", () => {
	assert.equal(looksLikeStream("/api/state", "application/json"), false);
	assert.equal(looksLikeStream("/api/sessions/runtimes", ""), false);
	assert.equal(looksLikeStream("/api/sessions/s-1/messages?limit=20", "application/json"), false);
	assert.equal(looksLikeStream("/api/streams-of-consciousness", ""), false);
});
