/**
 * UI 层冒烟测试：用 jsdom 跑真实 DOM 代码，验证历史渲染、流式增量更新、ask 卡片应答。
 * 只覆盖 DOM 行为；协议/状态逻辑由 ../tests 覆盖。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM('<!doctype html><html><body><div id="app"></div></body></html>');
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.requestAnimationFrame = (cb: () => void) => setTimeout(cb, 0);
g.HTMLElement = dom.window.HTMLElement;
g.Node = dom.window.Node;

const { ChatView } = await import("../src/ui/chat-view.ts");

function makeView() {
  const root = dom.window.document.getElementById("app")!;
  const responses: Array<{ requestId: string; response: Record<string, unknown> }> = [];
  const view = new ChatView(root as unknown as HTMLElement, {
    onRespond: (pending, response) => responses.push({ requestId: pending.requestId, response }),
  });
  return { root, view, responses };
}

test("历史消息：从 parts 提取文本并按角色渲染", () => {
  const { root, view } = makeView();
  view.setHistory([
    { id: "m1", role: "user", parts: [{ type: "text", text: "你好" }] } as never,
    { id: "m2", role: "assistant", content: "在的" } as never,
    { id: "m3", role: "system", parts: [{ type: "text", text: "系统提示" }] } as never,
  ]);
  const text = root.textContent ?? "";
  assert.ok(text.includes("你好"));
  assert.ok(text.includes("在的"));
  assert.equal(text.includes("系统提示"), false, "system 消息不渲染");
});

test("没有可显示消息时给出空态", () => {
  const { root, view } = makeView();
  view.setHistory([{ id: "m1", role: "system", parts: [] } as never]);
  assert.ok((root.textContent ?? "").includes("还没有可显示的消息"));
});

test("流式：同一 block 增量更新，不重复插入节点", () => {
  const { root, view } = makeView();
  view.setLive("run-1", [{ kind: "text", id: "t1", text: "你", done: false }], "running");
  view.setLive("run-1", [{ kind: "text", id: "t1", text: "你好", done: false }], "running");
  view.setLive("run-1", [{ kind: "text", id: "t1", text: "你好呀", done: true }], "finished");

  const bodies = root.querySelectorAll(".live .msg-body");
  assert.equal(bodies.length, 1, "只有一个正文节点");
  assert.equal(bodies[0]?.textContent, "你好呀");
  assert.ok((root.textContent ?? "").includes("运行中") === false, "结束后不再显示运行中");
});

test("新一轮（messageId 变化）清空上一轮的块", () => {
  const { root, view } = makeView();
  view.setLive("run-1", [{ kind: "text", id: "t1", text: "旧", done: true }], "finished");
  view.setLive("run-2", [{ kind: "text", id: "t2", text: "新", done: false }], "running");
  const text = root.textContent ?? "";
  assert.equal(text.includes("旧"), false);
  assert.ok(text.includes("新"));
});

test("工具块显示状态与入参", () => {
  const { root, view } = makeView();
  view.setLive("run-1", [{ kind: "tool", toolCallId: "c1", toolName: "bash", state: "running" }], "running");
  view.setLive(
    "run-1",
    [{ kind: "tool", toolCallId: "c1", toolName: "bash", state: "ok", input: { cmd: "ls" }, output: "a.txt" }],
    "running",
  );
  const text = root.textContent ?? "";
  assert.ok(text.includes("bash"));
  assert.ok(text.includes("完成"));
  assert.ok(text.includes("a.txt"));
});

test("ask 卡片：select 选项点击后回传 { value }", () => {
  const { root, view, responses } = makeView();
  view.setPending([
    {
      sessionId: "s1",
      agentId: "a1",
      runtimeGeneration: 1,
      requestId: "req-1",
      method: "select",
      title: "选一个",
      options: ["允许", "拒绝"],
    } as never,
  ]);
  const buttons = Array.from(root.querySelectorAll<HTMLButtonElement>(".ask-options button"));
  assert.equal(buttons.length, 2);
  buttons[0]?.click();
  assert.deepEqual(responses, [{ requestId: "req-1", response: { value: "允许" } }]);
});

test("ask 卡片：confirm 回传 { confirmed }", () => {
  const { root, view, responses } = makeView();
  view.setPending([
    {
      sessionId: "s1",
      agentId: "a1",
      runtimeGeneration: 1,
      requestId: "req-2",
      method: "confirm",
      title: "执行命令？",
    } as never,
  ]);
  const buttons = Array.from(root.querySelectorAll<HTMLButtonElement>(".ask-options button"));
  buttons[1]?.click();
  assert.deepEqual(responses, [{ requestId: "req-2", response: { confirmed: false } }]);
});

test("ask 卡片：input 提交空值不发送", () => {
  const { root, view, responses } = makeView();
  view.setPending([
    {
      sessionId: "s1",
      agentId: "a1",
      runtimeGeneration: 1,
      requestId: "req-3",
      method: "input",
      title: "补充说明",
    } as never,
  ]);
  const submit = root.querySelector<HTMLButtonElement>(".ask-card > .btn");
  submit?.click();
  assert.equal(responses.length, 0);
});

test("历史消息：PiDeck 实际的扁平结构（顶层 text 字段）", () => {
  const { root, view } = makeView();
  view.setHistory([
    { id: "m1", role: "user", text: "你好", timestamp: 1700000000000, meta: { entryId: "e1" } } as never,
    {
      id: "m2",
      role: "assistant",
      text: "在的，需要什么？",
      thinking: "用户只是打招呼",
      timestamp: 1700000001000,
      meta: { entryId: "e2" },
    } as never,
  ]);
  const text = root.textContent ?? "";
  assert.ok(text.includes("你好"), "user 正文要渲染");
  assert.ok(text.includes("在的，需要什么？"), "assistant 正文要渲染");
  assert.ok(text.includes("思考"), "assistant 的 thinking 要折叠展示");
});

test("历史消息：tool 走工具块，显示工具名与状态", () => {
  const { root, view } = makeView();
  view.setHistory([
    {
      id: "m3",
      role: "tool",
      text: "done",
      meta: { toolName: "bash", status: "completed", detailText: "ls -la" },
    } as never,
  ]);
  const text = root.textContent ?? "";
  assert.ok(text.includes("bash"), "要显示工具名");
  assert.ok(text.includes("完成"), "要显示状态");
  assert.ok(text.includes("ls -la"), "要显示 detailText");
});

test("历史消息：tool 失败状态显示失败", () => {
  const { root, view } = makeView();
  view.setHistory([{ id: "m4", role: "tool", text: "boom", meta: { toolName: "read", isError: true } } as never]);
  assert.ok((root.textContent ?? "").includes("失败"));
});

test("历史消息：只有 system 时给空态提示", () => {
  const { root, view } = makeView();
  view.setHistory([{ id: "m5", role: "system", text: "提示词" } as never]);
  assert.ok((root.textContent ?? "").includes("还没有可显示的消息"));
});

test("错误卡：标题/详情/提示/重试按钮齐全，且点重试会回调", () => {
  const { root, view } = makeView();
  let retried = 0;
  view.setError({
    title: "这个 DSH 会话的历史读不出来",
    detail: "HTTP 500 · webError.internal",
    hint: "去桌面端修 DSH runtime",
    onRetry: () => {
      retried += 1;
    },
  });
  const text = root.textContent ?? "";
  assert.ok(text.includes("历史读不出来"));
  assert.ok(text.includes("HTTP 500"));
  assert.ok(text.includes("DSH runtime"));
  root.querySelector<HTMLButtonElement>(".error-card .btn")!.click();
  assert.equal(retried, 1);
});

test("hasContent：空态/错误卡/消息分别判定", () => {
  const { view } = makeView();
  assert.equal(view.hasContent(), false);
  view.setEmpty("空");
  assert.equal(view.hasContent(), false, "空态不算内容");
  view.setError({ title: "x" });
  assert.equal(view.hasContent(), true, "错误卡算内容");
  view.setHistory([{ id: "m", role: "user", text: "hi" } as never]);
  assert.equal(view.hasContent(), true);
});

test("发送时本地回显自己的提问", () => {
  const { root, view } = makeView();
  view.appendLocalUser("帮我看看这个错误");
  const local = root.querySelector(".msg--local .msg-body");
  assert.equal(local?.textContent, "帮我看看这个错误");
  assert.ok((root.textContent ?? "").includes("我"), "用户气泡要有角色标记");
});

test("服务端历史已包含该提问时，撤掉本地占位避免重复", () => {
  const { root, view } = makeView();
  view.appendLocalUser("重复测试");
  assert.equal(root.querySelectorAll(".msg--local").length, 1);

  view.setHistory([
    { id: "m1", role: "user", text: "重复测试", timestamp: 1 } as never,
    { id: "m2", role: "assistant", text: "好的", timestamp: 2 } as never,
  ]);
  assert.equal(root.querySelectorAll(".msg--local").length, 0, "不应残留占位");
  assert.equal(root.querySelectorAll(".msg").length, 2);
});

test("历史还没落盘时保留本地占位", () => {
  const { root, view } = makeView();
  view.appendLocalUser("刚发出的问题");
  view.setHistory([{ id: "m1", role: "assistant", text: "更早的回答", timestamp: 1 } as never]);
  assert.equal(root.querySelectorAll(".msg--local").length, 1, "服务端没有这条就继续显示");
});

test("clearLive 清空流式区与状态文案", () => {
  const { root, view } = makeView();
  view.setLive("run-1", [{ kind: "text", id: "t1", text: "流式中", done: false }], "running");
  assert.ok(root.querySelector(".live")?.textContent?.includes("流式中"));
  view.clearLive();
  assert.equal(root.querySelector(".live")?.textContent, "");
  assert.equal(root.querySelector(".run-status")?.textContent, "");
});

test("连续 setLive 不会累积「执行过程」块（回归：每帧新建 group 却不移除旧的）", () => {
  const { root, view } = makeView();
  const blocks: never[] = [];
  const push = (block: unknown) => {
    (blocks as unknown[]).push(block);
    view.setLive("m1", blocks as never, "running");
  };

  push({ kind: "reasoning", id: "r1", text: "想想", done: false });
  assert.equal(root.querySelectorAll(".live .process").length, 1);

  push({ kind: "tool", toolCallId: "c1", toolName: "bash", state: "running" });
  assert.equal(root.querySelectorAll(".live .process").length, 1, "同一分组内不该新增");

  push({ kind: "tool", toolCallId: "c2", toolName: "read", state: "ok" });
  assert.equal(root.querySelectorAll(".live .process").length, 1, "第三帧仍然只有一个");

  push({ kind: "text", id: "t1", text: "回答", done: false });
  assert.equal(root.querySelectorAll(".live .process").length, 1, "正文打断后旧分组保留");

  push({ kind: "tool", toolCallId: "c3", toolName: "grep", state: "ok" });
  assert.equal(root.querySelectorAll(".live .process").length, 2, "正文之后应新开一组");
});

test("重复渲染同一帧不会产生多余节点", () => {
  const { root, view } = makeView();
  const blocks = [
    { kind: "tool", toolCallId: "c1", toolName: "bash", state: "running" },
  ] as never;
  for (let i = 0; i < 10; i += 1) view.setLive("m1", blocks, "running");
  assert.equal(root.querySelectorAll(".live > *").length, 1, "10 次渲染仍只有 1 个顶层节点");
  assert.equal(root.querySelectorAll(".live .process").length, 1);
});

test("分组内的工具条目不会因重复渲染而复制", () => {
  const { root, view } = makeView();
  const blocks = [
    { kind: "tool", toolCallId: "c1", toolName: "bash", state: "ok" },
    { kind: "tool", toolCallId: "c2", toolName: "read", state: "ok" },
  ] as never;
  for (let i = 0; i < 5; i += 1) view.setLive("m1", blocks, "running");
  assert.equal(root.querySelectorAll(".live .process-body > *").length, 2);
});

test("ask 卡片：选项带描述时显示两行", () => {
  const { root, view } = makeView();
  view.setPending([
    {
      sessionId: "s1",
      agentId: "a1",
      runtimeGeneration: 1,
      requestId: "r1",
      method: "select",
      title: "选一个",
      options: ["允许 — 只对本次生效", "拒绝"],
    } as never,
  ]);
  const labels = [...root.querySelectorAll(".ask-option-label")].map((el) => el.textContent);
  const descs = [...root.querySelectorAll(".ask-option-desc")].map((el) => el.textContent);
  assert.deepEqual(labels, ["允许", "拒绝"]);
  assert.deepEqual(descs, ["只对本次生效"]);
});

test("ask 卡片：点选项回传的是原始完整值（含描述）", () => {
  const { root, view, responses } = makeView();
  view.setPending([
    {
      sessionId: "s1",
      agentId: "a1",
      runtimeGeneration: 1,
      requestId: "r2",
      method: "select",
      title: "t",
      options: ["允许 — 只对本次生效"],
    } as never,
  ]);
  root.querySelector<HTMLButtonElement>(".ask-option")!.click();
  assert.deepEqual(responses[0]?.response, { value: "允许 — 只对本次生效" });
});

test("ask 卡片：batch_ask 显示题号进度并可逐题作答", () => {
  const { root, view, responses } = makeView();
  view.setPending([
    {
      sessionId: "s1",
      agentId: "a1",
      runtimeGeneration: 1,
      requestId: "r3",
      method: "batch_ask",
      title: "多题",
      batchQuestions: [
        { question: "第一题", options: ["A", "B"] },
        { question: "第二题" },
      ],
    } as never,
  ]);
  assert.ok((root.textContent ?? "").includes("提问（2 题）"));
  assert.ok((root.textContent ?? "").includes("已答 0/2"));
  assert.ok((root.textContent ?? "").includes("第一题"));

  // 选第一题的 A → 自动进入下一题
  root.querySelector<HTMLButtonElement>(".ask-option")!.click();
  assert.ok((root.textContent ?? "").includes("第二题"));
  assert.ok((root.textContent ?? "").includes("已答 1/2"));

  // 最后一题点「去审阅」
  const reviewBtn = [...root.querySelectorAll<HTMLButtonElement>(".ask-card .btn")].find((b) => b.textContent === "去审阅");
  assert.ok(reviewBtn, "应有去审阅按钮");
  reviewBtn!.click();
  assert.ok((root.textContent ?? "").includes("确认全部回答"));
  assert.ok((root.textContent ?? "").includes("还有题目未回答"), "未答完时按钮应提示");
  assert.equal(responses.length, 0, "未提交前不应回传");
});

test("ask 卡片：取消回传 cancelled", () => {
  const { root, view, responses } = makeView();
  view.setPending([
    { sessionId: "s1", agentId: "a1", runtimeGeneration: 1, requestId: "r4", method: "confirm", title: "t" } as never,
  ]);
  const cancel = [...root.querySelectorAll<HTMLButtonElement>(".ask-card .btn-text")].find((b) => b.textContent === "取消");
  cancel!.click();
  assert.deepEqual(responses[0]?.response, { cancelled: true });
});

test("问答卡在切换会话后能恢复（回归：之前切走再切回就丢了）", () => {
  const { root, view } = makeView();
  const pending = [
    {
      sessionId: "s1",
      agentId: "a1",
      runtimeGeneration: 1,
      requestId: "r-keep",
      method: "select",
      title: "部署到哪个环境？",
      options: ["测试", "生产"],
    } as never,
  ];

  view.setPending(pending);
  assert.equal(root.querySelectorAll(".ask-card").length, 1);

  // 切到别的会话：clear 会清掉卡片
  view.clear();
  view.setEmpty("加载中…");
  assert.equal(root.querySelectorAll(".ask-card").length, 0);

  // 切回来：openSession 会重新 setPending
  view.clear();
  view.setPending(pending);
  assert.equal(root.querySelectorAll(".ask-card").length, 1);
  assert.equal(root.querySelectorAll(".ask-option").length, 2, "选项要完整恢复");
});

test("同一会话同时有多张卡时全部渲染", () => {
  const { root, view } = makeView();
  view.setPending([
    { sessionId: "s1", agentId: "a1", runtimeGeneration: 1, requestId: "r1", method: "confirm", title: "A" } as never,
    { sessionId: "s1", agentId: "a1", runtimeGeneration: 1, requestId: "r2", method: "input", title: "B" } as never,
  ]);
  assert.equal(root.querySelectorAll(".ask-card").length, 2);
});

test("setPending 空数组清空所有卡片", () => {
  const { root, view } = makeView();
  view.setPending([{ sessionId: "s1", agentId: "a1", runtimeGeneration: 1, requestId: "r1", method: "confirm", title: "A" } as never]);
  assert.equal(root.querySelectorAll(".ask-card").length, 1);
  view.setPending([]);
  assert.equal(root.querySelectorAll(".ask-card").length, 0);
});

test("键盘弹起后保持贴底；用户翻上去则不抢位置", () => {
  const { root, view } = makeView();
  const scroll = root.querySelector<HTMLElement>(".chat-scroll")!;
  let H = 2000;
  let C = 600;
  let top = 0;
  Object.defineProperty(scroll, "scrollHeight", { get: () => H });
  Object.defineProperty(scroll, "clientHeight", { get: () => C });
  Object.defineProperty(scroll, "scrollTop", {
    get: () => top,
    set: (v: number) => {
      top = Math.min(Math.max(0, v), H - C);
    },
  });

  view.scrollToBottom(); // 内部走 rAF，这里直接验证公式
  scroll.scrollTop = H - C;
  assert.equal(top, H - C, "贴底位置 = scrollHeight - clientHeight");

  // 键盘弹起 → 可视高度变小，仍应贴底
  C = 300;
  view.keepBottomOnResize();
  assert.equal(top, H - C, "键盘弹起后仍贴在底部");

  // 用户往上翻之后，不应被强制拉回
  top = 200;
  scroll.dispatchEvent(new dom.window.Event("scroll"));
  view.keepBottomOnResize();
  assert.equal(top, 200, "用户主动上翻时不要抢位置");
});
