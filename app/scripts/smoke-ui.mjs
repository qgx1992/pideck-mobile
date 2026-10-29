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
const hasFab = doc.querySelector(".composer-fab") !== null;
const fabInInput = Boolean(doc.querySelector(".composer-input .composer-fab"));
console.log("");
console.log(`历史按钮: ${hasFab ? "存在" : "缺失"}  在输入框内=${fabInInput}`);
if (hasFab) {
  doc.querySelector(".composer-fab").dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
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

// 任务清单：切到「这个」会话（本身带 todo 的会话才有清单）再验一次
{
  const target = [...doc.querySelectorAll(".session")].find((el) =>
    (el.querySelector(".sname")?.textContent ?? "").includes("开发PiDeck手机端"),
  );
  if (target) {
    target.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true }));
    await waitFor(() => !doc.querySelector(".todo-strip.is-hidden"), 6000);
    const strip = doc.querySelector(".todo-strip");
    const items = strip?.querySelectorAll(".todo-item") ?? [];
    const running = strip?.querySelectorAll(".todo-item.is-in_progress").length ?? 0;
    const done = strip?.querySelectorAll(".todo-item.is-completed").length ?? 0;
    console.log(
      `
任务清单（带 todo 的会话）: 可见=${!strip?.classList.contains("is-hidden")} 条目=${items.length} 已完成=${done} 进行中=${running} 进度=${strip?.querySelector(".todo-progress")?.textContent ?? ""}`,
    );
    console.log(`  首条: ${strip?.querySelector(".todo-text")?.textContent ?? "(无)"}`);
  }
}

// 任务清单条带
{
  const strip = doc.querySelector(".todo-strip");
  const items = doc.querySelectorAll(".todo-item");
  const done = doc.querySelectorAll(".todo-item.is-completed").length;
  const running = doc.querySelectorAll(".todo-item.is-in_progress").length;
  const hidden = strip?.classList.contains("is-hidden");
  const progress = doc.querySelector(".todo-progress")?.textContent ?? "";
  console.log(
    `
任务清单: 存在=${Boolean(strip)} 隐藏=${hidden} 条目=${items.length} 已完成=${done} 进行中=${running} 进度=${progress}`,
  );
  const spinner = doc.querySelector(".run-status .run-spinner");
  console.log(`  运行中动画标识: ${Boolean(spinner)}`);
}

// Markdown：助手回答里应出现粗体 / 代码块 / 列表
{
  const bodies = [...doc.querySelectorAll(".msg-body--md")];
  const strong = doc.querySelectorAll(".md strong").length;
  const code = doc.querySelectorAll(".md-codeblock").length;
  const inlineCode = doc.querySelectorAll(".md-inline-code").length;
  const lists = doc.querySelectorAll(".md-list").length;
  const tables = doc.querySelectorAll(".md-table").length;
  const tokens = doc.querySelectorAll(".md-code .tok").length;
  const cursors = doc.querySelectorAll(".md .md-p").length;
  const sample = [...doc.querySelectorAll(".md strong")].slice(0, 3).map((el) => el.textContent);
  console.log(
    `
Markdown: 助手气泡=${bodies.length} 粗体=${strong} 代码块=${code} 行内代码=${inlineCode} 列表=${lists} 表格=${tables} 段落=${cursors} 高亮token=${tokens}`,
  );
  console.log(`  粗体样例: ${sample.join(" / ") || "(无)"}`);
}
// 折叠箭头：必须是 14px 的 SVG，且不能残留字面量 B8
{
  const heads = [...doc.querySelectorAll(".project-head .chev-icon")];
  const sizes = heads.map((el) => el.getAttribute("width"));
  const moreChevs = [...doc.querySelectorAll(".sessions-more .chev-icon")];
  const rotated = heads.filter((el) => el.classList.contains("is-expanded")).length;
  console.log(
    `
折叠箭头: 分组头=${heads.length} 尺寸=${[...new Set(sizes)].join("/") || "(无)"} 展开态=${rotated} 展开按钮箭头=${moreChevs.length}`,
  );
  console.log(`  残留 B8 文案=${(doc.body.textContent ?? "").includes("B8")}`);
  const proc = doc.querySelector(".process summary .chev-icon");
  console.log(`  执行过程箭头=${Boolean(proc)}`);
}

