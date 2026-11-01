/**
 * 后端选择（b 方案）：新建会话的候选列表、切换补丁、锁定条件。
 *
 * 这里每条断言都对着一个真实约束：`/api/sessions` 不认识字段就 400、
 * ACP 必须带 acpToolId、从 ACP 切走要清模型绑定、有 runtime 就不能再改后端。
 */

import test from "node:test";
import assert from "node:assert/strict";

const {
  DEFAULT_BACKEND_CHOICE,
  backendOptions,
  backendPatch,
  backendSwitchAllowed,
  backendLockedHint,
  choiceKey,
  createSessionInput,
  describeChoice,
  indexOfChoice,
  missingAcpTool,
  sameChoice,
} = await import("../src/ui/backend-selector.ts");

const acpTools = [
  { id: "tool-1", name: "Gemini CLI" },
  { id: "tool-2", name: "Kimi" },
];

test("默认只有 pi 与 DSH；没登记 ACP 工具时不出 ACP 入口", () => {
  const keys = backendOptions(undefined).map((option) => option.key);
  assert.deepEqual(keys, ["pi", "dsh"]);
  assert.deepEqual(backendOptions([]).map((option) => option.key), ["pi", "dsh"]);
});

test("ACP 工具展开成一行行：每行绑定具体 CLI", () => {
  const options = backendOptions(acpTools);
  assert.deepEqual(
    options.map((option) => option.key),
    ["pi", "dsh", "acp:tool-1", "acp:tool-2"],
  );
  assert.equal(options[2]!.label, "ACP · Gemini CLI");
  assert.deepEqual(options[2]!.choice, { backend: "acp", acpToolId: "tool-1" });
  assert.ok(options[2]!.hint.length > 0, "每行要有一句说明");
});

test("工具清单里的脏数据被丢掉或补名，不产生不可选的行", () => {
  const options = backendOptions([
    { id: "ok", name: "正常" },
    { name: "没 id" },
    null,
    "garbage",
    { id: "  ", name: "空白 id" },
    { id: "noname" },
  ]);
  assert.deepEqual(
    options.map((option) => option.key),
    ["pi", "dsh", "acp:ok", "acp:noname"],
  );
  // 没有名字时退回 id：选择列表里总得让人看到点什么
  assert.equal(options[3]!.label, "ACP · noname");
});

test("include 可收紧列表（生图会话在手机上只读，默认不进新建流程）", () => {
  assert.deepEqual(backendOptions(undefined, ["pi"]).map((o) => o.key), ["pi"]);
  const withImage = backendOptions(undefined, ["pi", "imagegen"]);
  assert.deepEqual(withImage.map((o) => o.key), ["pi", "imagegen"]);
  assert.equal(withImage[1]!.label, "生图");
  // ACP 永远由工具列表展开，即使 include 里点名也不重复出现
  assert.deepEqual(backendOptions(acpTools, ["pi", "acp"]).map((o) => o.key), ["pi", "acp:tool-1", "acp:tool-2"]);
});

test("选择键：非 ACP 就是后端名，ACP 带工具 id", () => {
  assert.equal(choiceKey({ backend: "pi", acpToolId: null }), "pi");
  assert.equal(choiceKey({ backend: "dsh", acpToolId: "stale" }), "dsh", "非 ACP 忽略残留的 acpToolId");
  assert.equal(choiceKey({ backend: "acp", acpToolId: "tool-1" }), "acp:tool-1");
  assert.equal(choiceKey({ backend: "acp", acpToolId: null }), "acp");
  assert.equal(choiceKey({ backend: "acp", acpToolId: "  " }), "acp");
});

test("同一个后端的不同 ACP 工具算不同选择（切换时必须能区分）", () => {
  assert.equal(sameChoice({ backend: "acp", acpToolId: "a" }, { backend: "acp", acpToolId: "a" }), true);
  assert.equal(sameChoice({ backend: "acp", acpToolId: "a" }, { backend: "acp", acpToolId: "b" }), false);
  assert.equal(sameChoice(DEFAULT_BACKEND_CHOICE, { backend: "pi", acpToolId: null }), true);
});

test("indexOfChoice：找得到给下标，工具被删了给 -1", () => {
  const options = backendOptions(acpTools);
  assert.equal(indexOfChoice(options, { backend: "pi", acpToolId: null }), 0);
  assert.equal(indexOfChoice(options, { backend: "acp", acpToolId: "tool-2" }), 3);
  assert.equal(indexOfChoice(options, { backend: "acp", acpToolId: "gone" }), -1);
});

