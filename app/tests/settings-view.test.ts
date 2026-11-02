/**
 * 设置页（jsdom）：分区渲染、开关回调、折叠分组、设备行、返回。
 *
 * 这里不测主进程接线，只保证「数据进来 → 该出现的元素出现 → 点击调对回调」。
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

const { createSettingsPage } = await import("../src/ui/settings-view.ts");

function baseData(overrides: Record<string, unknown> = {}) {
  return {
    theme: { mode: "dark", options: [
      { value: "system", label: "跟随系统" },
      { value: "light", label: "浅色" },
      { value: "dark", label: "深色" },
    ] },
    notify: { enabled: true },
    shell: { native: true },
    connection: { serviceUrl: "http://10.0.0.2:8765", token: "abcdefgh12345678", status: "live", version: "0.1.6" },
    devices: [
      { id: "http://a", label: "家里", url: "http://10.0.0.2:8765", active: true, running: 1, pending: 0, enabled: true },
      { id: "http://b", label: "公司", url: "http://10.0.0.3:8765", active: false, running: 0, pending: 2, enabled: true },
    ],
    models: [{ key: "deepseek::chat", name: "DeepSeek Chat", provider: "deepseek", modelId: "chat" }],
    skills: [
      {
        sourceId: "pi-global",
        label: "pi 全局",
        rows: [
          { key: "pi-global::usage-probe", name: "usage-probe", sourceId: "pi-global", sourceLabel: "pi 全局", description: "查用量", enabled: true, valid: true, userOnly: false, warnings: [] },
          { key: "pi-global::broken", name: "broken", sourceId: "pi-global", sourceLabel: "pi 全局", description: "", enabled: false, valid: false, userOnly: false, warnings: [] },
        ],
      },
    ],
    extensions: [
      { key: "user::plan-mode", source: "builtin:plan-mode", name: "计划模式", scope: "user", enabled: true, description: "" },
    ],
    dshPlugins: [],
    // 数据里带而不是走 handler：Flutter 版只收 JSON，拿不到闭包。
    dshTarget: "会话「DSH 测试」",
    loading: false,
    ...overrides,
  };
}

/** 按折叠头文案取那个分组（「模型列表」「pi 全局」「扩展列表」…顺序会变）。 */
function foldByTitle(root: HTMLElement, title: string): HTMLElement {
  const fold = [...root.querySelectorAll(".settings-fold")].find(
    (el) => el.querySelector(".settings-fold-title")?.textContent === title,
  );
  assert.ok(fold, `找不到折叠分组：${title}`);
  return fold as HTMLElement;
}

function makePage() {
  const calls: string[] = [];
  const page = createSettingsPage({
    onClose: () => calls.push("close"),
    onTheme: (mode) => calls.push(`theme:${mode}`),
    onToggleNotify: (next) => calls.push(`notify:${next}`),
    onToggleShell: (next) => calls.push(`shell:${next}`),
    onDeviceActivate: (id) => calls.push(`device:${id}`),
    onDeviceMenu: (id) => calls.push(`deviceMenu:${id}`),
    onAddDevice: () => calls.push("addDevice"),
    onDisconnect: () => calls.push("disconnect"),
    onToggleSkill: (_section, name, next) => calls.push(`skill:${name}:${next}`),
    onToggleExtension: (_extension, next) => calls.push(`ext:${next}`),
    onOpenUsage: () => calls.push("usage"),
    onRefresh: () => calls.push("refresh"),
    onDshPluginAction: (plugin, action) => calls.push(`dsh:${plugin.id}:${action}`),
    onDshPluginInstall: (input) => calls.push(`dshInstall:${input.idPrefix}:${input.name}`),
  });
  return { page, calls };
}

test("五个分区都在", () => {
  const { page } = makePage();
  page.update(baseData() as never);
  const titles = [...page.el.querySelectorAll(".settings-section-title")].map((el) => el.textContent);
  assert.deepEqual(titles, ["外观", "通知", "界面", "连接与设备（2）", "pi 概况", "技能", "扩展", "DSH 插件", "关于"]);
  page.destroy();
});

