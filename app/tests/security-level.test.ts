import test from "node:test";
import assert from "node:assert/strict";

import {
  parseSecurityState,
  securityChipLabel,
  securityChoices,
  securityDisabledHint,
  securityLevelFor,
  securityWritable,
  composerToolChips,
} from "../src/ui/security-level-model.ts";

const STATE = {
  enabled: true,
  defaultLevelId: "standard",
  levels: [
    { id: "off", name: "关闭", description: "所有工具直接放行", builtin: true },
    { id: "standard", name: "标准", description: "写操作需要确认", builtin: true },
    { id: "strict", name: "严格", description: "", builtin: true },
  ],
  sessionOverrides: { "s-2": "strict" },
};

test("security：解析 state.security（含缺失的 description 兜底）", () => {
  const parsed = parseSecurityState(STATE);
  assert.ok(parsed);
  assert.equal(parsed!.defaultLevelId, "standard");
  assert.deepEqual(parsed!.levels.map((level) => level.id), ["off", "standard", "strict"]);
  assert.equal(parsed!.levels[2]!.name, "严格");
  assert.deepEqual(parsed!.overrides, { "s-2": "strict" });
  // 0.9.8 及更早没有这个字段
  assert.equal(parseSecurityState(undefined), null);
  assert.equal(parseSecurityState(null), null);
  assert.equal(parseSecurityState([]), null);
});

test("security：会话级覆盖优先，没覆盖时跟随全局默认", () => {
  const state = parseSecurityState(STATE);
  const inherited = securityLevelFor(state, "s-1");
  assert.equal(inherited.level?.id, "standard");
  assert.equal(inherited.inherited, true);

  const overridden = securityLevelFor(state, "s-2");
  assert.equal(overridden.level?.id, "strict");
  assert.equal(overridden.inherited, false);

  assert.equal(securityLevelFor(state, null).level, null);
});

test("security：选择项第一项固定是「跟随全局默认」", () => {
  const state = parseSecurityState(STATE);
  const choices = securityChoices(state, "s-1");
  assert.equal(choices[0]!.id, null);
  assert.match(choices[0]!.label, /跟随全局默认（标准）/);
  assert.equal(choices[0]!.active, true, "没有覆盖时「跟随默认」是当前项");
  assert.deepEqual(
    choices.slice(1).map((choice) => [choice.id, choice.active]),
    [
      ["off", false],
      ["standard", false],
      ["strict", false],
    ],
  );

  const overridden = securityChoices(state, "s-2");
  assert.equal(overridden[0]!.active, false);
  assert.equal(overridden.find((choice) => choice.id === "strict")!.active, true);
  assert.deepEqual(securityChoices(null, "s-1"), []);
});

test("security：chip 文案与 dsh/acp 不可写", () => {
  assert.equal(securityChipLabel({ id: "standard", name: "标准", description: "", builtin: true }), "安全 标准");
  assert.equal(securityChipLabel(null), "安全");

  assert.equal(securityWritable(undefined), true, "pi 会话没有 backend 字段");
  assert.equal(securityWritable("pi"), true);
  assert.equal(securityWritable("dsh"), false);
  assert.equal(securityWritable(" DSH "), false);
  assert.equal(securityWritable("acp"), false);
});

test("security：关闭扩展 / 版本过旧时的提示", () => {
  assert.match(securityDisabledHint(null), /0\.9\.9/);
  assert.equal(securityDisabledHint(parseSecurityState({ ...STATE, enabled: false })), "桌面端已关闭「安全管理」扩展，这里只展示等级，切换不生效。");
  assert.equal(securityDisabledHint(parseSecurityState(STATE)), "");
});

test("输入框 chip 显隐：pi 会话显示安全、dsh 会话显示 DSH，互斥", () => {
  const state = parseSecurityState({
    enabled: true,
    defaultLevelId: "off",
    levels: [{ id: "off", name: "关闭" }],
    sessionOverrides: {},
  });
  assert.deepEqual(composerToolChips("pi", state, true), { security: true, dsh: false });
  assert.deepEqual(composerToolChips("dsh", state, true), { security: false, dsh: true });
  assert.deepEqual(composerToolChips("acp", state, true), { security: false, dsh: false });
  // 旧桌面端没有 state.security：安全 chip 不显示
  assert.deepEqual(composerToolChips("pi", null, true), { security: false, dsh: false });
  // 没选会话时两个都不显示
  assert.deepEqual(composerToolChips("dsh", state, false), { security: false, dsh: false });
});
