#!/usr/bin/env node
/**
 * 真机渲染冒烟（手动跑，不进 CI）：
 * 用 jsdom 执行 dist 构建产物，连**真的** PiDeck，把侧栏渲染结果打印出来。
 *
 *   node scripts/smoke-ui.mjs --base http://127.0.0.1:8765 --token <token>
 *
 * 令牌不会被打日志；也可以用 PIDECK_BASE / PIDECK_TOKEN。
 */

import { readdirSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";
import { JSDOM } from "jsdom";

const APP_DIR = path.resolve(import.meta.dirname, "..");
const DIST_ASSETS = path.join(APP_DIR, "dist", "assets");

function arg(name) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

const baseUrl = arg("base") ?? process.env.PIDECK_BASE;
const token = arg("token") ?? process.env.PIDECK_TOKEN;
if (!baseUrl || !token) {
  console.error("缺少 --base / --token（或 PIDECK_BASE / PIDECK_TOKEN）");
  process.exit(1);
}

const dom = new JSDOM('<!doctype html><html><body><div id="app"></div></body></html>', {
  url: "http://localhost/",
  pretendToBeVisual: true,
});
const g = globalThis;
g.window = dom.window;
g.document = dom.window.document;
g.HTMLElement = dom.window.HTMLElement;
g.Node = dom.window.Node;
g.MutationObserver = dom.window.MutationObserver;
g.CustomEvent = dom.window.CustomEvent;
g.Event = dom.window.Event;
g.localStorage = dom.window.localStorage;
g.requestAnimationFrame = (cb) => setTimeout(() => cb(Date.now()), 0);
dom.window.matchMedia = (query) => ({
  matches: false,
  media: query,
  onchange: null,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  addListener: () => undefined,
  removeListener: () => undefined,
  dispatchEvent: () => false,
});

g.localStorage.setItem(
  ["pideck", "pairing", "v1"].join("-"),
  JSON.stringify({ baseUrl, token, savedAt: Date.now() }),
);

const entry = readdirSync(DIST_ASSETS).find((name) => /^index-.*\.js$/.test(name));
if (!entry) {
  console.error("dist/assets 里找不到 index-*.js，先跑 npm run build");
  process.exit(1);
}
await import(pathToFileURL(path.join(DIST_ASSETS, entry)).href);

async function waitFor(check, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (check()) return true;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  return false;
}

const ok = await waitFor(() => dom.window.document.querySelectorAll(".project-head").length > 0);
const doc = dom.window.document;

if (!ok) {
  console.error("✗ 8 秒内没有渲染出侧栏。页面当前内容：");
  console.error((doc.getElementById("app")?.textContent ?? "").slice(0, 500));
  process.exit(1);
}

const groups = [...doc.querySelectorAll(".project-group")];
console.log(`✓ 已渲染 ${groups.length} 个项目分组、${doc.querySelectorAll(".session").length} 个会话\n`);

for (const group of groups.slice(0, 8)) {
  const name = group.querySelector(".pname")?.textContent;
  const count = group.querySelector(".pcount")?.textContent;
  console.log(`  ${name}  (${count})`);
  for (const session of [...group.querySelectorAll(".session")].slice(0, 3)) {
    console.log(`     · ${session.querySelector(".sname")?.textContent}`);
  }
}

const header = doc.querySelector(".header")?.textContent?.trim();
console.log(`\nheader: ${header || "(空态)"}`);

// 打开第一个会话，检查历史消息是否真的渲染出来
const firstSession = doc.querySelector(".session");
if (firstSession) {
  firstSession.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
  const rendered = await waitFor(() => doc.querySelectorAll(".history .msg, .history .tool").length > 0, 6000);
  const count = doc.querySelectorAll(".history .msg, .history .tool").length;
  const tools = doc.querySelectorAll(".history .tool").length;
  const bubble = doc.querySelector(".history .msg-body")?.textContent ?? "";
  console.log(`
历史消息：${count} 条（其中工具 ${tools} 条）—— ${rendered ? "✓ 已渲染" : "✗ 空"}`);
  if (bubble) console.log(`首条正文：${bubble.slice(0, 60).replace(/\s+/g, " ")}…`);
}

// 回到 pi 会话，确认 chips 显示真实模型与思考档位
const piSession = [...doc.querySelectorAll(".session")].find(
  (el) => !(el.querySelector(".smeta")?.textContent ?? "").includes("dsh"),
);
if (piSession) {
  piSession.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
  await waitFor(() => doc.querySelectorAll(".history .msg").length > 0, 6000);
  const piChips = [...doc.querySelectorAll(".composer-chips .chip")].map((el) => el.textContent);
  console.log(`
pi 会话 chips: ${piChips.join(" | ")}`);
}

// 验证「执行过程」折叠：历史里的工具/思考应被收进少数几个折叠块
if (doc.querySelectorAll(".history .process").length > 0) {
  const groups = doc.querySelectorAll(".history .process");
  const tools = doc.querySelectorAll(".history .process .tool").length;
  const reasonings = doc.querySelectorAll(".history .process .reasoning").length;
  const topLevelTools = doc.querySelectorAll(".history > .tool, .history > .reasoning").length;
  console.log("");
  console.log(`执行过程折叠: ${groups.length} 个折叠块，内含 ${tools} 个工具 / ${reasonings} 段思考`);
  console.log(`  顶层未折叠的工具/思考: ${topLevelTools} (应为 0)`);
  const titles = [...groups].slice(0, 3).map((g) => g.querySelector(".process-title")?.textContent);
  console.log(`  标题示例: ${titles.join(" | ")}`);
}

// 再点一个 DSH 会话，验证「读不到历史」时错误卡是否给对人话
const dshSession = [...doc.querySelectorAll(".session")].find((el) =>
  (el.querySelector(".smeta")?.textContent ?? "").includes("dsh"),
);
if (dshSession) {
  dshSession.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
  const shown = await waitFor(() => doc.querySelector(".error-card") !== null, 6000);
  const title = doc.querySelector(".error-card .error-title")?.textContent ?? "";
  const detail = doc.querySelector(".error-card .error-detail")?.textContent ?? "";
  console.log("");
  console.log(`DSH 会话：${shown ? "✓ 显示了错误卡" : "✗ 没有错误卡"}`);
  if (title) console.log(`  ${title}  (${detail})`);
} else {
  console.log("（这个列表里没有 DSH 会话，跳过降级验证）");
}

// 验证顶部栏随会话切换刷新（回归：之前切会话标题不动）
const allSessions = [...doc.querySelectorAll(".session")];
if (allSessions.length >= 2) {
  allSessions[0].dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
  await waitFor(() => (doc.querySelector(".header .title")?.textContent ?? "") !== "PiDeck", 6000);
  const t1 = doc.querySelector(".header .title")?.textContent;
  const s1 = doc.querySelector(".header .sub")?.textContent;

  allSessions[1].dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
  await new Promise((r) => setTimeout(r, 600));
  const t2 = doc.querySelector(".header .title")?.textContent;
  const s2 = doc.querySelector(".header .sub")?.textContent;

  console.log("");
  console.log("顶部栏随会话刷新:");
  console.log(`  会话A: ${t1}  |  ${s1}`);
  console.log(`  会话B: ${t2}  |  ${s2}`);
  console.log(`  ${t1 !== t2 ? "OK 标题已变化" : "FAIL 标题没变"}`);
}

// 右侧工作区抽屉：能打开、能切 Tab、能看到 Git 数据
const wsBtn = [...doc.querySelectorAll(".header .icon-btn")].find((b) => (b.title || "").includes("工作区"));
console.log("");
console.log(`工作区按钮: ${wsBtn ? "存在" : "缺失"}`);
if (wsBtn) {
  wsBtn.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
  const opened = await waitFor(() => doc.querySelector(".body.show-workspace") !== null, 3000);
  console.log(`  抽屉打开: ${opened ? "OK" : "FAIL"}`);
  const tabs = [...doc.querySelectorAll(".ws-tab")].map((t) => t.textContent);
  console.log(`  Tab: ${tabs.join(" / ")}`);
  // 等「读取中…」消失（真正结果出来）
  await waitFor(() => {
    const loading = doc.querySelector(".ws-content .ws-empty");
    if (loading && (loading.textContent ?? "").includes("读取")) return false;
    return doc.querySelectorAll(".ws-content .ws-branch, .ws-content .ws-empty").length > 0;
  }, 6000);
  const branch = doc.querySelector(".ws-branch-name")?.textContent;
  const fileCount = doc.querySelectorAll(".ws-content .ws-file").length;
  const commits = doc.querySelectorAll(".ws-content .ws-commit").length;
  const empty = doc.querySelector(".ws-content .ws-empty")?.textContent;
  console.log(`  Git: 分支=${branch ?? "-"} 变更项=${fileCount} 提交=${commits} ${empty ? `提示=${empty}` : ""}`);

  // 切到「文件」Tab
  const filesTab = [...doc.querySelectorAll(".ws-tab")].find((t) => t.textContent === "文件");
  filesTab?.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
  await waitFor(() => doc.querySelectorAll(".ws-content .ws-file").length > 0, 4000);
  const entries = doc.querySelectorAll(".ws-content .ws-file").length;
  const crumb = doc.querySelector(".ws-crumb")?.textContent;
  const mentions = doc.querySelectorAll(".ws-content .ws-mention").length;
  const dirs = [...doc.querySelectorAll(".ws-content .ws-file")].filter((r) => r.querySelector(".ws-mention") === null).length;
  console.log(`  文件: 根=${crumb} 条目=${entries} 其中可引用(@)=${mentions} 目录=${dirs}`);
  doc.querySelector(".workspace-scrim")?.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
}

// 跳转历史：点一条能否真的定位到 DOM
const hasFab = doc.querySelector(".fab") !== null;
console.log("");
console.log(`历史悬浮按钮: ${hasFab ? "存在" : "缺失"}`);
if (hasFab) {
  doc.querySelector(".fab").dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
  await new Promise((r) => setTimeout(r, 400));
  const head = doc.querySelector(".sheet-backdrop .sheet h2")?.textContent;
  const items = doc.querySelectorAll(".sheet-backdrop .jump-item").length;
  console.log(`  面板标题: ${head}`);
  console.log(`  可跳转条数: ${items}`);

  // 取面板里第 3 条的预览，点它，再看对应元素是否被高亮
  const rows = [...doc.querySelectorAll(".sheet-backdrop .jump-item")];
  const target = rows[2] ?? rows[0];
  const preview = target?.querySelector(".jump-text")?.textContent ?? "";
  target?.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
  await new Promise((r) => setTimeout(r, 500));
  const flashed = doc.querySelectorAll(".history .msg.is-flash").length;
  console.log(`  点击「${preview.slice(0, 24)}…」→ 高亮元素数: ${flashed} ${flashed === 1 ? "OK" : "FAIL"}`);
  const flashText = doc.querySelector(".history .msg.is-flash .msg-body")?.textContent ?? "";
  console.log(`  高亮内容匹配: ${preview.startsWith(flashText.trim().slice(0, 12)) || flashText.includes(preview.slice(-12)) ? "是" : "否"} (${flashText.slice(0, 30)})`);
  doc.querySelector(".sheet-backdrop")?.remove();
}

// 检查 composer 上的模型 / 思考 chip 是否按会话渲染
const chips = [...doc.querySelectorAll(".composer-chips .chip")].map((el) => el.textContent);
console.log(`
composer chips: ${chips.join(" | ") || "(无)"}`);

dom.window.close();
process.exit(0);
