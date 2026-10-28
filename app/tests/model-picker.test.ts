/** 模型/思考档位视图模型：thinkingLevelMap 过滤、分组、查找。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  buildModelSections,
  findModel,
  modelKey,
  modelSubtitle,
  thinkingHint,
  thinkingLabel,
  toModelOption,
} from "../src/ui/model-picker.ts";

const raw = [
  {
    provider: "magpie",
    id: "auto/deepseek-v4.1-flash",
    name: "Deepseek-V4.1-Flash",
    reasoning: true,
    thinkingLevels: ["low", "high"],
    thinkingLevelMap: { off: null, minimal: null, low: "low", medium: null, high: "high", xhigh: null, max: null },
    contextWindow: 1048576,
  },
  {
    provider: "magpie",
    id: "auto/glm-5.3-flash",
    name: "GLM-5.3-Flash",
    reasoning: true,
    thinkingLevels: ["off", "minimal", "low", "medium", "high"],
    thinkingLevelMap: { off: "off", minimal: "minimal", low: "low", medium: "medium", high: "high" },
    contextWindow: 1000000,
  },
  {
    provider: "other",
    id: "no-reasoning",
    name: "Plain Model",
    reasoning: false,
    thinkingLevels: [],
    thinkingLevelMap: { off: null, low: null, high: null },
  },
  { provider: "", id: "broken", name: "Broken" },
];

test("thinkingLevelMap 为 null 的档位被过滤掉", () => {
  const option = toModelOption(raw[0] as never);
  assert.deepEqual(option?.thinkingLevels, ["low", "high"]);
});

test("档位按固定顺序排列（off → max）", () => {
  const option = toModelOption(raw[1] as never);
  assert.deepEqual(option?.thinkingLevels, ["off", "minimal", "low", "medium", "high"]);
});

test("不支持 reasoning 的模型没有思考档位", () => {
  const option = toModelOption(raw[2] as never);
  assert.deepEqual(option?.thinkingLevels, []);
});

test("缺 provider/id 的模型被丢弃", () => {
  assert.equal(toModelOption(raw[3] as never), null);
});

test("分组按 provider，组内按名称排序", () => {
  const sections = buildModelSections(raw as never);
  assert.deepEqual(
    sections.map((s) => s.provider),
    ["magpie", "other"],
  );
  assert.deepEqual(
    sections[0]?.models.map((m) => m.name),
    ["Deepseek-V4.1-Flash", "GLM-5.3-Flash"],
  );
});

test("findModel 按 provider + modelId 精确匹配", () => {
  const sections = buildModelSections(raw as never);
  assert.equal(findModel(sections, "magpie", "auto/glm-5.3-flash")?.name, "GLM-5.3-Flash");
  assert.equal(findModel(sections, "magpie", "nope"), undefined);
  assert.equal(findModel(sections, undefined, undefined), undefined);
});

test("modelKey 不会因 provider/model 含分隔符而撞车", () => {
  assert.notEqual(modelKey("a", "b/c"), modelKey("a/b", "c"));
});

test("思考档位有中文标签与说明", () => {
  assert.equal(thinkingLabel("high"), "高");
  assert.equal(thinkingHint("high"), "复杂任务");
  assert.equal(thinkingLabel("unknown-level"), "unknown-level", "未知档位原样显示");
});

test("modelSubtitle 展示 provider 与上下文规模", () => {
  const sections = buildModelSections(raw as never);
  const subtitle = modelSubtitle(sections[0]!.models[0]!);
  assert.ok(subtitle.includes("magpie"));
  assert.ok(subtitle.includes("1M 上下文"), `实际：${subtitle}`);
});
