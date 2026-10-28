/** 侧栏分组逻辑：项目顺序、组内排序、搜索过滤、折叠规则。 */

import test from "node:test";
import assert from "node:assert/strict";
import { buildSidebarGroups, projectLabel, resolveExpandedProjects } from "../src/ui/sidebar-model.ts";

const projects = [
  { id: "p-chat", kind: "chat", name: "Chat" },
  { id: "p-a", name: "alpha", path: "C:/work/alpha" },
  { id: "p-b", path: "D:/repos/beta" },
];

const sessions = [
  { id: "s1", projectId: "p-chat", title: "问候", updatedAt: 100 },
  { id: "s2", projectId: "p-a", title: "修 CI", updatedAt: 300 },
  { id: "s3", projectId: "p-a", title: "重构侧栏", updatedAt: 200 },
  { id: "s4", projectId: "p-b", title: "发布 1.0", updatedAt: 150 },
];

const none = new Set<string>();

test("每个项目一个分组，顺序跟 projects 一致", () => {
  const groups = buildSidebarGroups({ projects, sessions }, "", none);
  assert.deepEqual(
    groups.map((g) => g.project.id),
    ["p-chat", "p-a", "p-b"],
  );
});

test("组内会话按 updatedAt 倒序", () => {
  const alpha = buildSidebarGroups({ projects, sessions }, "", none).find((g) => g.project.id === "p-a");
  assert.deepEqual(
    alpha?.sessions.map((s) => s.id),
    ["s2", "s3"],
  );
});

test("空项目也保留分组，total 为 0", () => {
  const groups = buildSidebarGroups({ projects, sessions: [] }, "", none);
  assert.equal(groups.length, 3);
  assert.equal(groups[0]?.total, 0);
  assert.equal(groups[0]?.sessions.length, 0);
});

test("搜索跨项目过滤，并隐藏无命中的分组", () => {
  const groups = buildSidebarGroups({ projects, sessions }, "修", none);
  assert.deepEqual(
    groups.map((g) => g.project.id),
    ["p-a"],
  );
  assert.equal(groups[0]?.sessions.length, 1);
});

test("搜索时强制展开（即使该分组是折叠状态）", () => {
  const collapsed = new Set(["p-a"]);
  assert.equal(buildSidebarGroups({ projects, sessions }, "", collapsed).find((g) => g.project.id === "p-a")?.collapsed, true);
  assert.equal(buildSidebarGroups({ projects, sessions }, "重构", collapsed).find((g) => g.project.id === "p-a")?.collapsed, false);
});

test("搜索大小写不敏感", () => {
  const groups = buildSidebarGroups({ projects, sessions }, "CI", none);
  assert.equal(groups.length, 1);
  assert.equal(groups[0]?.sessions[0]?.id, "s2");
});

test("搜索无命中时返回空数组", () => {
  assert.deepEqual(buildSidebarGroups({ projects, sessions }, "不存在的标题", none), []);
});

test("空白搜索等同不搜索", () => {
  assert.equal(buildSidebarGroups({ projects, sessions }, "   ", none).length, 3);
});

test("projectLabel：chat 恒为 Chat，优先 name，其次路径末段", () => {
  assert.equal(projectLabel({ id: "x", kind: "chat" }), "Chat");
  assert.equal(projectLabel({ id: "x", name: "  alpha  ", path: "C:/work/zzz" }), "alpha");
  assert.equal(projectLabel({ id: "x", path: "D:/repos/beta/" }), "beta");
  assert.equal(projectLabel({ id: "x", path: "C:\\work\\gamma" }), "gamma");
  assert.equal(projectLabel({ id: "only-id" }), "only-id");
  assert.equal(projectLabel(undefined), undefined);
});

const manySessions2 = Array.from({ length: 10 }, (_, i) => ({
  id: `m${i}`,
  projectId: "p-a",
  title: `会话 ${i}`,
  updatedAt: 1000 - i,
}));

test("未选会话时只返回显式展开的项目", () => {
  const expanded = new Map([["p-a", true]]);
  assert.deepEqual([...resolveExpandedProjects({ projects, sessions: manySessions2 }, expanded, null)], ["p-a"]);

  const empty = resolveExpandedProjects({ projects, sessions: manySessions2 }, new Map(), null);
  assert.equal(empty.size, 0);
});

test("选中的会话在前 6 个内 → 不额外展开（回归：之前会永久全展开）", () => {
  const result = resolveExpandedProjects(
    { projects, sessions: manySessions2 },
    new Map(),
    "m0", // 第一个，未截断
  );
  assert.equal(result.size, 0, "不该因为进过会话就展开");
});

test("选中的会话被截断（第 7 个之后）→ 临时展开该项目", () => {
  const result = resolveExpandedProjects(
    { projects, sessions: manySessions2 },
    new Map(),
    "m8", // 第 9 个
  );
  assert.deepEqual([...result], ["p-a"]);
});

test("临时展开不会污染手动集合（换会话后自动收起）", () => {
  const manual = new Map<string, boolean>();
  const withDeep = resolveExpandedProjects({ projects, sessions: manySessions2 }, manual, "m8");
  assert.equal(withDeep.has("p-a"), true);
  assert.equal(manual.size, 0, "手动集合必须保持不变");

  const withShallow = resolveExpandedProjects({ projects, sessions: manySessions2 }, manual, "m0");
  assert.equal(withShallow.has("p-a"), false, "切回浅位置的会话应恢复折叠");
});

test("自定义 preview 数量生效", () => {
  // preview=3 时，index 0/1/2 可见；m3 是第 4 个，属于被截断
  const visible = resolveExpandedProjects({ projects, sessions: manySessions2 }, new Map(), "m2", 3);
  assert.equal(visible.size, 0, "m2 在可见范围内，不展开");

  const truncated = resolveExpandedProjects({ projects, sessions: manySessions2 }, new Map(), "m3", 3);
  assert.deepEqual([...truncated], ["p-a"]);
});

test("显式收起优先：即使当前会话被截断也保持收起（用户点的收起不能失效）", () => {
  const override = new Map([["p-a", false]]);
  const result = resolveExpandedProjects({ projects, sessions: manySessions2 }, override, "m8");
  assert.equal(result.has("p-a"), false, "用户明确收起时应尊重选择");
});

test("显式展开优先：当前会话在前 6 个也保持展开", () => {
  const override = new Map([["p-a", true]]);
  const result = resolveExpandedProjects({ projects, sessions: manySessions2 }, override, "m0");
  assert.equal(result.has("p-a"), true);
});

test("收起后再切到深位置会话，仍然保持收起", () => {
  const override = new Map([["p-a", false]]);
  for (const sid of ["m6", "m8", "m9"]) {
    const result = resolveExpandedProjects({ projects, sessions: manySessions2 }, override, sid);
    assert.equal(result.has("p-a"), false, `切到 ${sid} 时不该自动展开`);
  }
});

test("收起后用户点展开，override 翻成 true 就恢复展开", () => {
  const override = new Map([["p-a", false]]);
  assert.equal(resolveExpandedProjects({ projects, sessions: manySessions2 }, override, "m8").has("p-a"), false);

  override.set("p-a", true);
  assert.equal(resolveExpandedProjects({ projects, sessions: manySessions2 }, override, "m8").has("p-a"), true);
});
