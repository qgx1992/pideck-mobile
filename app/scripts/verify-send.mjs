#!/usr/bin/env node
/**
 * 发送链路验证（完全离线）：
 * 用本地 mock 的 PiDeck 接口跑构建产物，验证三件事：
 *   1. 点发送后，自己的提问立刻出现在历史区（本地回显）
 *   2. 运行中「执行过程」折叠块默认收起
 *   3. 一轮结束后流式区被清空、历史被重拉
 *
 * 绝不连真实 PiDeck：mock 的端口随机、数据全在内存。
 */

import http from "node:http";
import { readdirSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { JSDOM } from "jsdom";

const APP_DIR = path.resolve(import.meta.dirname, "..");
const DIST = path.join(APP_DIR, "dist", "assets");

/* ------------------------------ mock 服务 ------------------------------ */

const SESSION = { id: "s-1", projectId: "p-1", title: "测试会话", status: "active", updatedAt: 1 };
const PROJECTS = [{ id: "p-1", name: "demo", path: "C:/demo" }];

let history = [{ id: "h1", role: "user", text: "之前的问题", timestamp: 1 }];
let running = false;

let pendingAsk = false;

function baseState() {
  return {
    projects: PROJECTS,
    sessions: [SESSION],
    runtimes: running
      ? [{ sessionId: SESSION.id, agentId: "a-1", runtimeGeneration: 1, status: "running" }]
      : [],
    pendingUiRequests: pendingAsk
      ? [{
          sessionId: SESSION.id,
          agentId: "a-1",
          runtimeGeneration: 1,
          requestId: "ask-1",
          method: "select",
          title: "部署到哪个环境？",
          options: ["测试环境", "生产环境"],
        }]
      : [],
  };
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://localhost");
  const send = (status, body, type = "application/json") => {
    const payload = typeof body === "string" ? body : JSON.stringify(body);
    res.writeHead(status, {
      "content-type": `${type}; charset=utf-8`,
      "access-control-allow-origin": "*",
      "cache-control": "no-store",
      connection: "close",
      "content-length": Buffer.byteLength(payload),
    });
    res.end(payload);
  };

  if (url.pathname === "/api/health") return send(200, { ok: true, service: "PiDeck", host: "0.0.0.0", port: 0 });
  if (url.pathname === "/api/state") return send(200, baseState());
  if (url.pathname === "/api/models") return send(200, { models: [] });

  if (url.pathname === "/api/events") {
    res.writeHead(200, {
      "content-type": "text/event-stream",
      "access-control-allow-origin": "*",
      "cache-control": "no-cache",
      connection: "keep-alive",
    });
    res.write(`event: state\ndata: ${JSON.stringify(baseState())}\n\n`);
    const timer = setInterval(() => {
      if (res.writableEnded) return clearInterval(timer);
      res.write(`event: state\ndata: ${JSON.stringify(baseState())}\n\n`);
    }, 1_000);
    req.on("close", () => clearInterval(timer));
    return;
  }

  const messages = url.pathname.match(/^\/api\/sessions\/([^/]+)\/messages$/);
  if (messages) {
    return send(200, { messages: history, total: history.length, windowStart: 0, truncated: false });
  }

  const prompt = url.pathname.match(/^\/api\/sessions\/([^/]+)\/prompt$/);
  if (prompt && req.method === "POST") {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => {
      const parsed = JSON.parse(body || "{}");
      running = true;
      // 服务端把这轮落盘（模拟真实行为）
      history = [
        ...history,
        { id: "h2", role: "user", text: parsed.message, timestamp: 2 },
        { id: "h3", role: "assistant", text: "好的，我来处理。", timestamp: 3 },
      ];
      send(200, { result: { accepted: true } });
    });
    return;
  }

  const stream = url.pathname.match(/^\/api\/sessions\/([^/]+)\/stream$/);
  if (stream) {
    res.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "access-control-allow-origin": "*",
      "cache-control": "no-cache",
      "x-vercel-ai-ui-message-stream": "v1",
      connection: "keep-alive",
    });
    const frame = (data) => res.write(`data: ${typeof data === "string" ? data : JSON.stringify(data)}\n\n`);
    // 先跑一段工具，再给正文，最后 finish
    setTimeout(() => frame({ type: "start", messageId: "m-1" }), 60);
    setTimeout(() => frame({ type: "reasoning-start", id: "r1" }), 90);
    setTimeout(() => frame({ type: "reasoning-delta", id: "r1", delta: "先想一下" }), 110);
    setTimeout(() => frame({ type: "tool-input-start", toolCallId: "c1", toolName: "bash" }), 160);
    setTimeout(() => frame({ type: "tool-input-available", toolCallId: "c1", toolName: "bash", input: { cmd: "ls" } }), 180);
    setTimeout(() => frame({ type: "tool-output-available", toolCallId: "c1", output: "a.txt" }), 220);
    setTimeout(() => frame({ type: "text-start", id: "t1" }), 260);
    setTimeout(() => frame({ type: "text-delta", id: "t1", delta: "处理完了" }), 300);
    setTimeout(() => frame({ type: "finish" }), 380);
    setTimeout(() => {
      frame("[DONE]");
      running = false;
    }, 420);
    return;
  }

  send(404, { code: "not_found", error: "no route" });
});

