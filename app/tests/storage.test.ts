/** 连接历史：多条保存、去重、最近使用排序、重命名、删除。 */

import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/" });
const g = globalThis as Record<string, unknown>;
g.window = dom.window;
g.localStorage = dom.window.localStorage;

const {
  savePairing,
  loadPairing,
  listPairings,
  rememberPairing,
  forgetPairing,
  renamePairing,
  pairingLabel,
  clearPairing,
} = await import("../src/platform/storage.ts");

const A = { baseUrl: "http://192.168.1.10:8765", token: "token-aaaaaaaa", savedAt: 1_000 };
const B = { baseUrl: "http://100.64.0.2:8765", token: "token-bbbbbbbb", savedAt: 2_000 };

function reset(): void {
  dom.window.localStorage.clear();
}

test("保存后能读回当前连接，并进入历史", () => {
  reset();
  savePairing(A);
  assert.equal(loadPairing()?.baseUrl, A.baseUrl);
  assert.deepEqual(
    listPairings().map((p) => p.baseUrl),
    [A.baseUrl],
  );
});

test("可以保存多条，按最近使用倒序", () => {
  reset();
  rememberPairing(A);
  rememberPairing(B);
  assert.deepEqual(
    listPairings().map((p) => p.baseUrl),
    [B.baseUrl, A.baseUrl],
  );

  // 再摸一下 A，A 应排到前面
  rememberPairing(A);
  assert.deepEqual(
    listPairings().map((p) => p.baseUrl),
    [A.baseUrl, B.baseUrl],
  );
});

test("同一 baseUrl 不重复入列，只更新 token", () => {
  reset();
  rememberPairing(A);
  rememberPairing({ ...A, token: "token-updated-1234" });
  const list = listPairings();
  assert.equal(list.length, 1);
  assert.equal(list[0]?.token, "token-updated-1234");
});

test("clearPairing 只清当前，不清历史", () => {
  reset();
  savePairing(A);
  savePairing(B);
  clearPairing();
  assert.equal(loadPairing(), null, "当前连接已清空");
  assert.equal(listPairings().length, 2, "历史保留，方便一键切回");
});

test("重命名后展示名变化，清空则回退到 host", () => {
  reset();
  rememberPairing(A);
  renamePairing(A.baseUrl, "  家里台式机  ");
  assert.equal(pairingLabel(listPairings()[0]!), "家里台式机");

  renamePairing(A.baseUrl, "   ");
  assert.equal(pairingLabel(listPairings()[0]!), "192.168.1.10:8765");
});

test("删除指定历史", () => {
  reset();
  rememberPairing(A);
  rememberPairing(B);
  forgetPairing(A.baseUrl);
  assert.deepEqual(
    listPairings().map((p) => p.baseUrl),
    [B.baseUrl],
  );
});

test("损坏的历史数据不会崩，返回空数组", () => {
  reset();
  dom.window.localStorage.setItem(["pideck", "pairing", "history", "v1"].join("-"), "{not json");
  assert.deepEqual(listPairings(), []);
});

test("历史里字段不完整的条目被过滤", () => {
  reset();
  dom.window.localStorage.setItem(
    ["pideck", "pairing", "history", "v1"].join("-"),
    JSON.stringify([{ baseUrl: "http://x" }, { baseUrl: "http://y", token: "t" }, null]),
  );
  const list = listPairings();
  assert.equal(list.length, 1);
  assert.equal(list[0]?.baseUrl, "http://y");
});

test("pairingLabel 对非法 URL 也能兜底", () => {
  assert.equal(pairingLabel({ baseUrl: "not a url", token: "t", savedAt: 0 }), "not a url");
});
