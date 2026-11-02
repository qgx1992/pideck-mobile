/**
 * 侧栏快照（推给 Flutter `Drawer` 的那份数据）。
 *
 * 这里测的是「摊平 + 截断 + 分组」这些容易在手机上察觉不到的细节：
 * 折叠后不该再给行、预览截断的条数要能算回来（否则「收起」没有入口）、
 * 搜索时忽略分段、多设备要带设备名。
 */

import test from "node:test";
import assert from "node:assert/strict";

const { buildSidebarSnapshot, timeLabel } = await import("../src/platform/flutter-sidebar.ts");

const projects = [
  { id: "p-chat", kind: "chat", name: "Chat" },
  { id: "p-a", name: "alpha", path: "C:/work/alpha" },
  { id: "p-b", name: "beta", path: "C:/work/beta" },
];

const many = Array.from({ length: 9 }, (_, index) => ({
  id: `a${index + 1}`,
  projectId: "p-a",
  title: `会话 ${index + 1}`,
  updatedAt: 1000 - index,
}));

const sessions = [
  { id: "s1", projectId: "p-chat", title: "问候", updatedAt: 100 },
  ...many,
  { id: "s2", projectId: "p-b", title: "写文档", updatedAt: 300 },
  { id: "s3", projectId: "p-b", title: "修 CI", updatedAt: 500, backend: "acp", acpToolId: "t1" },
  { id: "s4", projectId: "p-b", title: "dsh 的活", updatedAt: 400, backend: "dsh" },
];

const state = (runtimes: unknown[] = []) => ({
  projects,
  sessions,
  runtimes,
  acpTools: [{ id: "t1", name: "Gemini" }],
});

const base = (patch: Record<string, unknown> = {}) => ({
  tab: "active" as const,
  query: "",
  endpoints: [{ id: "ep1", label: "本机", state: state() }],
  activeEndpointId: "ep1",
  activeSessionId: "s3",
  activeProjectId: "p-b",
  collapsedProjects: new Set<string>(),
  expandedSessions: new Map<string, boolean>(),
  flatExpanded: new Set<string>(),
  now: 1_700_000_000_000,
  ...patch,
});

test("活动分段：最近会话按 updatedAt 倒序，行上带项目名", () => {
  const snapshot = buildSidebarSnapshot(base({ activeSessionId: null, activeProjectId: null }));
  const recent = snapshot.sections.find((section) => section.key === "active-recent");
  assert.ok(recent);
  assert.equal(recent?.title, "最近会话");
  // 10 条上限，a1..a9 + s3/s2/s4/s1 里取最新 10 条
  assert.equal(recent?.rows.length, 10);
  assert.equal(recent?.rows[0]?.id, "a1");
  assert.ok(recent?.rows[0]?.meta.includes("alpha"));
  // 活动段没有「展开其余」：上限就是展示上限
  assert.equal(recent?.hiddenCount, 0);
});

test("活动分段：运行中的会话排进「运行中」并从最近里剔除", () => {
  const snapshot = buildSidebarSnapshot(
    base({ endpoints: [{ id: "ep1", label: "本机", state: state([{ sessionId: "s3", status: "running" }]) }] }),
  );
  const live = snapshot.sections.find((section) => section.key === "active-live");
  const recent = snapshot.sections.find((section) => section.key === "active-recent");
  assert.equal(live?.rows.length, 1);
  assert.equal(live?.rows[0]?.running, true);
  assert.equal(recent?.rows.some((row) => row.id === "s3"), false);
});

test("活动分段：一条都没有时给空态文案", () => {
  const snapshot = buildSidebarSnapshot(
    base({
      endpoints: [{ id: "ep1", label: "本机", state: { projects, sessions: [], runtimes: [] } }],
      activeSessionId: null,
    }),
  );
  const recent = snapshot.sections.find((section) => section.key === "active-recent");
  assert.equal(recent?.rows.length, 0);
  assert.match(recent?.empty ?? "", /还没有会话/);
  // 空的「运行中」整段不出现
  assert.equal(snapshot.sections.some((section) => section.key === "active-live"), false);
});

