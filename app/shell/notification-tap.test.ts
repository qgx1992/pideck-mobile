/**
 * 「点会话完成通知 → 直接进那个会话」的接线守门测试。
 *
 * 原生这一头一直是通的（Intent 带 `pideckSessionId`，`RunWatchPlugin.consumeLaunchSession`
 * 等 JS 来取），缺的是 JS 从不取 —— 于是点通知只把 App 唤醒，停在启动页。
 * 这几条断言把两头钉住：去掉任一端都会红。
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";

const appDir = path.join(import.meta.dirname, "..");
const read = (...parts: string[]): string => readFileSync(path.join(appDir, ...parts), "utf8");

const service = read("android/app/src/main/java/dev/pideck/mobile/RunWatchService.java");
const plugin = read("android/app/src/main/java/dev/pideck/mobile/RunWatchPlugin.java");
const activity = read("android/app/src/main/java/dev/pideck/mobile/MainActivity.java");
const bootTs = read("shell/boot.ts");
const launcherTs = read("shell/launcher.ts");
const launchSession = read("shell/launch-session.ts");

describe("原生：通知 Intent 带会话与设备", () => {
	it("两条通知（完成 / 待回答）都塞了 sessionId 与 deviceId", () => {
		assert.equal(
			(service.match(/intent\.putExtra\(MainActivity\.EXTRA_SESSION_ID, sessionId\);/g) ?? []).length,
			2,
		);
		assert.equal(
			(service.match(/intent\.putExtra\(MainActivity\.EXTRA_DEVICE_ID, deviceId\);/g) ?? []).length,
			2,
		);
	});

	it("看护服务记住了是哪台设备在看", () => {
		assert.match(service, /intent\.putExtra\(EXTRA_DEVICE_ID, deviceId == null \? "" : deviceId\)/);
		assert.match(plugin, /RunWatchService\.start\([\s\S]*?deviceId == null/);
	});

	it("MainActivity 把设备 id 一起接住、一起交出去", () => {
		assert.match(activity, /pendingDeviceId = intent\.getStringExtra\(EXTRA_DEVICE_ID\)/);
		assert.match(activity, /static String takePendingDeviceId\(\)/);
		assert.match(plugin, /result\.put\("deviceId", deviceId == null \? "" : deviceId\)/);
	});
});

describe("JS：真的把那个 id 取走了", () => {
	it("launch-session 会调用 consumeLaunchSession", () => {
		assert.match(launchSession, /RunWatch\.consumeLaunchSession\(\)/);
	});

	it("boot 冷启动 + 热启动都接了", () => {
		assert.match(bootTs, /void openPendingLaunch\(\{ mode: "replace" \}\)/);
		assert.match(bootTs, /installLaunchWatcher\(\{ mode: "push" \}\)/);
	});

	it("启动页认 /s/<id> 与后台回来的通知", () => {
		assert.match(launcherTs, /takeSessionRouteFromUrl\(\)/);
		assert.match(launcherTs, /consumeNativeLaunch\(\)/);
		assert.match(launcherTs, /App\.addListener\("appStateChange"/);
	});

	it("切会话用 popstate 兜住上游挂载时序，且不会覆盖用户的新选择", () => {
		assert.match(launchSession, /window\.dispatchEvent\(new PopStateEvent\("popstate"\)\)/);
		assert.match(launchSession, /if \(sessionFromPath\(location\.pathname\) !== sessionId\) return;/);
		assert.match(launchSession, /同一会话 2 秒内只跳一次/);
	});
});
