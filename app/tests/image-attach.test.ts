/**
 * 图片附件的纯逻辑：尺寸约束、体积估算、历史消息里的图片提取。
 */

import test from "node:test";
import assert from "node:assert/strict";

const {
  MAX_IMAGES,
  base64Payload,
  canAddImage,
  collectImageSources,
  estimateBytes,
  fitWithin,
  formatBytes,
  isImageDataUrl,
  mimeOfDataUrl,
  toImageAttachment,
} = await import("../src/ui/image-attach-model.ts");

/** 一张 1x1 的 PNG，够用来验证解析与体积计算 */
const PNG =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

test("fitWithin 只缩长边，不放大", () => {
  assert.deepEqual(fitWithin(4000, 3000, 1600), { width: 1600, height: 1200 });
  assert.deepEqual(fitWithin(3000, 4000, 1600), { width: 1200, height: 1600 });
  assert.deepEqual(fitWithin(800, 600, 1600), { width: 800, height: 600 });
  assert.deepEqual(fitWithin(0, 0), { width: 0, height: 0 });
  assert.deepEqual(fitWithin(Number.NaN, 10), { width: 0, height: 0 });
});

test("data URL 拆解", () => {
  assert.equal(mimeOfDataUrl(PNG), "image/png");
  assert.ok(base64Payload(PNG).startsWith("iVBORw0KGgo"));
  assert.equal(base64Payload("no-comma"), "no-comma");
  assert.equal(isImageDataUrl(PNG), true);
  assert.equal(isImageDataUrl("data:text/plain;base64,QQ=="), false);
  assert.equal(isImageDataUrl(undefined), false);
});

test("体积估算与格式化", () => {
  const bytes = estimateBytes(PNG);
  assert.ok(bytes > 50 && bytes < 200, `实际 ${bytes}`);
  assert.equal(formatBytes(512), "512 B");
  assert.equal(formatBytes(2048), "2 KB");
  assert.equal(formatBytes(3 * 1024 * 1024), "3.0 MB");
});

test("张数上限", () => {
  assert.equal(canAddImage(0), true);
  assert.equal(canAddImage(MAX_IMAGES - 1), true);
  assert.equal(canAddImage(MAX_IMAGES), false);
});

test("发给服务端的形状是裸 base64 + mimeType", () => {
  const attachment = toImageAttachment({
    dataUrl: PNG,
    mimeType: "image/png",
    width: 1,
    height: 1,
    bytes: 100,
  });
  assert.equal(attachment.mimeType, "image/png");
  assert.ok(!String(attachment.data).startsWith("data:"));
});

test("历史消息里的图片：AI SDK parts 形状", () => {
  const sources = collectImageSources({
    id: "m1",
    role: "user",
    parts: [
      { type: "text", text: "看这张" },
      { type: "image", data: PNG },
    ],
  });
  assert.deepEqual(sources, [PNG]);
});

test("历史消息里的图片：file + mediaType + 裸 base64", () => {
  const payload = base64Payload(PNG);
  const sources = collectImageSources({
    role: "user",
    parts: [{ type: "file", mediaType: "image/png", data: payload }],
  });
  assert.deepEqual(sources, [PNG]);
});

test("历史消息里的图片：images 数组 + url", () => {
  const sources = collectImageSources({ role: "user", images: [{ url: PNG, mimeType: "image/png" }] });
  assert.deepEqual(sources, [PNG]);
});

test("同一张图重复出现只保留一份", () => {
  const sources = collectImageSources({ role: "user", parts: [{ type: "image", data: PNG }], images: [{ data: PNG }] });
  assert.deepEqual(sources, [PNG]);
});

test("纯文本消息不产生图片", () => {
  assert.deepEqual(collectImageSources({ role: "assistant", text: "没有图" }), []);
  assert.deepEqual(collectImageSources({ role: "tool", parts: [{ type: "text", text: "x" }] }), []);
});