test("项目分段：分组按项目顺序，组内按 updatedAt 倒序", () => {
  const snapshot = buildSidebarSnapshot(base({ tab: "projects" }));
  const groups = snapshot.sections.filter((section) => section.kind === "group");
  // 内置 Chat 归到「聊天」，项目分段只剩 alpha / beta
  assert.deepEqual(
    groups.map((group) => group.label),
    ["alpha", "beta"],
  );
  const beta = groups.find((group) => group.projectId === "p-b");
  assert.deepEqual(
    beta?.rows.map((row) => row.id),
    ["s3", "s4", "s2"],
  );
  assert.equal(beta?.count, 3);
});

test("项目分段里没有内置 Chat 项目（它在「聊天」段平铺，因此长按菜单永远是「可移除」的那种）", () => {
  const snapshot = buildSidebarSnapshot(base({ tab: "projects" }));
  const groups = snapshot.sections.filter((section) => section.kind === "group");
  assert.equal(groups.some((group) => group.projectId === "p-chat"), false);
  assert.equal(groups.every((group) => group.projectId !== "p-chat"), true);
});

test("项目分段：超过 6 条时截断，并报出隐藏条数（收起时有入口）", () => {
  const snapshot = buildSidebarSnapshot(base({ tab: "projects" }));
  const alpha = snapshot.sections.find((section) => section.projectId === "p-a");
  assert.equal(alpha?.rows.length, 6);
  assert.equal(alpha?.hiddenCount, 3);
  assert.equal(alpha?.expanded, false);

  const expanded = buildSidebarSnapshot(
    base({ tab: "projects", expandedSessions: new Map([["p-a", true]]) }),
  ).sections.find((section) => section.projectId === "p-a");
  assert.equal(expanded?.rows.length, 9);
  // 展开后仍然报 3，Dart 侧才知道能「收起」
  assert.equal(expanded?.hiddenCount, 3);
  assert.equal(expanded?.expanded, true);
});

test("项目分段：当前会话被截断时临时展开（不写回用户意图）", () => {
  const snapshot = buildSidebarSnapshot(base({ tab: "projects", activeSessionId: "a9" }));
  const alpha = snapshot.sections.find((section) => section.projectId === "p-a");
  assert.equal(alpha?.expanded, true);
  assert.equal(alpha?.rows.length, 9);

  // 用户显式收起 → 即使当前会话在里面也不展开
  const collapsed = buildSidebarSnapshot(
    base({ tab: "projects", activeSessionId: "a9", expandedSessions: new Map([["p-a", false]]) }),
  ).sections.find((section) => section.projectId === "p-a");
  assert.equal(collapsed?.expanded, false);
});

test("项目分段：折叠的项目不给行，但保留计数与运行中数量", () => {
  const snapshot = buildSidebarSnapshot(
    base({
      tab: "projects",
      collapsedProjects: new Set(["p-b"]),
      endpoints: [
        {
          id: "ep1",
          label: "本机",
          state: state([
            { sessionId: "s3", status: "running" },
            { sessionId: "s4", status: "running" },
          ]),
        },
      ],
    }),
  );
  const beta = snapshot.sections.find((section) => section.projectId === "p-b");
  assert.equal(beta?.collapsed, true);
  assert.equal(beta?.rows.length, 0);
  assert.equal(beta?.count, 3);
  assert.equal(beta?.runningCount, 2);
});

test("项目分段：没有任何工作区项目时给整栏空态", () => {
  const snapshot = buildSidebarSnapshot(
    base({
      tab: "projects",
      endpoints: [
        { id: "ep1", label: "本机", state: { projects: [projects[0]], sessions, runtimes: [] } },
      ],
    }),
  );
  assert.equal(snapshot.sections.length, 0);
  assert.match(snapshot.empty ?? "", /还没有项目/);
});

test("聊天分段：只显示内置 Chat 项目的会话", () => {
  const snapshot = buildSidebarSnapshot(base({ tab: "chats" }));
  const chats = snapshot.sections[0];
  assert.deepEqual(
    chats?.rows.map((row) => row.id),
    ["s1"],
  );
});

