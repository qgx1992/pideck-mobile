/**
 * 对话里的图片渲染（jsdom）。
 *
 * 关键点：助手/用户消息里的图片以前被 messageText() 直接丢掉（README 已知缺口第 1 条），
 * 现在要从 parts / images 里挖出来渲染成缩略图，点开能看原图。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM('<!doctype html><html><body><div id="app"></div></body></html>');
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.requestAnimationFrame = (cb: () => void) => setTimeout(cb, 0);
g.HTMLElement = dom.window.HTMLElement;
g.Node = dom.window.Node;

const { ChatView } = await import("../src/ui/chat-view.ts");
const { closeImageLightbox } = await import("../src/ui/image-lightbox.ts");

const PNG =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

function makeView() {
  const root = dom.window.document.getElementById("app")!;
  root.textContent = "";
  const view = new ChatView(root as unknown as HTMLElement, { onRespond: () => undefined });
  return { root, view };
}

test("历史消息里的图片被渲染成缩略图", () => {
  const { root, view } = makeView();
  view.setHistory([
    { id: "m1", role: "user", text: "看这张", parts: [{ type: "image", data: PNG }] },
  ]);
  const images = [...root.querySelectorAll("img.msg-image")];
  assert.equal(images.length, 1);
  assert.equal((images[0] as unknown as HTMLImageElement).src, PNG);
  // 正文照旧
  assert.match(root.textContent ?? "", /看这张/);
});

test("只有图没有文字的消息也会渲染（以前会被 continue 掉）", () => {
  const { root, view } = makeView();
  view.setHistory([{ id: "m2", role: "user", parts: [{ type: "file", mediaType: "image/png", url: PNG }] }]);
  assert.equal(root.querySelectorAll("img.msg-image").length, 1);
});

test("一条消息多张图都渲染，且按顺序", () => {
  const { root, view } = makeView();
  const second = PNG.replace("iVBORw0KGgo", "iVBORw0KGgo"); // 同一张，但来自不同字段
  view.setHistory([
    {
      id: "m3",
      role: "user",
      parts: [
        { type: "image", data: PNG },
        { type: "image", data: second + "" },
      ],
    },
  ]);
  // 完全相同的 data URL 会去重成一张（避免回放重复）
  assert.equal(root.querySelectorAll("img.msg-image").length, 1);
});

test("本地回显也能带图", () => {
  const { root, view } = makeView();
  view.appendLocalUser("刚拍的", [PNG]);
  assert.equal(root.querySelectorAll("img.msg-image").length, 1);
});

test("点缩略图打开 lightbox，点遮罩关闭", () => {
  const { root, view } = makeView();
  view.setHistory([{ id: "m4", role: "user", parts: [{ type: "image", data: PNG }] }]);

  const img = root.querySelector("img.msg-image") as unknown as HTMLElement;
  img.click();
  const lightbox = dom.window.document.querySelector(".lightbox");
  assert.ok(lightbox, "应该出现 lightbox");
  assert.equal((lightbox!.querySelector(".lightbox-img") as unknown as HTMLImageElement).src, PNG);

  (lightbox as unknown as HTMLElement).click();
  assert.equal(dom.window.document.querySelector(".lightbox"), null);
  closeImageLightbox();
});

test("纯文本消息不会多出图片容器", () => {
  const { root, view } = makeView();
  view.setHistory([{ id: "m5", role: "assistant", text: "没有图" }]);
  assert.equal(root.querySelectorAll(".msg-images").length, 0);
});
