/**
 * 首屏空态：图标 + 标题 + 副标题 + 三条快捷提示词。
 *
 * 关键行为：点提示词只回填输入框（回调里做），不在 view 里直接发送；
 * 不传 suggestions 时保持旧行为（只有图标和一句引导）。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body><div id=\"app\"></div></body></html>");
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.requestAnimationFrame = (cb: () => void) => setTimeout(cb, 0);
g.HTMLElement = dom.window.HTMLElement;
g.Node = dom.window.Node;

const { ChatView } = await import("../src/ui/chat-view.ts");

function makeView() {
  const root = dom.window.document.getElementById("app")!;
  root.textContent = "";
  return { root, view: new ChatView(root as unknown as HTMLElement, { onRespond: () => undefined }) };
}

const SUGGESTIONS = ["介绍一下这个项目的整体情况", "运行测试并修复失败的部分", "审查我尚未提交的改动"];

test("渲染标题、副标题与三条提示词", () => {
  const { root, view } = makeView();
  const picked: string[] = [];
  view.setEmptyState("发送一条消息即可开始新的一轮。", {
    title: "开始使用你的 PiDeck",
    suggestions: SUGGESTIONS,
    onSuggestion: (prompt) => picked.push(prompt),
  });

  assert.equal(root.querySelector(".empty-state-title")?.textContent, "开始使用你的 PiDeck");
  assert.match(root.querySelector(".empty-state-text")?.textContent ?? "", /发送一条消息/);
  assert.ok(root.querySelector(".empty-state-badge"), "图标要包在圆底里");

  const chips = [...root.querySelectorAll(".suggestion-chip")] as unknown as HTMLElement[];
  assert.equal(chips.length, 3);
  assert.deepEqual(chips.map((chip) => chip.textContent), SUGGESTIONS);

  chips[1]!.click();
  assert.deepEqual(picked, [SUGGESTIONS[1]]);
});

test("不传提示词时保持旧行为", () => {
  const { root, view } = makeView();
  view.setEmptyState("选择左侧会话，或直接输入消息新建一个。");
  assert.equal(root.querySelectorAll(".suggestion-chip").length, 0);
  // 标题有默认值
  assert.equal(root.querySelector(".empty-state-title")?.textContent, "开始使用你的 PiDeck");
});

test("空数组也不渲染提示词区", () => {
  const { root, view } = makeView();
  view.setEmptyState("x", { suggestions: [], onSuggestion: () => undefined });
  assert.equal(root.querySelector(".empty-state-suggestions"), null);
});
