/**
 * 扫码取景的守门测试。
 *
 * 相机预览铺在 WebView **下面**，所以「WebView / 窗口 / 页面画布」任何一层不透明，
 * 用户看到的就是一块纯色。0.4.2 给安全区样式在 `html` 上铺了画布底色
 * （`--color-bg-app` 在启动页没有定义 → 兜底白色），扫码预览就是这样变白的。
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";

const appDir = path.join(import.meta.dirname, "..");
const read = (file: string): string => readFileSync(path.join(appDir, file), "utf8");

const launcherHtml = read("shell/launcher.html");
const launcherTs = read("shell/launcher.ts");
const bootTs = read("shell/boot.ts");
const mainActivity = read("android/app/src/main/java/dev/pideck/mobile/MainActivity.java");
const navPlugin = read("android/app/src/main/java/dev/pideck/mobile/NavigationBarPlugin.java");

describe("启动页画布必须透明（扫码时能看见相机）", () => {
	it("html 一层没有不透明底色", () => {
		assert.match(launcherHtml, /html \{ margin: 0; height: 100%; background: transparent; \}/);
	});

	it("扫码态把 html 与 body 一起压透明", () => {
		assert.match(launcherHtml, /html\.barcode-scanner-active,\s*\n\s*body\.barcode-scanner-active \{ background: transparent !important; \}/);
	});

	it("扫码开始/结束时各断言一次原生透明（不只靠 onCreate）", () => {
		assert.match(launcherTs, /setScannerTransparency\(\{ active: true \}\)/);
		assert.match(launcherTs, /setScannerTransparency\(\{ active: false, color: "#0b0b0c" \}\)/);
	});

	it("原生侧确实有这个方法，并且同时处理 WebView 与窗口", () => {
		assert.match(navPlugin, /public void setScannerTransparency\(PluginCall call\)/);
		assert.match(navPlugin, /webView\.setBackgroundColor\(target\)/);
		assert.match(navPlugin, /getDecorView\(\)\.setBackgroundColor/);
	});
});

describe("只有聊天页铺画布底色", () => {
	it("聊天页启动时传了 canvas", () => {
		assert.match(bootTs, /startSafeArea\(\{ canvas: /);
	});

	it("启动页不传 canvas", () => {
		assert.match(launcherTs, /^startSafeArea\(\);$/m);
		assert.doesNotMatch(launcherTs, /startSafeArea\(\{/);
	});

	it("MainActivity 仍然在 onCreate 里把 WebView 设成透明", () => {
		assert.match(mainActivity, /setBackgroundColor\(Color\.TRANSPARENT\)/);
	});
});
