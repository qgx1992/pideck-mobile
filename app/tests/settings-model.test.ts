/**
 * 设置页数据模型。
 *
 * 重点是「后端形状未固定时不能崩」：技能/扩展/DSH 插件的返回体在文档里没固化，
 * 字段缺失、类型不对、脏数据都不该让整页挂掉。
 */

import test from "node:test";
import assert from "node:assert/strict";

const {
  DESKTOP_ONLY_NOTE,
  applyExtensionToggle,
  applySkillToggle,
  dshPluginActionable,
  maskToken,
  normalizeDshPlugins,
  normalizeExtensions,
  normalizeModels,
  normalizeSkills,
} = await import("../src/ui/settings-model.ts");

test("技能：按来源分组、保持后端顺序、脏数据丢弃", () => {
  const sections = normalizeSkills({
    locations: [{ id: "pi-global", label: "pi 全局" }],
    skills: [
      { name: "usage-probe", sourceId: "pi-global", description: "查用量", enabled: true, valid: true },
      { name: "image-gen", sourceId: "pideck-global", description: "生图", enabled: false },
      { name: "another", sourceId: "pi-global" },
      { name: "", sourceId: "pi-global" },
      { name: "no-source" },
      null,
    ],
  });
  assert.deepEqual(
    sections.map((section) => section.sourceId),
    ["pi-global", "pideck-global"],
  );
  assert.deepEqual(
    sections[0]!.rows.map((row) => row.name),
    ["usage-probe", "another"],
  );
  assert.equal(sections[0]!.label, "pi 全局");
  // 没给 sourceLabel 时用内置中文兜底
  assert.equal(sections[1]!.label, "PiDeck 内置");
  // enabled 缺省视为开；valid 缺省视为可用
  assert.equal(sections[1]!.rows[0]!.enabled, false);
  assert.equal(sections[0]!.rows[1]!.valid, true);
});

test("技能：key 由 sourceId + name 组成（后端不下发路径）", () => {
  const sections = normalizeSkills({
    skills: [{ name: "x", sourceId: "project-pi" }],
  });
  assert.equal(sections[0]!.rows[0]!.key, "project-pi::x");
});

test("技能：非法输入返回空数组", () => {
  assert.deepEqual(normalizeSkills(null), []);
  assert.deepEqual(normalizeSkills({}), []);
  assert.deepEqual(normalizeSkills({ skills: "nope" }), []);
});

test("技能开关回写只改目标那一行", () => {
  const sections = normalizeSkills({
    skills: [
      { name: "a", sourceId: "pi-global", enabled: true },
      { name: "b", sourceId: "pi-global", enabled: true },
    ],
  });
  const next = applySkillToggle(sections, "pi-global::a", false);
  assert.equal(next[0]!.rows[0]!.enabled, false);
  assert.equal(next[0]!.rows[1]!.enabled, true);
  // 不改原对象
  assert.equal(sections[0]!.rows[0]!.enabled, true);
});

test("扩展：scope 归一、projectId 保留、name 兜底", () => {
  const rows = normalizeExtensions({
    extensions: [
      { source: "builtin:plan-mode", name: "计划模式", scope: "user", enabled: true },
      { source: "/tmp/ext/foo.ts", scope: "project", projectId: "p1", enabled: false },
      { source: "/tmp/ext/bar.ts", scope: "weird" },
      { source: "" },
    ],
  });
  assert.equal(rows.length, 3);
  assert.equal(rows[0]!.scope, "user");
  assert.equal(rows[1]!.scope, "project");
  assert.equal(rows[1]!.projectId, "p1");
  // scope 不认识 → unknown
  assert.equal(rows[2]!.scope, "unknown");
  // name 缺失时用 source 末段
  assert.equal(rows[1]!.name, "foo.ts");
});

test("扩展开关回写", () => {
  const rows = normalizeExtensions({ extensions: [{ source: "a", scope: "user", enabled: true }] });
  assert.equal(applyExtensionToggle(rows, "user::a", false)[0]!.enabled, false);
});

test("DSH 插件：宽松解析，status=running 也算运行中", () => {
  const rows = normalizeDshPlugins([
    { id: "p1", name: "插件一", version: "1.2.0", running: true },
    { id: "p2", status: "running" },
    { name: "只有名字" },
    { id: "" },
  ]);
  assert.deepEqual(rows.map((row) => row.id), ["p1", "p2", "只有名字"]);
  assert.equal(rows[0]!.running, true);
  assert.equal(rows[1]!.running, true);
  assert.deepEqual(normalizeDshPlugins(null), []);
});

test("DSH 插件：0.9.9 的 {dynamic, static} 两段都要读出来", () => {
  const rows = normalizeDshPlugins({
    dynamic: [
      {
        pluginId: "note-abc",
        agentId: "agent-1",
        packages: [{ packageId: "pkg-1", name: "note", purpose: "配笔记用" }],
        currentPackageId: "pkg-1",
        activeRun: { pluginRunId: "r1", packageId: "pkg-1" },
        status: "running",
      },
      { pluginId: "idle-one", packages: [{ packageId: "pkg-2", name: "idle", purpose: "没事干" }], status: "stopped" },
    ],
    static: [
      { entryId: "row-1", moduleName: "dsh-todo", enabled: true, fiberPhase: "active", origin: "builtin" },
      { entryId: "row-2", moduleName: "my-plugin", enabled: false, fiberPhase: null, origin: "user" },
    ],
  });

  assert.equal(rows.length, 4);
  assert.deepEqual(rows.map((row) => row.kind), ["dynamic", "dynamic", "static", "static"]);
  assert.equal(rows[0]!.id, "note-abc");
  assert.equal(rows[0]!.name, "note", "名字取当前 package 的 name");
  assert.equal(rows[0]!.running, true);
  assert.equal(rows[0]!.description, "配笔记用");
  assert.equal(rows[0]!.packageId, "pkg-1");
  assert.equal(rows[1]!.running, false);
  assert.equal(rows[2]!.running, true);
  assert.match(rows[2]!.description, /随包内置/);
  assert.match(rows[3]!.description, /用户自装/);
  assert.equal(rows[3]!.running, false);
  // 只有动态插件可操作（静态条目没有对应的 Web 路由）
  assert.deepEqual(rows.map(dshPluginActionable), [true, true, false, false]);
});

test("模型列表：provider + modelId 组成 key", () => {
  const rows = normalizeModels([
    { provider: "deepseek", id: "deepseek-chat", name: "DeepSeek Chat" },
    { providerId: "anthropic", modelId: "claude-sonnet-4" },
    { name: "没有 id" },
  ]);
  assert.equal(rows.length, 2);
  assert.equal(rows[0]!.key, "deepseek::deepseek-chat");
  assert.equal(rows[0]!.name, "DeepSeek Chat");
  assert.equal(rows[1]!.name, "claude-sonnet-4");
});

test("令牌脱敏", () => {
  assert.equal(maskToken(""), "（未设置）");
  assert.equal(maskToken("short"), "*****");
  assert.equal(maskToken("abcdefghijklmnop"), "abcd…mnop");
});

test("桌面端限定说明不为空（设置页要显示）", () => {
  assert.match(DESKTOP_ONLY_NOTE, /桌面端/);
});
