/**
 * 「什么时候该弹通知」的守门测试。
 *
 * 两条规矩：
 * 1. 前台服务不能因为换页被停掉 —— 从聊天页退回启动页也是一次 pagehide，
 *    停掉之后在启动页等（甚至离开 App）就彻底没有通知了。
 * 2. 静音只针对「App 在前台且停在聊天页」：那页用户正盯着对话。启动页没有
 *    任何会话视图，跑完了必须提醒。
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";

const appDir = path.join(import.meta.dirname, "..");
const read = (...parts: string[]): string => readFileSync(path.join(appDir, ...parts), "utf8");

const service = read("android/app/src/main/java/dev/pideck/mobile/RunWatchService.java");
const plugin = read("android/app/src/main/java/dev/pideck/mobile/RunWatchPlugin.java");
const bootTs = read("shell/boot.ts");
const launcherTs = read("shell/launcher.ts");
const runWatchTs = read("shell/run-watch.ts");

describe("原生：通知静音只发生在聊天页", () => {
	it("两处通知都走 shouldNotify()", () => {
		assert.equal((service.match(/if \(shouldNotify\(\)\)/g) ?? []).length, 2);
		// 旧的「前台就静音」不能再出现，否则启动页永远收不到
		assert.equal((service.match(/if \(!appVisible\(\)\)/g) ?? []).length, 0);
	});

	it("shouldNotify 的语义：不可见 或 不在聊天页", () => {
		assert.match(service, /private boolean shouldNotify\(\) \{\s*return !appVisible\(\) \|\| !PAGE_CHAT\.equals\(page\);/);
	});

	it("page 从 JS 传进来、缺省按聊天页算", () => {
		assert.match(service, /public static final String EXTRA_PAGE = "page";/);
		assert.match(service, /private volatile String page = PAGE_CHAT;/);
		assert.match(service, /intent\.putExtra\(EXTRA_PAGE, page == null \? PAGE_CHAT : page\)/);
		assert.match(service, /if \(nextPage != null && !nextPage\.isEmpty\(\)\) page = nextPage;/);
		assert.match(plugin, /call\.getString\("page", "chat"\)/);
	});
});

describe("JS：两个页面都拉服务，并且各报各的身份", () => {
	it("聊天页报 chat，且不在 pagehide 上停服务", () => {
		assert.match(bootTs, /startRunWatch\(device, \{ page: "chat" \}\)/);
		assert.doesNotMatch(bootTs, /pagehide[\s\S]{0,80}runWatch\.stop\(\)/);
	});

	it("启动页报 launcher，并在回前台时补一拍", () => {
		assert.match(launcherTs, /startRunWatch\(activeDevice, \{ page: "launcher" \}\)/);
		assert.match(launcherTs, /runWatch\?\.poke\(\)/);
	});

	it("run-watch 把 page 透传给原生", () => {
		assert.match(runWatchTs, /export type ShellPage = "chat" \| "launcher";/);
		assert.match(runWatchTs, /deviceId: device\.id, page \}\)/);
	});
});