test("搜索：忽略分段，跨项目扁平匹配，空结果给文案", () => {
  const hit = buildSidebarSnapshot(base({ tab: "projects", query: "CI" }));
  assert.equal(hit.sections.length, 1);
  assert.equal(hit.sections[0]?.key, "search");
  assert.deepEqual(
    hit.sections[0]?.rows.map((row) => row.id),
    ["s3"],
  );

  const miss = buildSidebarSnapshot(base({ query: "不存在的词" }));
  assert.equal(miss.sections[0]?.rows.length, 0);
  assert.equal(miss.sections[0]?.empty, "没有匹配的会话");
});

test("徽标：acp 带 CLI 名字，dsh 带 dsh，pi 后端不显示徽标", () => {
  const snapshot = buildSidebarSnapshot(base({ tab: "projects" }));
  const rows = snapshot.sections.flatMap((section) => section.rows);
  const acp = rows.find((row) => row.id === "s3");
  assert.equal(acp?.acp, true);
  assert.equal(acp?.badgeKind, "acp");
  assert.equal(acp?.acpToolName, "Gemini");

  const dsh = rows.find((row) => row.id === "s4");
  assert.equal(dsh?.dsh, true);
  assert.equal(dsh?.badgeKind, "dsh");

  const plain = rows.find((row) => row.id === "s2");
  assert.equal(plain?.badge, undefined);
  assert.equal(plain?.acp, false);
});

test("分段按钮带计数（多设备时是各端相加）", () => {
  const snapshot = buildSidebarSnapshot(base());
  assert.deepEqual(
    snapshot.tabs.map((tab) => tab.id),
    ["active", "chats", "projects"],
  );
  // 项目计数只数工作区项目（不含内置 Chat）
  assert.equal(snapshot.tabs.find((tab) => tab.id === "projects")?.count, 2);
  assert.equal(snapshot.tabs.find((tab) => tab.id === "chats")?.count, 1);

  const multi = buildSidebarSnapshot(
    base({
      endpoints: [
        { id: "ep1", label: "本机", state: state() },
        { id: "ep2", label: "家里", state: state() },
      ],
    }),
  );
  assert.equal(multi.deviceCount, 2);
  assert.equal(multi.tabs.find((tab) => tab.id === "projects")?.count, 4);
});

test("多设备：项目分段按设备分块，行上带设备名", () => {
  const snapshot = buildSidebarSnapshot(
    base({
      tab: "projects",
      endpoints: [
        { id: "ep1", label: "本机", state: state([{ sessionId: "s3", status: "running" }]) },
        { id: "ep2", label: "家里", state: state() },
      ],
    }),
  );
  const devices = snapshot.sections.filter((section) => section.kind === "device");
  assert.deepEqual(
    devices.map((section) => section.label),
    ["本机", "家里"],
  );
  assert.equal(devices[0]?.active, true);
  assert.equal(devices[0]?.deviceRunning, 1);

  // 非活跃端不给 active 标记（否则两边各有一行高亮）
  const second = snapshot.sections.filter((section) => section.kind === "group" && section.label === "beta");
  assert.equal(second[1]?.rows.every((row) => row.active === false), true);
});

test("多设备：活动分段的行带设备名", () => {
  const snapshot = buildSidebarSnapshot(
    base({
      endpoints: [
        { id: "ep1", label: "本机", state: state() },
        { id: "ep2", label: "家里", state: state() },
      ],
    }),
  );
  const rows = snapshot.sections.flatMap((section) => section.rows);
  assert.ok(rows.length > 0);
  assert.ok(rows.every((row) => row.deviceLabel === "本机" || row.deviceLabel === "家里"));
});

test("timeLabel：今天给时分，跨天给月日，无时间给空串", () => {
  const now = new Date(2024, 4, 20, 15, 30).getTime();
  assert.equal(timeLabel(new Date(2024, 4, 20, 9, 5).getTime(), now), "09:05");
  assert.equal(timeLabel(new Date(2024, 4, 18, 9, 5).getTime(), now), "5/18");
  assert.equal(timeLabel(undefined, now), "");
});

test("没有端点时不炸（连接还没建立起来）", () => {
  const snapshot = buildSidebarSnapshot(base({ endpoints: [], activeEndpointId: "ep1" }));
  assert.deepEqual(snapshot.sections, []);
  assert.deepEqual(snapshot.tabs, []);
});