/* ------------------------------ 跑构建产物 ------------------------------ */

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const port = server.address().port;

const dom = new JSDOM('<!doctype html><html><body><div id="app"></div></body></html>', {
  url: "http://localhost/",
  pretendToBeVisual: true,
});
Object.assign(globalThis, {
  window: dom.window,
  document: dom.window.document,
  HTMLElement: dom.window.HTMLElement,
  Node: dom.window.Node,
  MutationObserver: dom.window.MutationObserver,
  CustomEvent: dom.window.CustomEvent,
  Event: dom.window.Event,
  localStorage: dom.window.localStorage,
  requestAnimationFrame: (cb) => setTimeout(() => cb(Date.now()), 0),
});
dom.window.matchMedia = (q) => ({
  matches: false,
  media: q,
  addEventListener() {},
  removeEventListener() {},
  addListener() {},
  removeListener() {},
  dispatchEvent() {
    return false;
  },
});

dom.window.localStorage.setItem(
  "pideck-pairing-v1",
  JSON.stringify({ baseUrl: `http://127.0.0.1:${port}`, token: "mock-token-value", savedAt: Date.now() }),
);

const entry = readdirSync(DIST).find((n) => /^index-.*\.js$/.test(n));
await import(pathToFileURL(path.join(DIST, entry)).href);

const doc = dom.window.document;
const wait = async (fn, ms = 8000) => {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (fn()) return true;
    await new Promise((r) => setTimeout(r, 40));
  }
  return false;
};

const results = [];
const check = (name, ok, detail = "") => results.push({ name, ok, detail });

await wait(() => doc.querySelectorAll(".session").length > 0);
doc.querySelector(".session")?.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
await wait(() => doc.querySelectorAll(".history .msg").length > 0);

// --- 1) 发送后立即回显 ---
const textarea = doc.querySelector(".composer textarea");
const sendBtn = [...doc.querySelectorAll(".composer .btn")].find((b) => b.textContent === "发送");
textarea.value = "这段是测试提问";
textarea.dispatchEvent(new dom.window.Event("input"));
sendBtn.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));

const echoedFast = await wait(() => doc.querySelector(".history .msg--local") !== null, 1_500);
check("发送后 1.5s 内出现本地回显", echoedFast);

// --- 2) 运行中折叠块收起 + 正文确实在流 ---
await wait(() => doc.querySelectorAll(".live .process").length > 0, 1_500);
const openGroups = [...doc.querySelectorAll(".live .process")].filter((d) => d.hasAttribute("open")).length;
check("运行中「执行过程」默认收起", openGroups === 0, `展开 ${openGroups} 个`);

const bodyAppeared = await wait(() => doc.querySelectorAll(".live .msg-body").length > 0, 2_000);
check("流式正文出现", bodyAppeared);

