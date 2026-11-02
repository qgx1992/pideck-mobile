/**
 * 聊天页「视图迁移」的桥：历史消息 → 渲染条目、快照组装、合并推送。
 *
 * 这三块都是纯逻辑，不碰 DOM，但 `historyItemsOf` 依赖 `chat-view.ts` 的
 * `normalizeRole`/`messageText`（那边 import 了 dom 工具），所以照样要先搭 jsdom。
 *
 * 重点盯的是「Flutter 侧字段名」：Dart 的 `ChatSnapshot.fromJson` 与这里的
 * 接口一一对应，改名不改那边就会静默拿到 null。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>");
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.HTMLElement = dom.window.HTMLElement;
g.Node = dom.window.Node;
g.Event = dom.window.Event;

const { historyItemsOf, buildChatSnapshot, FlutterChatMirror } = await import(
  "../src/platform/flutter-chat.ts"
);

test("historyItemsOf：用户消息取正文与时间", () => {
  const items = historyItemsOf([
    { id: "m1", role: "user", parts: [{ type: "text", text: "你好" }], timestamp: Date.UTC(2026, 0, 2, 3, 4) },
  ]);
  assert.equal(items.length, 1);
  assert.equal(items[0].role, "user");
  assert.equal(items[0].text, "你好");
  assert.equal(items[0].id, "m1");
  // 必须是 24 小时制补零，且**不能**跟着系统语言变：en-US 下 toLocaleTimeString()
  // 给的是 "3:04:00 AM"，切前五位就成了 "3:04:"（CI 上就是这么红的）
  assert.match(items[0].time, /^[0-2]\d:[0-5]\d$/);
});

test("historyItemsOf：<thinking> 标签从正文里剥出来", () => {
  const items = historyItemsOf([
    { id: "m2", role: "assistant", parts: [{ type: "text", text: "<thinking>先想想</thinking>答案" }] },
  ]);
  assert.equal(items[0].text, "答案");
  assert.equal(items[0].thinking, "先想想");
});

test("historyItemsOf：独立 thinking 字段与标签内容都保留", () => {
  const items = historyItemsOf([
    {
      id: "m3",
      role: "assistant",
      thinking: "字段里的",
      parts: [{ type: "text", text: "<thinking>标签里的</thinking>正文" }],
    },
  ]);
  assert.equal(items[0].thinking, "字段里的\n\n标签里的");
  assert.equal(items[0].text, "正文");
});

test("historyItemsOf：tool 条目带工具名、状态与详情", () => {
  const items = historyItemsOf([
    {
      id: "t1",
      role: "tool",
      parts: [{ type: "text", text: "命令输出" }],
      meta: { toolName: "bash", status: "ok", detailText: "ls -la" },
    },
  ]);
  assert.equal(items[0].role, "tool");
  assert.equal(items[0].toolName, "bash");
  assert.equal(items[0].toolState, "ok");
  assert.equal(items[0].toolDetail, "ls -la\n\n命令输出");
  assert.equal(items[0].text, "");
});

test("historyItemsOf：isError 优先于 status 判成 error", () => {
  const items = historyItemsOf([
    { id: "t2", role: "tool", parts: [], meta: { toolName: "read", status: "ok", isError: true } },
  ]);
  assert.equal(items[0].toolState, "error");
});

test("historyItemsOf：running 状态原样保留", () => {
  const items = historyItemsOf([
    { id: "t3", role: "tool", parts: [], meta: { toolName: "grep", status: "running" } },
  ]);
  assert.equal(items[0].toolState, "running");
});

test("historyItemsOf：没有 id 时退回 entryId", () => {
  const items = historyItemsOf([
    { entryId: "e1", role: "assistant", parts: [{ type: "text", text: "x" }] } as never,
  ]);
  assert.equal(items[0].id, "e1");
});

test("historyItemsOf：跳过 system 与完全空的消息", () => {
  const items = historyItemsOf([
    { id: "s", role: "system", parts: [{ type: "text", text: "系统提示" }] },
    { id: "e", role: "assistant", parts: [] },
    { id: "ok", role: "assistant", parts: [{ type: "text", text: "有内容" }] },
  ]);
  assert.deepEqual(items.map((item) => item.id), ["ok"]);
});

test("historyItemsOf：工具详情超过 2000 字被截断", () => {
  const items = historyItemsOf([
    { id: "t4", role: "tool", parts: [], meta: { toolName: "bash", status: "ok", detailText: "x".repeat(5000) } },
  ]);
  assert.equal(items[0].toolDetail.length, 2000);
});

test("buildChatSnapshot：只取第一条提问卡，其余字段透传", () => {
  const snapshot = buildChatSnapshot({
    sessionId: "s1",
    title: "标题",
    messages: [{ id: "m1", role: "user", parts: [{ type: "text", text: "hi" }] }],
    status: "running",
    statusText: "正在生成",
    loading: true,
    live: { messageId: "live1", blocks: [{ kind: "text", id: "b1", text: "流", done: false }] },
    pending: [
      { requestId: "q1", method: "select", title: "选", options: ["a"] },
      { requestId: "q2", method: "input", title: "第二个" },
    ] as never,
    perf: { ttftMs: 300 } as never,
  });

  assert.equal(snapshot.sessionId, "s1");
  assert.equal(snapshot.status, "running");
  assert.equal(snapshot.statusText, "正在生成");
  assert.equal(snapshot.loading, true);
  assert.equal(snapshot.history.length, 1);
  assert.equal(snapshot.live?.blocks.length, 1);
  assert.equal(snapshot.pending?.requestId, "q1");
  assert.equal((snapshot.perf as { ttftMs?: number } | null)?.ttftMs, 300);
});

test("buildChatSnapshot：空输入给安全默认值", () => {
  const snapshot = buildChatSnapshot({ sessionId: null, messages: [] });
  assert.equal(snapshot.sessionId, null);
  assert.equal(snapshot.status, "idle");
  assert.equal(snapshot.statusText, "");
  assert.equal(snapshot.loading, false);
  assert.equal(snapshot.error, null);
  assert.equal(snapshot.empty, null);
  assert.equal(snapshot.live, null);
  assert.equal(snapshot.pending, null);
  assert.deepEqual(snapshot.history, []);
  assert.deepEqual(snapshot.suggestions, []);
});

test("buildChatSnapshot：空态提示词原样带过去（拷贝，不是引用）", () => {
  const suggestions = ["介绍一下这个项目", "跑一下测试"];
  const snapshot = buildChatSnapshot({ sessionId: "s1", messages: [], suggestions });
  assert.deepEqual(snapshot.suggestions, suggestions);
  assert.notEqual(snapshot.suggestions, suggestions, "要拷贝：调用方之后改数组不该影响已推的快照");
});

test("FlutterChatMirror：40ms 内的多次更新只推最后一次", async () => {
  const pushed: string[] = [];
  const mirror = new FlutterChatMirror({ push: (json) => void pushed.push(json) });
  const snapshot = buildChatSnapshot({ sessionId: "s1", messages: [], status: "running" });
  mirror.update({ ...snapshot, statusText: "第一" });
  mirror.update({ ...snapshot, statusText: "第二" });
  mirror.update({ ...snapshot, statusText: "第三" });
  assert.equal(pushed.length, 0, "还没到 40ms 就不该推");
  await new Promise((resolve) => setTimeout(resolve, 60));
  assert.equal(pushed.length, 1);
  assert.equal(JSON.parse(pushed[0]).statusText, "第三");
  mirror.dispose();
});

test("FlutterChatMirror：内容没变不重复过桥", () => {
  const pushed: string[] = [];
  const mirror = new FlutterChatMirror({ push: (json) => void pushed.push(json) });
  const snapshot = buildChatSnapshot({ sessionId: "s1", messages: [] });
  mirror.update(snapshot);
  mirror.flush();
  mirror.update({ ...snapshot });
  mirror.flush();
  assert.equal(pushed.length, 1);
  mirror.dispose();
});

test("FlutterChatMirror：dispose 后定时器不再触发", async () => {
  const pushed: string[] = [];
  const mirror = new FlutterChatMirror({ push: (json) => void pushed.push(json) });
  mirror.update(buildChatSnapshot({ sessionId: "s1", messages: [] }));
  mirror.dispose();
  await new Promise((resolve) => setTimeout(resolve, 60));
  assert.equal(pushed.length, 0);
});

test("FlutterChatMirror：flush 之前没有快照时什么都不做", () => {
  const pushed: string[] = [];
  const mirror = new FlutterChatMirror({ push: (json) => void pushed.push(json) });
  mirror.flush();
  assert.equal(pushed.length, 0);
  mirror.dispose();
});

test("messageMenus：pi 会话里 user 有 5 项、assistant 只有 2 项，删除标 danger", () => {
  const snapshot = buildChatSnapshot({ sessionId: "s1", messages: [], backend: "pi" });
  assert.deepEqual(
    snapshot.messageMenus.user.map((item) => item.id),
    ["copy", "edit", "resend", "fork", "delete"],
  );
  assert.deepEqual(
    snapshot.messageMenus.assistant.map((item) => item.id),
    ["copy", "fork"],
  );
  assert.equal(snapshot.messageMenus.user.find((item) => item.id === "delete")?.danger, true);
  assert.equal(snapshot.messageMenus.assistant.every((item) => item.danger !== true), true);
});

test("messageMenus：ACP 会话只剩「复制」（运行时没有 fork/改/删命令）", () => {
  const snapshot = buildChatSnapshot({ sessionId: "s1", messages: [], backend: "acp" });
  assert.deepEqual(snapshot.messageMenus.user, [{ id: "copy", label: "复制文本" }]);
  assert.deepEqual(snapshot.messageMenus.assistant, [{ id: "copy", label: "复制文本" }]);
});

test("messageMenus：没传 backend 时按 pi 处理", () => {
  const snapshot = buildChatSnapshot({ sessionId: "s1", messages: [] });
  assert.equal(snapshot.messageMenus.user.length, 5);
});

test("弹层：sheet 原样带出，没传时为 null", () => {
  const view = {
    kind: "dsh",
    title: "DSH 工具",
    items: [{ id: "goal", label: "目标一句" }],
  };
  assert.deepEqual(buildChatSnapshot({ sessionId: "s1", messages: [], sheet: view }).sheet, view);
  assert.equal(buildChatSnapshot({ sessionId: "s1", messages: [] }).sheet, null);
});
