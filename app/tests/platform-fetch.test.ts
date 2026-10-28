/** 平台 fetch 分流判定：哪些请求必须留在 WebView。 */

import test from "node:test";
import assert from "node:assert/strict";
import { isStreamingRequest } from "../src/platform/fetch.ts";

test("Accept 含 text/event-stream 判为流式", () => {
  assert.equal(isStreamingRequest({ accept: "text/event-stream" }), true);
  assert.equal(isStreamingRequest({ accept: "text/event-stream; charset=utf-8" }), true);
  assert.equal(isStreamingRequest({ Accept: "text/event-stream" }), true);
});

test("普通 JSON 请求不算流式", () => {
  assert.equal(isStreamingRequest({ accept: "application/json" }), false);
  assert.equal(isStreamingRequest({}), false);
});

test("大小写与多余空格不影响判定", () => {
  assert.equal(isStreamingRequest({ accept: "  TEXT/EVENT-STREAM  " }), true);
});
