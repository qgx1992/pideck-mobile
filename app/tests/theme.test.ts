/** 主题：模式解析、持久化、DOM 与状态栏同步（原生部分在无 Capacitor 时跳过）。 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM(
  '<!doctype html><html><head><meta name="theme-color" content="#18181b"></head><body></body></html>',
  { url: "http://localhost/" },
);
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
g.localStorage = dom.window.localStorage;

// 可控的系统主题
let systemLight = false;
dom.window.matchMedia = ((query: string) => ({
  matches: query.includes("light") ? systemLight : false,
  media: query,
  onchange: null,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  addListener: () => undefined,
  removeListener: () => undefined,
  dispatchEvent: () => false,
})) as unknown as typeof dom.window.matchMedia;

const { initTheme, setThemeMode, getThemeMode, resolveTheme, applyTheme, themeLabel, THEME_MODES } = await import(
  "../src/platform/theme.ts"
);

const root = dom.window.document.documentElement;
const meta = dom.window.document.querySelector('meta[name="theme-color"]')!;

function reset(): void {
  dom.window.localStorage.clear();
  systemLight = false;
}

test("默认跟随系统；系统浅色时解析为 light", () => {
  reset();
  assert.equal(getThemeMode(), "system");
  assert.equal(resolveTheme("system"), "dark");

  systemLight = true;
  assert.equal(resolveTheme("system"), "light");
});

test("显式模式不受系统影响", () => {
  reset();
  systemLight = true;
  assert.equal(resolveTheme("dark"), "dark");
  systemLight = false;
  assert.equal(resolveTheme("light"), "light");
});

test("applyTheme 写 data-theme / color-scheme / theme-color", () => {
  reset();
  applyTheme("light");
  assert.equal(root.dataset.theme, "light");
  assert.equal(root.style.colorScheme, "light");
  assert.equal(meta.getAttribute("content"), "#f7f7f9");

  applyTheme("dark");
  assert.equal(root.dataset.theme, "dark");
  assert.equal(root.style.colorScheme, "dark");
  assert.equal(meta.getAttribute("content"), "#18181b");
});

test("setThemeMode 持久化，重新 initTheme 能读回", () => {
  reset();
  setThemeMode("light");
  assert.equal(dom.window.localStorage.getItem(["pideck", "app", "theme"].join("-")), "light");
  assert.equal(getThemeMode(), "light");

  initTheme();
  assert.equal(root.dataset.theme, "light", "initTheme 应沿用已保存的浅色");
});

test("跟随系统时 applyTheme 会重新解析", () => {
  reset();
  setThemeMode("system");
  systemLight = true;
  assert.equal(applyTheme(), "light");
  systemLight = false;
  assert.equal(applyTheme(), "dark");
});

test("非法存储值回退到 system", () => {
  reset();
  dom.window.localStorage.setItem(["pideck", "app", "theme"].join("-"), "purple");
  initTheme();
  assert.equal(getThemeMode(), "system");
});

test("标签与顺序", () => {
  assert.equal(themeLabel("light"), "浅色");
  assert.equal(themeLabel("dark"), "深色");
  assert.equal(themeLabel("system"), "跟随系统");
  assert.deepEqual(THEME_MODES, ["system", "light", "dark"]);
});
