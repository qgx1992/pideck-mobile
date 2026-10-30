/**
 * 多端点（一机连多台）的身份规则与跨设备聚合。
 *
 * 这两块都是纯函数，但直接决定「会不会串台」和「排序对不对」，
 * 所以单独覆盖：id 归一化、去重、跨设备合并、时钟偏差截断。
 */

import test from "node:test";
import assert from "node:assert/strict";

const {
  MAX_LIVE_ENDPOINTS,
  dedupeEndpoints,
  describeEndpointCap,
  endpointIdOf,
  endpointLabelOf,
  sortEndpointsForPicker,
} = await import("../src/ui/endpoint-model.ts");
const { buildActivityViewMulti, chatSessionsMulti, endpointCatalogs, searchSessionsMulti } = await import(
  "../src/ui/sidebar-tabs-model.ts"
);

test("端点 id 归一化到 origin", () => {
  assert.equal(endpointIdOf("http://192.168.1.20:8765/?token=x"), "http://192.168.1.20:8765");
  assert.equal(endpointIdOf("HTTP://192.168.1.20:8765/"), "http://192.168.1.20:8765");
  assert.equal(endpointIdOf("https://my-pc.tailnet.ts.net"), "https://my-pc.tailnet.ts.net");
  assert.equal(endpointIdOf("   "), "");
  // 解析不了的退回去掉尾斜杠的小写串
  assert.equal(endpointIdOf("192.168.1.20:8765/"), "192.168.1.20:8765");
});

test("端点显示名：备注 > 主机名 > id", () => {
  assert.equal(endpointLabelOf({ baseUrl: "http://10.0.0.2:8765", label: "家里" }), "家里");
  assert.equal(endpointLabelOf({ baseUrl: "http://10.0.0.2:8765" }), "10.0.0.2:8765");
  // URL 会把 http 的 80 端口规范化掉，只剩主机名
  assert.equal(endpointLabelOf({ baseUrl: "http://10.0.0.2:80" }), "10.0.0.2");
  assert.equal(endpointLabelOf({ baseUrl: "" }), "未命名设备");
});

test("同一台机器只留一条，后来的备注名会补上", () => {
  const list = dedupeEndpoints([
    { baseUrl: "http://10.0.0.2:8765", token: "a" },
    { baseUrl: "http://10.0.0.2:8765/", token: "b", label: "家里" },
    { baseUrl: "http://10.0.0.3:8765", token: "c" },
  ]);
  assert.equal(list.length, 2);
  assert.equal((list[0] as { label?: string }).label, "家里");
});

test("上限提示：超过才提示", () => {
  assert.equal(describeEndpointCap(MAX_LIVE_ENDPOINTS), "");
  assert.match(describeEndpointCap(MAX_LIVE_ENDPOINTS + 1), /耗电/);
});

test("设备选择器：运行中的排前面", () => {
  const sorted = sortEndpointsForPicker([
    { label: "b", running: 0 },
    { label: "a", running: 2 },
    { label: "c", running: 1 },
  ]);
  assert.deepEqual(sorted.map((item) => item.label), ["a", "c", "b"]);
});

/* ------------------------------ 跨设备聚合 ------------------------------ */

const endpoint = (id: string, label: string, sessionIds: string[], running: string[] = [], pending = 0) => ({
  id,
  label,
  state: {
    projects: [{ id: `p-${id}`, name: `项目 ${id}`, path: `/w/${id}` }],
    sessions: sessionIds.map((sid, index) => ({
      id: sid,
      projectId: `p-${id}`,
      title: `${label} 的会话 ${sid}`,
      updatedAt: 100 + index,
    })),
    runtimes: running.map((sid) => ({ sessionId: sid, status: "running" })),
    pendingUiRequests: Array.from({ length: pending }, (_, index) => ({ sessionId: `s${index}`, requestId: `r${index}` })),
  },
});

test("活动分段：两台设备的运行中会话都进上半区，带设备名", () => {
  const a = endpoint("http://a", "家里", ["a1", "a2"], ["a1"]);
  const b = endpoint("http://b", "公司", ["b1"], ["b1"]);
  const view = buildActivityViewMulti([a, b] as never, 10, 1_000);
  assert.deepEqual(view.live.map((row) => `${row.endpointLabel}/${row.session.id}`), ["家里/a1", "公司/b1"]);
  assert.ok(view.recent.every((row) => row.endpointId));
});

test("活动分段：跨设备按更新时间倒序，且未来时间会被截断", () => {
  const a = { ...endpoint("http://a", "A", ["a1"]), state: { ...endpoint("http://a", "A", ["a1"]).state } };
  const b = { ...endpoint("http://b", "B", ["b1"]), state: { ...endpoint("http://b", "B", ["b1"]).state } };
  // A 的时钟比本地快 1 小时
  (a.state.sessions as Array<{ updatedAt: number }>)[0]!.updatedAt = 3_600_000;
  (b.state.sessions as Array<{ updatedAt: number }>)[0]!.updatedAt = 500;
  const view = buildActivityViewMulti([a, b] as never, 10, 1_000);
  // 截断到 now+60s 后仍排第一，但不会因为「永远未来」而无限霸榜（这里只断言不会崩且有序）
  assert.equal(view.recent.length, 2);
  assert.equal(view.recent[0]!.endpointId, "http://a");
});

test("聊天分段：合并各端内置 Chat 的会话", () => {
  const a = endpoint("http://a", "家里", ["a1"]);
  const b = endpoint("http://b", "公司", ["b1"]);
  // 把项目改成 chat 类型才会进聊天分段
  (a.state.projects as Array<{ kind?: string }>)[0]!.kind = "chat";
  (b.state.projects as Array<{ kind?: string }>)[0]!.kind = "chat";
  // 两台的时间戳相同（都是 100），只要求都在、且各自带设备名
  const rows = chatSessionsMulti([a, b] as never, 1_000);
  assert.deepEqual(new Set(rows.map((row) => row.session.id)), new Set(["a1", "b1"]));
  assert.deepEqual(new Set(rows.map((row) => row.endpointLabel)), new Set(["家里", "公司"]));
});

test("搜索：跨设备命中并带设备名", () => {
  const a = endpoint("http://a", "家里", ["a1"]);
  const b = endpoint("http://b", "公司", ["b1"]);
  const rows = searchSessionsMulti([a, b] as never, "b1", 1_000);
  assert.equal(rows.length, 1);
  assert.equal(rows[0]!.endpointId, "http://b");
  assert.equal(rows[0]!.endpointLabel, "公司");
});

test("项目分段：按设备分组，内置 Chat 不算工作区", () => {
  const a = endpoint("http://a", "家里", ["a1"]);
  const b = endpoint("http://b", "公司", ["b1"]);
  (b.state.projects as Array<{ kind?: string }>)[0]!.kind = "chat";
  const catalogs = endpointCatalogs([a, b] as never);
  assert.equal(catalogs.length, 2);
  assert.equal(catalogs[0]!.projects.length, 1);
  assert.equal(catalogs[1]!.projects.length, 0, "chat 项目不进项目分段");
});
