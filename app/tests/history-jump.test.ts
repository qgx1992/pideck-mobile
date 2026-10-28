/** 跳转到历史消息：目标收集、过滤、角色标记。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  collectJumpTargets,
  filterJumpTargets,
  messageBodyText,
  roleLabel,
} from "../src/ui/history-jump-model.ts";

const messages = [
  { id: "m1", role: "user", text: "第一个问题", timestamp: 1000 },
  { id: "m2", role: "assistant", text: "第一个回答", timestamp: 2000 },
  { id: "m3", role: "tool", text: "工具输出", timestamp: 2500 },
  { id: "m4", role: "user", text: "第二个问题", timestamp: 3000 },
  { id: "m5", role: "assistant", text: "", timestamp: 3500 },
  { id: "m6", role: "system", text: "系统提示", timestamp: 3600 },
];

test("只收 user / assistant，且倒序", () => {
  const items = collectJumpTargets(messages as never);
  assert.deepEqual(
    items.map((i) => i.id),
    ["m4", "m2", "m1"],
  );
});

test("跳过 tool / system / 空正文", () => {
  const ids = collectJumpTargets(messages as never).map((i) => i.id);
  assert.ok(!ids.includes("m3"), "tool 不进列表");
  assert.ok(!ids.includes("m6"), "system 不进列表");
  assert.ok(!ids.includes("m5"), "空正文跳过");
});

test("不去重：同一句话出现两次都要能跳", () => {
  const items = collectJumpTargets([
    { id: "a", role: "user", text: "重复" },
    { id: "b", role: "user", text: "重复" },
  ] as never);
  assert.deepEqual(
    items.map((i) => i.id),
    ["b", "a"],
  );
});

test("没有 id 的消息被跳过（无法定位）", () => {
  const items = collectJumpTargets([{ role: "user", text: "没有 id" }] as never);
  assert.deepEqual(items, []);
});

test("摘要压成一行并截断，原文不参与显示", () => {
  const long = "第一行\n第二行\n" + "y".repeat(200);
  const items = collectJumpTargets([{ id: "x", role: "user", text: long }] as never);
  assert.ok(!items[0]!.preview.includes("\n"));
  assert.ok(items[0]!.preview.length <= 90);
});

test("messageBodyText 优先顶层 text，回落 content", () => {
  assert.equal(messageBodyText({ role: "user", text: "  a  " } as never), "a");
  assert.equal(messageBodyText({ role: "user", content: "b" } as never), "b");
  assert.equal(messageBodyText({ role: "user" } as never), "");
});

test("预览不包含 <thinking> 标签", () => {
  const items = collectJumpTargets([
    { id: "x", role: "assistant", text: "<thinking>内部推理</thinking>真正的回答" },
  ] as never);
  assert.equal(items[0]?.preview, "真正的回答");
  assert.ok(!items[0]!.preview.includes("thinking"));
});

test("只有思考没有正文的消息不进列表", () => {
  const items = collectJumpTargets([
    { id: "x", role: "assistant", text: "<thinking>全是推理</thinking>" },
  ] as never);
  assert.deepEqual(items, []);
});

test("过滤按摘要匹配，空查询返回全部", () => {
  const items = collectJumpTargets(messages as never);
  assert.equal(filterJumpTargets(items, "第二个").length, 1);
  assert.equal(filterJumpTargets(items, "  ").length, items.length);
  assert.equal(filterJumpTargets(items, "zzz").length, 0);
});

test("角色标签", () => {
  assert.equal(roleLabel("user"), "我");
  assert.equal(roleLabel("assistant"), "AI");
});
