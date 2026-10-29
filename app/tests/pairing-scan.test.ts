/**
 * 扫码结果清洗与挑选（纯逻辑，不碰相机）。
 *
 * 这些用例都来自真实会遇到的脏数据：桌面端二维码里可能带说明文字、
 * 用户从聊天记录里复制导致整串被 URL 编码、扫码 App 加零宽字符等。
 */

import test from "node:test";
import assert from "node:assert/strict";

const { describeScanMiss, looksLikePairingShape, normalizeScannedText, pickScannedLink } = await import(
  "../src/ui/pairing-scan-model.ts"
);

const LINK = "http://192.168.1.20:8765/?token=8f3c1d9e4b";

test("去掉首尾空白与换行", () => {
  assert.equal(normalizeScannedText(`  \n ${LINK}\n\n `), LINK);
});

test("多行内容只取第一行", () => {
  assert.equal(normalizeScannedText(`${LINK}\n扫码访问 PiDeck`), LINK);
});

test("剥掉成对包裹的引号 / 反引号 / 尖括号", () => {
  for (const wrapper of ["\"", "'", "`", "<", "【", "（"]) {
    const closer = { "\"": "\"", "'": "'", "`": "`", "<": ">", "【": "】", "（": "）" }[wrapper]!;
    assert.equal(normalizeScannedText(`${wrapper}${LINK}${closer}`), LINK, `包裹符 ${wrapper}`);
  }
});

test("清掉零宽字符与 BOM", () => {
  assert.equal(normalizeScannedText(`\u200b\u200e\ufeff${LINK}\u200d`), LINK);
});

test("带 PAIRING: / URL： 前缀也能取出链接", () => {
  assert.equal(normalizeScannedText(`PAIRING: ${LINK}`), LINK);
  assert.equal(normalizeScannedText(`URL：${LINK}`), LINK);
});

test("整串被 URL 编码过会解一次", () => {
  const encoded = encodeURIComponent(LINK);
  assert.equal(normalizeScannedText(encoded), LINK);
});

test("空输入不炸", () => {
  assert.equal(normalizeScannedText(""), "");
  assert.equal(normalizeScannedText("   \n "), "");
});

test("形态预判：http(s) + 合法 token 才算配对链接", () => {
  assert.equal(looksLikePairingShape(LINK), true);
  assert.equal(looksLikePairingShape("https://my-pc.tailnet.ts.net/?token=abcdefgh1234"), true);
  // token 不在第一位也算
  assert.equal(looksLikePairingShape("http://192.168.1.20:8765/?a=1&token=8f3c1d9e4b"), true);
  // 缺 token / token 太短
  assert.equal(looksLikePairingShape("http://192.168.1.20:8765/"), false);
  assert.equal(looksLikePairingShape("http://192.168.1.20:8765/?token=abc"), false);
  // 非 http
  assert.equal(looksLikePairingShape("pideck://pair?token=8f3c1d9e4b"), false);
  assert.equal(looksLikePairingShape("随便一段话"), false);
});

test("pickScannedLink：什么都没读到时说明原因", () => {
  const pick = pickScannedLink([]);
  assert.equal(pick.link, null);
  assert.equal(pick.reason, "empty");
  assert.equal(pick.seen, 0);
  assert.match(describeScanMiss(pick), /没读到二维码内容/);

  const blank = pickScannedLink(["   ", "\n"]);
  assert.equal(blank.link, null);
  assert.equal(blank.reason, "empty");
});

test("pickScannedLink：单条配对链接直接用", () => {
  const pick = pickScannedLink([LINK]);
  assert.equal(pick.link, LINK);
  assert.equal(pick.reason, undefined);
  assert.equal(describeScanMiss(pick), "");
});

test("pickScannedLink：多个码时挑像配对链接的那条", () => {
  const pick = pickScannedLink(["https://example.com/hello", LINK, "{\"a\":1}"]);
  assert.equal(pick.link, LINK);
  assert.equal(pick.seen, 3);
});

test("pickScannedLink：读到东西但不是配对链接时把原文交出去", () => {
  const pick = pickScannedLink(["https://example.com/hello"]);
  assert.equal(pick.link, "https://example.com/hello");
  assert.equal(pick.reason, "foreign");
  assert.match(describeScanMiss(pickScannedLink([])), /取景框/);
});