// 布局：@ / 必须嵌在输入框里，而不是下方工具行
{
  const inline = doc.querySelector(".composer-input .composer-inline-tools");
  const labels = inline ? [...inline.querySelectorAll("button")].map((el) => el.textContent.trim()) : [];
  console.log(`
inline tools: ${labels.join(" | ") || "(无)"}  在输入框内=${Boolean(inline)}`);

  const wrap = doc.querySelector(".composer-input");
  const rowChips = [...doc.querySelectorAll(".composer-row .chip")].map((el) => el.textContent.trim());
  console.log(`composer-row chips: ${rowChips.join(" | ") || "(无)"}`);

  const ctxChip = doc.querySelector(".chip--ctx");
  const ctxText = ctxChip?.querySelector(".ctx-text")?.textContent?.trim() ?? "";
  const ctxHidden = ctxChip ? ctxChip.classList.contains("is-hidden") : true;
  const ring = ctxChip?.querySelector(".ctx-ring");
  const pct = ring?.style.getPropertyValue("--ctx-pct") ?? "";
  console.log(`上下文 chip: 文本=${JSON.stringify(ctxText)} 隐藏=${ctxHidden} 环百分比=${pct || "(空)"} title=${ctxChip?.title ?? ""}`);
  const fab = doc.querySelector(".composer-fab");
  console.log(`布局自检: 输入框包裹=${Boolean(wrap)} @在包裹内=${Boolean(wrap?.querySelector(".composer-inline-tools"))} 历史在包裹内=${Boolean(wrap?.querySelector(".composer-fab"))} 行内不再有@=${!rowChips.includes("@")} 历史文字=${JSON.stringify(fab?.textContent ?? "")}`);
}

// 模型面板：搜索能过滤
{
  const modelBtn = [...doc.querySelectorAll(".composer-row .chip")].find((el) => !el.classList.contains("chip--ctx") && !el.textContent.includes("思考"));
  modelBtn?.click();
  const ok = await waitFor(() => doc.querySelector(".sheet-search"), 6000);
  if (!ok) {
    console.log("  面板内容: " + JSON.stringify((doc.querySelector(".sheet")?.textContent ?? "").slice(0, 160)));
    console.log("模型面板: 未打开搜索框");
  } else {
    // 只统计当前这个模型面板，别被残留的旧面板干扰
    const search = doc.querySelector(".sheet-search");
    const scope = search.closest(".sheet");
    // 取消按钮也是 .sheet-item，统计时要排除
    const items = () => scope.querySelectorAll(".sheet-item:not(.sheet-item--cancel)").length;

    // 打开面板时不能自动聚焦搜索框，否则手机会立刻弹出键盘挡住列表
    console.log(`  搜索框自动聚焦=${doc.activeElement === search}`);
    search.blur();

    const total = items();
    search.value = "kimi";
    search.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    await new Promise((r) => setTimeout(r, 50));
    const filtered = items();

    search.value = "zzzznope";
    search.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    await new Promise((r) => setTimeout(r, 50));
    const none = items();
    const noteVisible = ![...scope.querySelectorAll(".sheet-note")].every((el) => el.classList.contains("is-hidden"));

    console.log(`模型面板搜索: 全部=${total} 「kimi」=${filtered} 「zzzznope」=${none} 空提示可见=${noteVisible}`);
    doc.querySelector(".sheet-backdrop")?.remove();
  }
}

// 上下文面板：点 chip 应出明细
{
  doc.querySelector(".chip--ctx")?.click();
  const ok = await waitFor(() => doc.querySelector(".ctx-rows li"), 4000);
  const rows = [...doc.querySelectorAll(".ctx-row")].map((el) => el.textContent.replace(/\s+/g, " ").trim());
  console.log(`上下文面板: 已打开=${Boolean(ok)} 明细=${rows.join(" | ") || "(无)"}`);
  doc.querySelector(".sheet-backdrop")?.remove();
}

dom.window.close();
process.exit(0);
