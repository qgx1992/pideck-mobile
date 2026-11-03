/**
 * 「点通知 → 进那个会话」的纯逻辑。
 *
 * 原生侧把会话 id 塞进 Intent（`MainActivity.EXTRA_SESSION_ID`）并放在
 * `RunWatchPlugin.consumeLaunchSession()` 里等 JS 取；这里测的是 JS 这头怎么用。
 */
import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";

class MemoryStorage {
	private map = new Map<string, string>();
	get length(): number {
		return this.map.size;
	}
	clear(): void {
		this.map.clear();
	}
	getItem(key: string): string | null {
		return this.map.get(key) ?? null;
	}
	key(index: number): string | null {
		return [...this.map.keys()][index] ?? null;
	}
	removeItem(key: string): void {
		this.map.delete(key);
	}
	setItem(key: string, value: string): void {
		this.map.set(key, value);
	}
}

Object.defineProperty(globalThis, "localStorage", { value: new MemoryStorage(), configurable: true });

const {
	PENDING_LAUNCH_KEY,
	readPendingLaunch,
	writePendingLaunch,
	clearPendingLaunch,
	sessionFromPath,
	sessionTarget,
	pickLaunchDevice,
} = await import("./launch-session.ts");

beforeEach(() => localStorage.clear());

test("sessionFromPath 与上游同规则", () => {
	assert.equal(sessionFromPath("/s/abc-123"), "abc-123");
	assert.equal(sessionFromPath("/s/abc/extra"), "abc");
	assert.equal(sessionFromPath("/s/"), "");
	assert.equal(sessionFromPath("/s/bad id"), "");
	assert.equal(sessionFromPath("/"), "");
	assert.equal(sessionFromPath("/web.html"), "");
});

test("sessionTarget 会转义，空 id 回根路径", () => {
	assert.equal(sessionTarget("abc"), "/s/abc");
	assert.equal(sessionTarget(""), "/");
	assert.equal(sessionTarget("a b"), "/s/a%20b");
});

test("待跳转记录能往返，坏数据直接当没有", () => {
	writePendingLaunch({ sessionId: "s1", deviceId: "d1" });
	assert.deepEqual(readPendingLaunch(), { sessionId: "s1", deviceId: "d1" });
	clearPendingLaunch();
	assert.equal(readPendingLaunch(), null);

	localStorage.setItem(PENDING_LAUNCH_KEY, "{不是 json");
	assert.equal(readPendingLaunch(), null);
	localStorage.setItem(PENDING_LAUNCH_KEY, JSON.stringify({ sessionId: "" }));
	assert.equal(readPendingLaunch(), null);
	localStorage.setItem(PENDING_LAUNCH_KEY, JSON.stringify({ sessionId: "a/b" }));
	assert.equal(readPendingLaunch(), null);
});

test("没带 deviceId 的记录也算合法", () => {
	localStorage.setItem(PENDING_LAUNCH_KEY, JSON.stringify({ sessionId: "s1", deviceId: "" }));
	assert.deepEqual(readPendingLaunch(), { sessionId: "s1", deviceId: undefined });
});

test("通知来自另一台设备时要给出要切过去的那一台", () => {
	const devices = [
		{ id: "d1", name: "A", baseUrl: "http://a:1", token: "t", addedAt: 1, lastSeenAt: 1 },
		{ id: "d2", name: "B", baseUrl: "http://b:1", token: "t", addedAt: 1, lastSeenAt: 1 },
	];
	assert.deepEqual(pickLaunchDevice({ sessionId: "s", deviceId: "d1" }, devices, "d1"), { device: null, needsSwitch: false });
	assert.equal(pickLaunchDevice({ sessionId: "s", deviceId: "d2" }, devices, "d1").needsSwitch, true);
	assert.equal(pickLaunchDevice({ sessionId: "s", deviceId: "d2" }, devices, "d1").device?.baseUrl, "http://b:1");
	// 设备被删了：不切，就当当前设备的会话试试
	assert.deepEqual(pickLaunchDevice({ sessionId: "s", deviceId: "gone" }, devices, "d1"), { device: null, needsSwitch: false });
	// 老通知没有 deviceId：不动设备
	assert.deepEqual(pickLaunchDevice({ sessionId: "s" }, devices, "d1"), { device: null, needsSwitch: false });
});
