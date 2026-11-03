import assert from "node:assert/strict";
import { test } from "node:test";

import { SseFrameParser } from "./sse-frames.ts";

test("解析单条 message 帧", () => {
	const parser = new SseFrameParser();
	const events = parser.push('data: {"a":1}\n\n');
	assert.deepEqual(events, [{ type: "message", data: '{"a":1}', id: "" }]);
});

test("命名事件（/api/events 的 state）", () => {
	const parser = new SseFrameParser();
	const events = parser.push("event: state\ndata: {}\n\n");
	assert.equal(events[0]?.type, "state");
	assert.equal(events[0]?.data, "{}");
});

test("多行 data 用换行拼接", () => {
	const parser = new SseFrameParser();
	const events = parser.push("data: line1\ndata: line2\n\n");
	assert.equal(events[0]?.data, "line1\nline2");
});

test("跨 chunk 的半帧要缓存住", () => {
	const parser = new SseFrameParser();
	assert.deepEqual(parser.push("data: hel"), []);
	assert.deepEqual(parser.push('lo\n'), []);
	const events = parser.push("\n");
	assert.deepEqual(events, [{ type: "message", data: "hello", id: "" }]);
});

test("注释行（心跳）不产生事件", () => {
	const parser = new SseFrameParser();
	assert.deepEqual(parser.push(": keep-alive\n\n"), []);
});

test("id 字段带进事件（上游重连判重会用）", () => {
	const parser = new SseFrameParser();
	const events = parser.push("id: 42\ndata: x\n\n");
	assert.equal(events[0]?.id, "42");
});

test("\\r\\n 分隔与一行多条事件", () => {
	const parser = new SseFrameParser();
	const events = parser.push("data: a\r\n\r\ndata: b\r\n\r\n");
	assert.deepEqual(
		events.map((event) => event.data),
		["a", "b"],
	);
});

test("没有 data 的块被丢弃", () => {
	const parser = new SseFrameParser();
	assert.deepEqual(parser.push("event: ping\n\n"), []);
});