test("展示文字：ACP 翻成 CLI 名字，认不出工具就只说 ACP", () => {
  assert.equal(describeChoice({ backend: "pi", acpToolId: null }), "Pi");
  assert.equal(describeChoice({ backend: "dsh", acpToolId: null }), "DSH");
  assert.equal(describeChoice({ backend: "acp", acpToolId: "tool-1" }, acpTools), "ACP · Gemini CLI");
  assert.equal(describeChoice({ backend: "acp", acpToolId: "gone" }, acpTools), "ACP");
  assert.equal(describeChoice({ backend: "acp", acpToolId: null }), "ACP");
  assert.equal(describeChoice({ backend: "imagegen", acpToolId: null }), "生图");
});

test("切换补丁：换后端要清模型/思考绑定（否则旧绑定带到新后端上）", () => {
  const patch = backendPatch({ backend: "dsh", acpToolId: null }, { backend: "pi", acpToolId: null });
  assert.deepEqual(patch, { backend: "dsh", acpToolId: null, model: null, thinkingLevel: null });
});

test("切到 ACP：带上工具 id；仍是 ACP 但换工具时也清模型", () => {
  const toAcp = backendPatch({ backend: "acp", acpToolId: "tool-2" }, { backend: "pi", acpToolId: null });
  assert.deepEqual(toAcp, { backend: "acp", acpToolId: "tool-2", model: null, thinkingLevel: null });

  const switchTool = backendPatch({ backend: "acp", acpToolId: "tool-2" }, { backend: "acp", acpToolId: "tool-1" });
  assert.deepEqual(switchTool, { backend: "acp", acpToolId: "tool-2", model: null, thinkingLevel: null });
});

test("从 ACP 切回 pi：acpToolId 显式置 null，不带残留绑定", () => {
  const patch = backendPatch({ backend: "pi", acpToolId: null }, { backend: "acp", acpToolId: "tool-1" });
  assert.deepEqual(patch, { backend: "pi", acpToolId: null, model: null, thinkingLevel: null });
});

test("同后端同工具再点一次：只动 backend/acpToolId，不清模型", () => {
  const patch = backendPatch({ backend: "dsh", acpToolId: null }, { backend: "dsh", acpToolId: null });
  assert.deepEqual(patch, { backend: "dsh", acpToolId: null });
});

test("新建会话请求体：pi 什么都不带（老桌面端不认识会 400）", () => {
  assert.deepEqual(createSessionInput("p1", { backend: "pi", acpToolId: null }), { projectId: "p1" });
  assert.deepEqual(createSessionInput("p1", { backend: "dsh", acpToolId: null }), {
    projectId: "p1",
    backend: "dsh",
  });
  assert.deepEqual(createSessionInput("p1", { backend: "acp", acpToolId: "tool-1" }), {
    projectId: "p1",
    backend: "acp",
    acpToolId: "tool-1",
  });
  // 工具 id 前后空格要修掉，否则服务端按 id 精确匹配会找不到
  assert.deepEqual(createSessionInput("p1", { backend: "acp", acpToolId: " tool-1 " }), {
    projectId: "p1",
    backend: "acp",
    acpToolId: "tool-1",
  });
  // 没给选择时按默认 pi 走
  assert.deepEqual(createSessionInput("p1"), { projectId: "p1" });
});

test("选了 ACP 却没工具：拦住（服务端会 400 sessionToolRequired）", () => {
  assert.equal(missingAcpTool({ backend: "acp", acpToolId: null }), true);
  assert.equal(missingAcpTool({ backend: "acp", acpToolId: "  " }), true, "空白也要当没选");
  assert.equal(missingAcpTool({ backend: "acp", acpToolId: "tool-1" }), false);
  assert.equal(missingAcpTool({ backend: "pi", acpToolId: null }), false);
});

test("有 runtime 就锁定：没跑过的草稿才能改后端", () => {
  const runtimes = [{ sessionId: "s1" }, { sessionId: "s2" }];
  assert.equal(backendSwitchAllowed(runtimes, "s1"), false);
  assert.equal(backendSwitchAllowed(runtimes, "s3"), true);
  assert.equal(backendSwitchAllowed([], "s1"), true);
});

test("锁定提示要说清当前后端和「只能在新建时选」", () => {
  const hint = backendLockedHint({ backend: "acp", acpToolId: "tool-1" }, acpTools);
  assert.match(hint, /后端已锁定/);
  assert.match(hint, /ACP · Gemini CLI/);
  assert.match(hint, /新建时选/);
});
