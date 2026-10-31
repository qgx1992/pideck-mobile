import test from "node:test";
import assert from "node:assert/strict";
import { buildPairingUrl, isPrivateHostname, looksLikePairingUrl, pairingRisk, parsePairingInput } from "../src/pairing.ts";
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

test("私有网段判定：局域网 / 回环 / Tailscale 都算", () => {
  for (const host of [
    "localhost",
    "127.0.0.1",
    "10.1.2.3",
    "192.168.31.10",
    "172.20.0.5",
    "169.254.10.2",
    "100.109.155.33",
    "my-pc.local",
    "my-pc.tailnet.ts.net",
    "::1",
    "fe80::1",
  ]) {
    assert.equal(isPrivateHostname(host), true, host);
  }
  for (const host of ["8.8.8.8", "example.com", "203.0.113.9", "100.63.0.1", "100.128.0.1"]) {
    assert.equal(isPrivateHostname(host), false, host);
  }
});

test("配对风险：局域网 http 不打扰，公网才要确认", () => {
  assert.equal(pairingRisk({ baseUrl: "http://192.168.1.20:8765" }).needsConfirm, false);
  assert.equal(pairingRisk({ baseUrl: "https://my-pc.tailnet.ts.net" }).needsConfirm, false);

  const publicHttp = pairingRisk({ baseUrl: "http://8.8.8.8:8765" });
  assert.equal(publicHttp.needsConfirm, true);
  // 两条都要说清楚：地址不认识 + 明文
  assert.equal(publicHttp.messages.length, 2);
  assert.ok(publicHttp.messages.some((line) => line.includes("不在局域网范围内")));
  assert.ok(publicHttp.messages.some((line) => line.includes("明文")));

  // 公网但 https：只提醒地址，不吓唬明文
  const publicHttps = pairingRisk({ baseUrl: "https://pi.example.com" });
  assert.equal(publicHttps.needsConfirm, true);
  assert.equal(publicHttps.messages.length, 1);
});
