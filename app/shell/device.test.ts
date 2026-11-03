import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";

/**
 * device.ts 只碰 localStorage，这里给最小实现（Node 24 自带 TS 类型擦除，
 * 直接 `node --test` 跑源码，不需要构建）。
 */
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

const storage = new MemoryStorage();
(globalThis as unknown as { localStorage: MemoryStorage }).localStorage = storage;

const { deviceIdFor, loadDevices, normalizeBaseUrl, parsePairingInput, saveDevices, upsertDevice } = await import("./device.ts");

beforeEach(() => {
	storage.clear();
});

test("normalizeBaseUrl 补协议、去尾斜杠", () => {
	assert.equal(normalizeBaseUrl("192.168.1.11:8765"), "http://192.168.1.11:8765");
	assert.equal(normalizeBaseUrl("http://192.168.1.11:8765///"), "http://192.168.1.11:8765");
	assert.equal(normalizeBaseUrl(" https://pideck.local "), "https://pideck.local");
	assert.equal(normalizeBaseUrl(""), "");
});

test("parsePairingInput 认带令牌的二维码链接", () => {
	const parsed = parsePairingInput("http://192.168.1.11:8765/?token=abc123");
	assert.deepEqual(parsed, { baseUrl: "http://192.168.1.11:8765", token: "abc123" });
});

test("parsePairingInput 认裸 host:port 与无令牌链接", () => {
	assert.deepEqual(parsePairingInput("192.168.1.11:8765"), { baseUrl: "http://192.168.1.11:8765", token: "" });
	assert.deepEqual(parsePairingInput("http://box.local:9000/"), { baseUrl: "http://box.local:9000", token: "" });
	assert.equal(parsePairingInput("   "), null);
});

test("upsertDevice 同一台机器只留一条，并保留原名字与 addedAt", () => {
	const first = upsertDevice([], { baseUrl: "http://192.168.1.11:8765/", token: "t1", name: "书房" });
	assert.equal(first.length, 1);
	assert.equal(first[0]?.name, "书房");
	assert.equal(first[0]?.token, "t1");

	const second = upsertDevice(first, { baseUrl: "http://192.168.1.11:8765", token: "" });
	assert.equal(second.length, 1);
	assert.equal(second[0]?.token, "t1", "空令牌不该把旧令牌冲掉");
	assert.equal(second[0]?.addedAt, first[0]?.addedAt);
	assert.equal(second[0]?.name, "书房");
});

test("upsertDevice 换令牌时更新", () => {
	const first = upsertDevice([], { baseUrl: "http://a:1", token: "old" });
	const next = upsertDevice(first, { baseUrl: "http://a:1", token: "new" });
	assert.equal(next[0]?.token, "new");
});

test("deviceIdFor 稳定且可用于 localStorage 键", () => {
	assert.equal(deviceIdFor("http://192.168.1.11:8765/"), "192.168.1.11:8765");
	assert.equal(deviceIdFor("http://192.168.1.11:8765"), deviceIdFor("http://192.168.1.11:8765/"));
});

test("loadDevices 容忍脏数据", () => {
	storage.setItem("pideck.devices", "not json");
	assert.deepEqual(loadDevices(), []);
	storage.setItem("pideck.devices", JSON.stringify([{ baseUrl: "http://a:1" }, { nope: 1 }, null]));
	const devices = loadDevices();
	assert.equal(devices.length, 1);
	assert.equal(devices[0]?.baseUrl, "http://a:1");
});

test("saveDevices / loadDevices 往返", () => {
	saveDevices([{ id: "a:1", name: "a", baseUrl: "http://a:1", token: "t", addedAt: 1 }]);
	assert.equal(loadDevices()[0]?.token, "t");
});
