import test from "node:test";
import assert from "node:assert/strict";
import { SseParser, Utf8StreamDecoder, parseSseText } from "../src/api/sse.ts";

test("解析单条 data 帧并忽略心跳注释", () => {
  const events = parseSseText(': ping\n\ndata: {"type":"finish"}\n\n');
  assert.equal(events.length, 1);
  assert.equal(events[0]?.event, "message");
  assert.equal(events[0]?.data, '{"type":"finish"}');
});

test("识别 event 字段与多行 data", () => {
  const events = parseSseText("event: state\ndata: {\"a\":1,\ndata: \"b\":2}\n\n");
  assert.equal(events.length, 1);
  assert.equal(events[0]?.event, "state");
  assert.equal(events[0]?.data, '{"a":1,\n"b":2}');
});

test("一个分片里包含多条帧", () => {
  const events = parseSseText("data: 1\n\ndata: 2\n\ndata: [DONE]\n\n");
  assert.deepEqual(
    events.map((e) => e.data),
    ["1", "2", "[DONE]"],
  );
});

test("被切断的帧在下一个分片续上", () => {
  const parser = new SseParser();
  assert.deepEqual(parser.push("data: {\"ty"), []);
  assert.deepEqual(parser.push("pe\":\"fin"), []);
  const events = parser.push("ish\"}\n\n");
  assert.equal(events.length, 1);
  assert.equal(events[0]?.data, '{"type":"finish"}');
});

test("CRLF 与单独 CR 都能分行", () => {
  assert.equal(parseSseText("data: a\r\n\r\n").length, 1);
  assert.equal(parseSseText("data: a\r\r").length, 1);
});

test("末尾孤立 CR 不误判为换行", () => {
  const parser = new SseParser();
  assert.deepEqual(parser.push("data: a\r"), []);
  // 补上被切开的 CRLF，再补一个空行事件才收口
  const events = parser.push("\n\n");
  assert.equal(events.length, 1);
  assert.equal(events[0]?.data, "a");
});

test("flush 吐出没有尾随空行的残留数据", () => {
  const parser = new SseParser();
  parser.push("data: tail");
  const events = parser.flush();
  assert.equal(events.length, 1);
  assert.equal(events[0]?.data, "tail");
});

test("id / retry 字段被保留，注释不产生事件", () => {
  const events = parseSseText("id: 7\nretry: 3000\n: comment\ndata: x\n\n");
  assert.equal(events.length, 1);
  assert.equal(events[0]?.id, "7");
  assert.equal(events[0]?.retry, 3000);
});

test("BOM 被剥离", () => {
  const events = parseSseText("\uFEFFdata: x\n\n");
  assert.equal(events[0]?.data, "x");
});

test("空 data 行等价于空载荷（保留事件）", () => {
  const events = parseSseText("data:\n\n");
  assert.equal(events.length, 1);
  assert.equal(events[0]?.data, "");
});

test("UTF-8 多字节字符被分片切断仍能正确解码", () => {
  const bytes = new TextEncoder().encode("你好好好");
  const decoder = new Utf8StreamDecoder();
  // 故意在每个字节边界切分
  let text = "";
  for (const byte of bytes) text += decoder.decode(Uint8Array.of(byte));
  text += decoder.flush();
  assert.equal(text, "你好好好");
});