test("主题当前项高亮，点另一个回调", () => {
  const { page, calls } = makePage();
  page.update(baseData() as never);
  const items = [...page.el.querySelectorAll(".settings-choice-item")];
  assert.equal(items.filter((el) => el.classList.contains("is-active")).length, 1);
  assert.equal(items[2]!.classList.contains("is-active"), true, "dark 应高亮");
  (items[1] as unknown as HTMLElement).click();
  assert.deepEqual(calls, ["theme:light"]);
  page.destroy();
});

test("通知开关可点，状态渲染正确", () => {
  const { page, calls } = makePage();
  page.update(baseData() as never);
  const knob = page.el.querySelector(".settings-switch");
  assert.equal(knob?.classList.contains("is-on"), true);
  (page.el.querySelector(".settings-row--switch") as unknown as HTMLElement).click();
  assert.deepEqual(calls, ["notify:false"]);
  page.destroy();
});

test("界面开关：关掉原生主界面会回传 shell:false", () => {
  const { page, calls } = makePage();
  page.update(baseData() as never);
  const rows = [...page.el.querySelectorAll(".settings-row--switch")];
  const shellRow = rows.find((row) => /原生界面/.test(row.textContent ?? ""));
  assert.ok(shellRow, "没渲染出「用原生界面做主界面」这一行");
  (shellRow as unknown as HTMLElement).click();
  assert.deepEqual(calls, ["shell:false"]);
  page.destroy();
});

test("设备行：当前设备标使用中，点另一台切过去", () => {
  const { page, calls } = makePage();
  page.update(baseData() as never);
  const rows = [...page.el.querySelectorAll(".settings-row--action")];
  assert.match(rows[0]!.textContent ?? "", /家里/);
  assert.match(rows[0]!.textContent ?? "", /使用中/);
  assert.match(rows[1]!.textContent ?? "", /2 待答/);
  (rows[1] as unknown as HTMLElement).click();
  assert.deepEqual(calls, ["device:http://b"]);
  page.destroy();
});

test("设备区的两个动作按钮", () => {
  const { page, calls } = makePage();
  page.update(baseData() as never);
  for (const button of [...page.el.querySelectorAll(".settings-action")] as unknown as HTMLElement[]) {
    button.click();
  }
  assert.deepEqual(calls, ["addDevice", "disconnect"]);
  page.destroy();
});

test("技能折叠：默认收起，展开后渲染开关行", () => {
  const { page, calls } = makePage();
  page.update(baseData() as never);
  const skillFold = foldByTitle(page.el, "pi 全局");
  assert.equal((skillFold.querySelector(".settings-fold-body") as HTMLElement).style.display, "none");

  (skillFold.querySelector(".settings-fold-head") as unknown as HTMLElement).click();
  assert.equal((skillFold.querySelector(".settings-fold-body") as HTMLElement).style.display, "");

  const switches = [...skillFold.querySelectorAll(".settings-row--switch")] as unknown as HTMLElement[];
  assert.equal(switches.length, 2);
  // 无效技能置灰
  assert.equal((switches[1] as unknown as HTMLButtonElement).disabled, true);
  switches[0]!.click();
  assert.deepEqual(calls, ["skill:usage-probe:false"]);
  page.destroy();
});

test("扩展开关回调带 enabled", () => {
  const { page, calls } = makePage();
  page.update(baseData() as never);
  const extFold = foldByTitle(page.el, "扩展列表");
  (extFold.querySelector(".settings-fold-head") as unknown as HTMLElement).click();
  (extFold.querySelector(".settings-row--switch") as unknown as HTMLElement).click();
  assert.deepEqual(calls, ["ext:false"]);
  page.destroy();
});

test("额度入口与刷新按钮", () => {
  const { page, calls } = makePage();
  page.update(baseData() as never);
  const usageRow = [...page.el.querySelectorAll(".settings-row--action")].find((el) =>
    (el.textContent ?? "").includes("供应商额度"),
  ) as unknown as HTMLElement;
  usageRow.click();
  (page.el.querySelector(".settings-refresh") as unknown as HTMLElement).click();
  assert.deepEqual(calls, ["usage", "refresh"]);
  page.destroy();
});

