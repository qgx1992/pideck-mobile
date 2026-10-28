import test from "node:test";
import assert from "node:assert/strict";
import { ChatReducer, blocksToPlainText } from "../src/api/frames.ts";
import { safeParseFrame } from "../src/api/client.ts";

test("文本流按 delta 累积，finish 收口", () => {
  const reducer = new ChatReducer();
  reducer.apply({ type: "start", messageId: "m1" });
  assert.equal(reducer.status, "running");

  reducer.apply({ type: "text-start", id: "t1" });
  reducer.apply({ type: "text-delta", id: "t1", delta: "你" });
  reducer.apply({ type: "text-delta", id: "t1", delta: "好" });

  assert.equal(reducer.blocks.length, 1);
  assert.equal(blocksToPlainText(reducer.blocks), "你好");

  const result = reducer.apply({ type: "finish" });
  assert.equal(result.finished, true);
  assert.equal(reducer.status, "finished");
  const text = reducer.blocks[0];
  assert.ok(text && text.kind === "text" && text.done);
});

test("思考块与正文块互不干扰", () => {
  const reducer = new ChatReducer();
  reducer.apply({ type: "start", messageId: "m1" });
  reducer.apply({ type: "reasoning-start", id: "r1" });
  reducer.apply({ type: "reasoning-delta", id: "r1", delta: "先想一下" });
  reducer.apply({ type: "reasoning-end", id: "r1" });
  reducer.apply({ type: "text-start", id: "t1" });
  reducer.apply({ type: "text-delta", id: "t1", delta: "答案" });
  reducer.apply({ type: "finish" });

  assert.equal(reducer.blocks.length, 2);
  assert.equal(blocksToPlainText(reducer.blocks), "答案");
  assert.equal(blocksToPlainText(reducer.blocks, { includeReasoning: true }), "[思考] 先想一下\n答案");
});

test("工具块经历 running → ok", () => {
  const reducer = new ChatReducer();
  reducer.apply({ type: "tool-input-start", toolCallId: "c1", toolName: "bash" });
  reducer.apply({ type: "tool-input-available", toolCallId: "c1", toolName: "bash", input: { cmd: "ls" } });
  let tool = reducer.blocks[0];
  assert.ok(tool && tool.kind === "tool");
  assert.equal(tool.state, "running");

  reducer.apply({ type: "tool-output-available", toolCallId: "c1", output: "ok" });
  tool = reducer.blocks[0];
  assert.ok(tool && tool.kind === "tool");
  assert.equal(tool.state, "ok");
  assert.deepEqual(tool.input, { cmd: "ls" });
});

test("工具失败标记 errorText", () => {
  const reducer = new ChatReducer();
  reducer.apply({ type: "tool-input-start", toolCallId: "c2", toolName: "read" });
  reducer.apply({ type: "tool-output-error", toolCallId: "c2", errorText: "ENOENT" });
  const tool = reducer.blocks[0];
  assert.ok(tool && tool.kind === "tool");
  assert.equal(tool.state, "error");
  assert.equal(tool.errorText, "ENOENT");
});

test("error 帧把整体置为失败并收口未结束的块", () => {
  const reducer = new ChatReducer();
  reducer.apply({ type: "start", messageId: "m1" });
  reducer.apply({ type: "text-start", id: "t1" });
  reducer.apply({ type: "text-delta", id: "t1", delta: "半截" });
  reducer.apply({ type: "error", errorText: "模型超时" });
  reducer.apply({ type: "finish" });

  assert.equal(reducer.status, "error");
  assert.equal(reducer.error, "模型超时");
  const block = reducer.blocks[0];
  assert.ok(block && block.kind === "text" && block.done);
});

test("markAborted 把运行中状态置为 aborted", () => {
  const reducer = new ChatReducer();
  reducer.apply({ type: "start", messageId: "m1" });
  reducer.markAborted();
  assert.equal(reducer.status, "aborted");
});

test("新一轮 start 会清空上一轮的块", () => {
  const reducer = new ChatReducer();
  reducer.apply({ type: "start", messageId: "m1" });
  reducer.apply({ type: "text-start", id: "t1" });
  reducer.apply({ type: "text-delta", id: "t1", delta: "旧" });
  reducer.apply({ type: "finish" });

  reducer.apply({ type: "start", messageId: "m2" });
  assert.equal(reducer.blocks.length, 0);
  assert.equal(reducer.messageId, "m2");
});

test("未知帧不抛异常，原样上报", () => {
  const reducer = new ChatReducer();
  const result = reducer.apply({ type: "quantum-entangle", payload: 1 });
  assert.equal(result.changed, false);
  assert.equal(result.unknownFrame?.type, "quantum-entangle");
});

test("safeParseFrame 拒绝坏 JSON 与无 type 对象", () => {
  assert.equal(safeParseFrame("not json"), null);
  assert.equal(safeParseFrame("{}"), null);
  assert.equal(safeParseFrame('{"type":"finish"}')?.type, "finish");
});
