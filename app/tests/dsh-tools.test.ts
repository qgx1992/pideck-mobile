import test from "node:test";
import assert from "node:assert/strict";

import {
  goalPhaseLabel,
  goalRoundsText,
  parseDshGoal,
  parseDshSkills,
  parseDshSubagentHistory,
  parseDshSubagents,
  DSH_EMPTY_GOAL,
  DSH_EMPTY_SKILLS,
  DSH_EMPTY_SUBAGENTS,
} from "../src/ui/dsh-tools-model.ts";

test("dsh goal：解析目标与轮次", () => {
  const goal = parseDshGoal({
    goal: { objective: "把 CI 跑绿", phase: "paused", roundsStarted: 3, maxGoalRounds: 10 },
  });
  assert.deepEqual(goal, { objective: "把 CI 跑绿", phase: "paused", roundsStarted: 3, maxGoalRounds: 10 });
  assert.equal(goalPhaseLabel(goal!.phase), "已暂停");
  assert.equal(goalRoundsText(goal!), "第 3/10 轮");
});

test("dsh goal：没有目标 / 目标缺 objective 都算空", () => {
  assert.equal(parseDshGoal({ goal: null }), null);
  assert.equal(parseDshGoal({}), null);
  assert.equal(parseDshGoal({ goal: { phase: "active" } }), null);
  assert.equal(parseDshGoal(null), null);
  // 没有上限时只写已用轮次
  const noCap = parseDshGoal({ goal: { objective: "x", phase: "active", roundsStarted: 2 } });
  assert.equal(goalRoundsText(noCap!), "已进行 2 轮");
  assert.equal(goalPhaseLabel("complete"), "已完成");
  assert.equal(goalPhaseLabel("blocked"), "受阻");
  assert.equal(goalPhaseLabel("weird"), "进行中");
});

test("dsh subagents：运行中 / 诊断 / 模式文案", () => {
  const rows = parseDshSubagents({
    subagents: [
      { id: "a1", label: "查日志", activity: "running", mode: "continuable" },
      { id: "a2", activity: "idle", kind: "diagnostic", mode: "one-shot" },
      { id: "", label: "缺 id" },
      "不是对象",
    ],
  });
  assert.equal(rows.length, 2);
  assert.deepEqual(rows[0], { id: "a1", label: "查日志", running: true, diagnostic: false, modeLabel: "可续跑" });
  assert.equal(rows[1]!.label, "a2", "没有 label 时退回 id");
  assert.equal(rows[1]!.running, false);
  assert.equal(rows[1]!.diagnostic, true);
  assert.equal(rows[1]!.modeLabel, "一次性");
  assert.deepEqual(parseDshSubagents({ subagents: [] }), []);
  assert.deepEqual(parseDshSubagents(null), []);
});

test("dsh skills：名字必填，modelInvocable 缺省为 true", () => {
  const rows = parseDshSkills({
    skills: [
      { name: "pdf", description: "读 PDF", whenToUse: "要抽文本时" },
      { name: "deploy", description: "发版", modelInvocable: false },
      { name: "" },
    ],
  });
  assert.deepEqual(rows.map((row) => row.name), ["pdf", "deploy"]);
  assert.equal(rows[0]!.modelInvocable, true);
  assert.equal(rows[1]!.modelInvocable, false);
  assert.deepEqual(parseDshSkills({}), []);
});

test("dsh 子代理历史：text 与 parts 两种消息体都能取到文本", () => {
  const lines = parseDshSubagentHistory({
    messages: [
      { role: "user", text: "看看构建日志" },
      { role: "assistant", parts: [{ type: "text", text: "第一行" }, { type: "text", text: "第二行" }] },
      { role: "assistant", parts: [{ type: "tool_use" }] },
      { role: "user", text: "   " },
    ],
  });
  assert.deepEqual(lines, [
    { role: "user", text: "看看构建日志" },
    { role: "assistant", text: "第一行\n第二行" },
  ]);
  assert.deepEqual(parseDshSubagentHistory(null), []);
});

test("dsh 空态文案说明「本来就是空的」而不是报错", () => {
  assert.match(DSH_EMPTY_GOAL, /还没有设置目标/);
  assert.match(DSH_EMPTY_SUBAGENTS, /没有子代理/);
  assert.match(DSH_EMPTY_SKILLS, /没有可用的技能/);
});
