import test from "node:test";
import assert from "node:assert/strict";
import { buildPairingUrl, looksLikePairingUrl, parsePairingInput } from "../src/pairing.ts";
import { PiDeckError } from "../src/api/errors.ts";

const TOKEN = "pairing-test-token-value";

test("解析桌面端扫码链接（局域网 + token）", () => {
  const payload = parsePairingInput(`http://192.168.1.20:8765/?token=${TOKEN}`);
  assert.equal(payload.baseUrl, "http://192.168.1.20:8765");
  assert.equal(payload.token, TOKEN);
});

test("解析 Tailscale HTTPS 链接", () => {
  const payload = parsePairingInput(`https://my-pc.tailnet.ts.net/?token=${TOKEN}`);
  assert.equal(payload.baseUrl, "https://my-pc.tailnet.ts.net");
});

test("省略 scheme 也能解析", () => {
  const payload = parsePairingInput(`192.168.1.20:8765/?token=${TOKEN}`);
  assert.equal(payload.baseUrl, "http://192.168.1.20:8765");
});

test("去掉多余路径与 hash", () => {
  const payload = parsePairingInput(`http://10.0.0.5:8765/?token=${TOKEN}#/chat`);
  assert.equal(payload.baseUrl, "http://10.0.0.5:8765");
});

test("token 中的特殊字符被正确解码", () => {
  const special = "abc+def/ghi";
  const payload = parsePairingInput(`http://h:8765/?token=${encodeURIComponent(special)}`);
  assert.equal(payload.token, special);
});

test("缺少 token 时报明确错误", () => {
  assert.throws(
    () => parsePairingInput("http://192.168.1.20:8765/"),
    (error: unknown) => error instanceof PiDeckError && error.message.includes("没有 token"),
  );
});

test("token 太短被拒", () => {
  assert.throws(() => parsePairingInput("http://h:8765/?token=abc"));
});

test("非 http 协议被拒", () => {
  assert.throws(() => parsePairingInput(`ftp://h/?token=${TOKEN}`));
});

test("buildPairingUrl 与 parse 互逆", () => {
  const url = buildPairingUrl("http://192.168.1.20:8765", TOKEN);
  const parsed = parsePairingInput(url);
  assert.equal(parsed.baseUrl, "http://192.168.1.20:8765");
  assert.equal(parsed.token, TOKEN);
});

test("looksLikePairingUrl 用于扫码预筛", () => {
  assert.equal(looksLikePairingUrl(`http://h:8765/?token=${TOKEN}`), true);
  assert.equal(looksLikePairingUrl("https://github.com/ayuayue/PiDeck"), false);
});
