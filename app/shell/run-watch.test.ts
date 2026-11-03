/**
 * 看护清单（多设备）的纯逻辑。
 *
 * 原生服务现在按「设备清单」起连接：每台一条 SSE，通知里带设备 id。JS 这头
 * 要做的是把「当前有会话在跑的设备」比对成一串签名，只有集合变了才重新下发，
 * 否则每 15 秒一次轮询都会把 SSE 掐了重连（服务端每次都要重推 57KB state）。
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { RUNNING_STATUSES, pickRuntimes, targetSignature } from "./run-watch.ts";
import type { DeviceRecord } from "./device.ts";

const device = (id: string): DeviceRecord => ({
	id,
	name: id,
	baseUrl: `http://${id}:8765`,
	token: "t",
	addedAt: 1,
	lastSeenAt: 1,
});

test("设备清单签名与顺序无关", () => {
	assert.equal(targetSignature([device("b"), device("a")]), "a,b");
	assert.equal(targetSignature([device("a"), device("b")]), "a,b");
	assert.equal(targetSignature([]), "");
});

test("签名只认 id：同 id 换地址也算「没变」（地址变了由原生 reconcile 处理）", () => {
	const moved = { ...device("a"), baseUrl: "http://other:8765" };
	assert.equal(targetSignature([moved]), targetSignature([device("a")]));
});

test("running / starting 都算在跑", () => {
	assert.equal(RUNNING_STATUSES.has("running"), true);
	assert.equal(RUNNING_STATUSES.has("starting"), true);
	assert.equal(RUNNING_STATUSES.has("idle"), false);
	assert.equal(RUNNING_STATUSES.has("error"), false);
});

test("pickRuntimes 认数组与 {runtimes} 两种形状", () => {
	assert.deepEqual(pickRuntimes([{ sessionId: "s1", status: "running" }]), [{ sessionId: "s1", status: "running" }]);
	assert.deepEqual(pickRuntimes({ runtimes: [{ sessionId: "s2", status: "idle" }] }), [
		{ sessionId: "s2", status: "idle" },
	]);
	assert.deepEqual(pickRuntimes({ runtimes: [{ sessionId: 1, status: "running" }, null] }), []);
	assert.deepEqual(pickRuntimes(null), []);
});
