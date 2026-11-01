/**
 * 顶栏快照的组装规则。
 *
 * 这里测的是「WebView 算出来的东西有没有被正确摊平」—— chip 的顺序、能力的收敛、
 * 上下文短标签。真正的业务规则在 `ui/header-model.ts` / `ui/model-picker.ts` 里，
 * 那边已有自己的测试，不在这里重复。
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
  buildChatHeaderView,
  buildModelPicker,
  buildSecurityPicker,
  buildThinkingPicker,
  contextChipLabel,
  shortTokens,
} from "../src/platform/flutter-chat-header.ts";
import type { ChatHeaderInput } from "../src/platform/flutter-chat-header.ts";
import type { ModelPickerSection } from "../src/ui/model-picker.ts";
import type { SecurityChoice } from "../src/ui/security-level-model.ts";

function input(overrides: Partial<ChatHeaderInput> = {}): ChatHeaderInput {
  return {
    title: "会话标题",
    meta: "PiDeck · 运行中",
    running: true,
    caps: { model: true, thinking: true, plan: true, steer: true, dsh: false },
    model: { label: "claude-sonnet", placeholder: false },
    thinking: { level: "high", label: "高" },
    security: null,
    dsh: false,
    planOn: false,
    context: null,
    sendMode: "queue",
    promptCount: 3,
    todo: null,
    subagents: null,
    ...overrides,
  };
}

test("chip 顺序：模型 → 思考 → 安全 → 计划 → DSH", () => {
  const view = buildChatHeaderView(
    input({ security: { label: "标准", muted: false }, dsh: true, caps: { model: true, thinking: true, plan: true, steer: true, dsh: true } }),
  );
  assert.deepEqual(
    view.chips.map((chip) => chip.key),
    ["model", "thinking", "security", "plan", "dsh"],
  );
});

test("ACP 会话（没有模型/思考/计划能力）只剩标题，chip 全不出现", () => {
  const view = buildChatHeaderView(
    input({ caps: { model: false, thinking: false, plan: false, steer: false, dsh: false } }),
  );
  assert.deepEqual(view.chips, []);
  assert.equal(view.showSendMode, false);
});

test("模型占位与思考未设打 muted，计划开启打 active", () => {
  const view = buildChatHeaderView(
    input({
      model: { label: "默认模型", placeholder: true },
      thinking: { level: "", label: "" },
      planOn: true,
    }),
  );
  const byKey = new Map(view.chips.map((chip) => [chip.key, chip]));
  assert.equal(byKey.get("model")?.muted, true);
  assert.equal(byKey.get("thinking")?.label, "思考");
  assert.equal(byKey.get("thinking")?.muted, true);
  assert.equal(byKey.get("plan")?.active, true);
});

test("思考档位有值时文案带档位名", () => {
  const view = buildChatHeaderView(input());
  assert.equal(view.chips.find((chip) => chip.key === "thinking")?.label, "思考 高");
});

test("「排队 / 插话」开关只在运行中且后端支持插话时出现", () => {
  assert.equal(buildChatHeaderView(input()).showSendMode, true);
  assert.equal(buildChatHeaderView(input({ running: false })).showSendMode, false);
  assert.equal(
    buildChatHeaderView(input({ caps: { model: true, thinking: true, plan: true, steer: false, dsh: false } }))
      .showSendMode,
    false,
  );
});

test("上下文 chip：百分比取整、溢出标 active", () => {
  const view = buildChatHeaderView(
    input({
      context: {
        contextWindow: 200_000,
        contextTokens: 190_000,
        contextPercent: 95,
        contextOverflow: true,
        isCompacting: false,
      } as never,
    }),
  );
  assert.equal(view.context?.percent, 95);
  assert.equal(view.context?.overflow, true);
  assert.equal(view.context?.label, "190K / 200K");
});

test("没有上下文数据时 context 为 null（不显示 chip）", () => {
  assert.equal(buildChatHeaderView(input()).context, null);
});

test("shortTokens 的四个量级", () => {
  assert.equal(shortTokens(0), "0");
  assert.equal(shortTokens(999), "999");
  assert.equal(shortTokens(12_300), "12.3K");
  assert.equal(shortTokens(128_000), "128K");
  assert.equal(shortTokens(1_000_000), "1.0M");
});

test("contextChipLabel 拼成「已用 / 窗口」", () => {
  assert.equal(
    contextChipLabel({ contextTokens: 8_200, contextWindow: 128_000 } as never),
    "8.2K / 128K",
  );
});

const sections: ModelPickerSection[] = [
  {
    provider: "anthropic",
    models: [
      {
        key: "anthropic\u0000claude-sonnet",
        provider: "anthropic",
        modelId: "claude-sonnet",
        name: "Claude Sonnet",
        thinkingLevels: ["low", "high"],
        contextWindow: 200_000,
      },
    ],
  },
  {
    provider: "openai",
    models: [
      {
        key: "openai\u0000gpt-5",
        provider: "openai",
        modelId: "gpt-5",
        name: "GPT-5",
        thinkingLevels: ["low"],
      },
    ],
  },
];

test("模型面板：按 provider 分组、副标题带上下文窗口、选中的打 selected", () => {
  const view = buildModelPicker(sections, "anthropic\u0000claude-sonnet");
  assert.equal(view.kind, "model");
  assert.equal(view.searchable, true);
  assert.deepEqual(
    view.items.map((item) => [item.section, item.label, item.selected === true]),
    [
      ["anthropic", "Claude Sonnet", true],
      ["openai", "GPT-5", false],
    ],
  );
  assert.match(view.items[0]?.description ?? "", /anthropic · 200K 上下文/);
  // 没有 contextWindow 的不编造窗口大小
  assert.equal(view.items[1]?.description, "openai");
});

test("模型面板：没有可用模型时给空态文案", () => {
  const view = buildModelPicker([], "");
  assert.equal(view.items.length, 0);
  assert.match(view.empty ?? "", /没有可用模型/);
});

test("思考面板：只列传入的档位，当前档位打 selected", () => {
  const view = buildThinkingPicker(
    ["off", "high"],
    "high",
    (level) => (level === "off" ? "关闭" : "高"),
    () => "说明",
  );
  assert.deepEqual(
    view.items.map((item) => [item.id, item.label, item.selected === true]),
    [
      ["off", "关闭", false],
      ["high", "高", true],
    ],
  );
});

const choices: SecurityChoice[] = [
  { id: null, label: "跟随全局默认", description: "用设置里的默认级别", active: false },
  { id: "standard", label: "标准", description: "普通读写", active: true },
];

test("安全面板：第一项是「跟随全局默认」，id 为空串", () => {
  const view = buildSecurityPicker(choices);
  assert.equal(view.items[0]?.id, "");
  assert.equal(view.items[0]?.label, "跟随全局默认");
  assert.equal(view.items[1]?.selected, true);
});
