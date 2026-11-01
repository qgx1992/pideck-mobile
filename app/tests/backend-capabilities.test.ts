/**
 * 后端能力矩阵：哪些入口在哪个后端上是有意义的。
 *
 * 这张表存在的理由就是「点了没反应」——ACP 的模型 chip、思考 chip、计划模式、
 * 供应商额度、待办条都是空转的，露出来只会让人以为 App 坏了。
 */

import test from "node:test";
import assert from "node:assert/strict";

const { normalizeBackend, backendCapabilities, backendBadge, backendBadgeHint, acpToolNameOf } = await import(
  "../src/ui/backend-capabilities.ts"
);

test("认不出来的后端按 pi 处理（老桌面端不下发该字段）", () => {
  assert.equal(normalizeBackend(undefined), "pi");
  assert.equal(normalizeBackend(null), "pi");
  assert.equal(normalizeBackend(""), "pi");
  assert.equal(normalizeBackend("pi"), "pi");
  assert.equal(normalizeBackend("  DSH "), "dsh");
  assert.equal(normalizeBackend("ACP"), "acp");
  assert.equal(normalizeBackend("imagegen"), "imagegen");
  assert.equal(normalizeBackend(42), "pi");
});

test("pi 会话所有能力都在", () => {
  const caps = backendCapabilities("pi");
  assert.deepEqual(Object.values(caps).filter((value) => !value), []);
});

test("dsh 会话与 pi 一样放行（DSH 的模型/思考是真实生效的）", () => {
  assert.deepEqual(backendCapabilities("dsh"), backendCapabilities("pi"));
});

test("acp 会话：模型/思考/计划/插话/分叉/改消息/额度/待办全部关掉", () => {
  const caps = backendCapabilities("acp");
  for (const key of [
    "model",
    "thinking",
    "plan",
    "steer",
    "fork",
    "editMessage",
    "usage",
    "todo",
  ] as const) {
    assert.equal(caps[key], false, `${key} 在 ACP 会话上必须关掉`);
  }
});

test("能力对象是副本，改一个后端的不会污染另一个", () => {
  const first = backendCapabilities("pi");
  first.model = false;
  assert.equal(backendCapabilities("pi").model, true);
  assert.equal(backendCapabilities("acp").model, false);
});

test("侧栏徽标：pi 不显示，dsh/acp/生图各一个标记", () => {
  assert.equal(backendBadge(undefined), "");
  assert.equal(backendBadge("pi"), "");
  assert.equal(backendBadge("dsh"), "DSH");
  assert.equal(backendBadge("acp"), "ACP");
  assert.equal(backendBadge("imagegen"), "生图");
});

test("徽标提示串：ACP 带上 CLI 名字（两个 ACP 会话光看徽标分不出来）", () => {
  assert.equal(backendBadgeHint("pi"), "");
  assert.equal(backendBadgeHint("dsh"), "DSH 后端");
  assert.equal(backendBadgeHint("acp"), "ACP 后端");
  assert.equal(backendBadgeHint("acp", "Gemini CLI"), "ACP 后端 · Gemini CLI");
  assert.equal(backendBadgeHint("acp", "   "), "ACP 后端");
});

test("acpTools 是 {id,name}[] 清单：按 id 取名字，取不到返回 null 而不是裸 id", () => {
  const tools = [
    { id: "tool-1", name: "Gemini CLI" },
    { id: "tool-2", name: "" },
    { id: "tool-3" },
    "garbage",
    null,
  ];
  assert.equal(acpToolNameOf(tools, "tool-1"), "Gemini CLI");
  assert.equal(acpToolNameOf(tools, " tool-1 "), "Gemini CLI");
  assert.equal(acpToolNameOf(tools, "tool-2"), null, "空名字不回退成 id");
  assert.equal(acpToolNameOf(tools, "tool-3"), null);
  assert.equal(acpToolNameOf(tools, "tool-9"), null);
  assert.equal(acpToolNameOf(tools, ""), null);
  assert.equal(acpToolNameOf(tools, undefined), null);
  assert.equal(acpToolNameOf(undefined, "tool-1"), null);
  assert.equal(acpToolNameOf([], "tool-1"), null);
});
