/** 会话模型/思考档位的显示解析。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  modelChipLabel,
  modelChipTitle,
  resolveSessionPrefs,
} from "../src/ui/session-prefs.ts";

const catalogSession = {
  id: "s1",
  model: { provider: "magpie", modelId: "auto/x", modelName: "Deepseek-V4.1-Flash" },
  thinkingLevel: "high",
} as never;

test("catalog 有值时优先用它", () => {
  const prefs = resolveSessionPrefs(catalogSession, { modelName: "别的模型", thinkingLevel: "low" });
  assert.equal(prefs.modelName, "Deepseek-V4.1-Flash");
  assert.equal(prefs.thinkingLevel, "high");
  assert.equal(prefs.placeholder, false);
});

test("catalog 的 model 为 null 时用运行时学到的值（最常见情况）", () => {
  const sessionWithoutModel = { id: "s1", model: null, thinkingLevel: null } as never;
  const prefs = resolveSessionPrefs(sessionWithoutModel, {
    provider: "magpie",
    modelId: "tokenrhythm/deepseek-v4.1-flash-1017",
    modelName: "DeepSeek V4.1 Flash",
    thinkingLevel: "high",
  });
  assert.equal(prefs.modelName, "DeepSeek V4.1 Flash");
  assert.equal(prefs.thinkingLevel, "high");
  assert.equal(prefs.placeholder, false);
});

test("两边都没有时标记为占位，文案是「默认模型」而不是「选择模型」", () => {
  const prefs = resolveSessionPrefs({ id: "s1", model: null } as never);
  assert.equal(prefs.placeholder, true);
  assert.equal(modelChipLabel(prefs), "默认模型");
  assert.ok(modelChipTitle(prefs).includes("发第一条消息后显示"));
});

test("catalog 只有 modelId 时也能显示", () => {
  const prefs = resolveSessionPrefs({ id: "s1", model: { modelId: "auto/y" } } as never);
  assert.equal(prefs.modelName, "auto/y");
  assert.equal(prefs.placeholder, false);
});

test("标题过长会被截断，但不超过给定长度", () => {
  const prefs = resolveSessionPrefs({
    id: "s1",
    model: { modelName: "一个特别特别特别特别特别长的模型名称" },
  } as never);
  const label = modelChipLabel(prefs, 12);
  assert.ok(label.length <= 12, `实际 ${label.length}`);
  assert.ok(label.endsWith("…"));
});

test("chip title 在名称与 id 不同时带上 id", () => {
  const prefs = resolveSessionPrefs({
    id: "s1",
    model: { modelId: "auto/x", modelName: "显示名" },
  } as never);
  assert.ok(modelChipTitle(prefs).includes("显示名"));
  assert.ok(modelChipTitle(prefs).includes("auto/x"));
});