// 工具与思考都必须在折叠块内部，不能裸露在流式区顶层
const nakedProcess = doc.querySelectorAll(".live > .tool, .live > .reasoning").length;
check("工具/思考没有裸露在顶层", nakedProcess === 0, `裸露 ${nakedProcess} 个`);

// --- 2.8) 主按钮在运行中应变成「停止」 ---
// 等按钮真的切到停止（流式 start 帧到达后才会 running）
const switched = await wait(() => {
  const b = [...doc.querySelectorAll(".composer .btn")].find((x) => x.classList.contains("btn-primary-action"));
  return (b?.textContent ?? "") === "停止";
}, 2_000);
const primary = [...doc.querySelectorAll(".composer .btn")].find((b) => b.classList.contains("btn-primary-action"));
check("运行中主按钮显示「停止」", switched, `实际: ${primary?.textContent}`);

// --- 2.5) 多帧更新后 group 数量必须稳定 ---
const groupCounts = [];
for (let i = 0; i < 6; i += 1) {
  await new Promise((r) => setTimeout(r, 90));
  const n = doc.querySelectorAll(".live .process").length;
  groupCounts.push(n);
}
const nonZero = groupCounts.filter((n) => n > 0);
const stable = nonZero.length > 0 && nonZero.every((n) => n === nonZero[0]) && nonZero[0] <= 2;
check("连续帧更新后折叠块数量稳定且不重复", stable, `各帧数量: ${groupCounts.join(",")}`);


// --- 3) 结束后收尾 ---
await wait(() => doc.querySelectorAll(".live > *").length === 0, 6_000);
const liveEmpty = doc.querySelectorAll(".live > *").length === 0;
const historyHasAnswer = [...doc.querySelectorAll(".history .msg-body")].some((b) =>
  (b.textContent ?? "").includes("好的，我来处理。"),
);
const noLocalLeft = doc.querySelectorAll(".msg--local").length === 0;

check("结束后流式区清空", liveEmpty);
check("结束后历史被重拉（含服务端回答）", historyHasAnswer);
check("本地回显被服务端历史替换（不重复）", noLocalLeft);
const afterLabel = [...doc.querySelectorAll(".composer .btn")].find((b) => b.classList.contains("btn-primary-action"))?.textContent ?? "";
check("结束后主按钮变回「发送」", afterLabel === "发送", `实际: ${afterLabel}`);
check("历史中自己的提问只出现一次", [...doc.querySelectorAll(".history .msg--user .msg-body")].filter((b) => (b.textContent ?? "").includes("这段是测试提问")).length === 1);

/* ------------------------------ 输出 ------------------------------ */

// --- 4) 问答卡持久性（回归：切走再切回卡片丢失）---
pendingAsk = true;
const row = doc.querySelector(".session");
row?.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
const hadCard = await wait(() => doc.querySelectorAll(".ask-card").length > 0, 6_000);
check("有 pending 时进入会话显示问答卡", hadCard, `卡片数 ${doc.querySelectorAll(".ask-card").length}`);
if (hadCard) {
  const title = doc.querySelector(".ask-card .ask-title")?.textContent;
  check("问答卡标题正确", title === "部署到哪个环境？", `实际: ${title}`);
}

// 切走（无卡片）→ 切回（有卡片）
pendingAsk = false;
row?.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
await new Promise((r) => setTimeout(r, 700));
pendingAsk = true;
row?.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
const restored = await wait(() => doc.querySelectorAll(".ask-card").length > 0, 6_000);
check("切走再切回问答卡仍在", restored, `卡片数 ${doc.querySelectorAll(".ask-card").length}`);
pendingAsk = false;

let failed = 0;
console.log("\n发送链路验证（mock，无真实会话）\n");
for (const r of results) {
  console.log(`  ${r.ok ? "OK  " : "FAIL"}  ${r.name}${r.detail ? `  (${r.detail})` : ""}`);
  if (!r.ok) failed += 1;
}
console.log(`\n${results.length - failed}/${results.length} 通过\n`);

dom.window.close();
server.closeAllConnections?.();
server.close();
process.exit(failed === 0 ? 0 : 1);