test("关于区：版本、地址、令牌脱敏", () => {
  const { page } = makePage();
  page.update(baseData() as never);
  const text = page.el.textContent ?? "";
  assert.match(text, /0\.1\.6/);
  assert.match(text, /10\.0\.0\.2:8765/);
  assert.match(text, /abcd…5678/);
  assert.ok(!text.includes("abcdefgh12345678"), "令牌不能明文出现");
  page.destroy();
});

test("加载中 / 读取失败都有提示", () => {
  const { page } = makePage();
  page.update(baseData({ loading: true }) as never);
  assert.match(page.el.textContent ?? "", /正在读取/);

  page.update(baseData({ loadError: "部分数据读取失败：503" }) as never);
  assert.match(page.el.textContent ?? "", /503/);
  page.destroy();
});

test("返回按钮与销毁", () => {
  const { page, calls } = makePage();
  page.update(baseData() as never);
  dom.window.document.body.append(page.el);
  const back = page.el.querySelector(".settings-back") as unknown as HTMLElement;
  back.click();
  assert.deepEqual(calls, ["close"]);
  page.destroy();
  assert.equal(dom.window.document.querySelector(".settings-page"), null);
});

test("DSH 插件：动态插件能启停，卸载要点两次", () => {
  const { page, calls } = makePage();
  page.update(
    baseData({
      dshPlugins: [
        { id: "note-abc", name: "note", version: "", running: true, description: "配笔记", kind: "dynamic", packageId: "pkg-1", status: "running" },
        { id: "row-1", name: "dsh-todo", version: "", running: true, description: "随包内置", kind: "static", packageId: "", status: "running" },
      ],
    }) as never,
  );

  const rows = [...page.el.querySelectorAll(".settings-row--stacked")] as unknown as HTMLElement[];
  assert.equal(rows.length, 2);

  const dynamicButtons = [...rows[0]!.querySelectorAll(".settings-mini-btn")] as unknown as HTMLElement[];
  assert.deepEqual(dynamicButtons.map((el) => el.textContent), ["停止", "卸载"]);

  dynamicButtons[0]!.click();
  assert.deepEqual(calls, ["dsh:note-abc:stop"], "运行中点的是停止");

  // 卸载要两次：第一次只换文案
  dynamicButtons[1]!.click();
  assert.deepEqual(calls, ["dsh:note-abc:stop"]);
  assert.equal(dynamicButtons[1]!.textContent, "确认卸载");
  dynamicButtons[1]!.click();
  assert.deepEqual(calls, ["dsh:note-abc:stop", "dsh:note-abc:uninstall"]);

  // 静态条目只读：一个按钮都没有
  assert.equal(rows[1]!.querySelectorAll(".settings-mini-btn").length, 0);
  page.destroy();
});

test("DSH 插件：安装表单校验 ID 前缀", () => {
  const { page, calls } = makePage();
  page.update(baseData() as never);

  const form = page.el.querySelector(".settings-install") as unknown as HTMLElement;
  assert.equal(form.classList.contains("is-hidden"), true, "默认收起");

  const installRow = [...page.el.querySelectorAll(".settings-row--action")].find((el) =>
    (el.textContent ?? "").includes("安装插件"),
  ) as unknown as HTMLElement;
  installRow.click();
  assert.equal(form.classList.contains("is-hidden"), false);

  const inputs = [...form.querySelectorAll(".settings-input")] as unknown as HTMLInputElement[];
  const submit = [...form.querySelectorAll(".settings-mini-btn")].find((el) => el.textContent === "安装") as unknown as HTMLElement;
  const note = form.querySelector(".settings-install-note") as unknown as HTMLElement;

  inputs[0]!.value = "no";
  inputs[1]!.value = "笔记";
  submit.click();
  assert.deepEqual(calls, [], "前缀不合法不该提交");
  assert.match(note.textContent ?? "", /3-6 个小写字母/);

  inputs[0]!.value = "note";
  submit.click();
  assert.deepEqual(calls, ["dshInstall:note:笔记"]);
  page.destroy();
});
