/**
 * 「文件下载到手机」的纯逻辑。
 *
 * 重点不是下载本身，而是**拦住不该下的时候**：桌面端只吐文本，
 * 二进制与超 2MB 的文件给不了完整字节，存下来就是坏文件。
 */

import test from "node:test";
import assert from "node:assert/strict";

const {
  baseName,
  mimeForName,
  formatBytes,
  downloadBlockedReason,
  downloadDoneText,
  textToBase64,
} = await import("../src/ui/file-download-model.ts");

test("baseName 取最后一段，兼容两种分隔符与末尾斜杠", () => {
  assert.equal(baseName("src/ui/dom.ts"), "dom.ts");
  assert.equal(baseName("src\\ui\\dom.ts"), "dom.ts");
  assert.equal(baseName("docs/guide/"), "guide");
  assert.equal(baseName("README.md"), "README.md");
  assert.equal(baseName(""), "文件");
  assert.equal(baseName("/"), "文件");
});

test("mimeForName 按扩展名给类型，未知扩展与无扩展退回 text/plain", () => {
  assert.equal(mimeForName("a.md"), "text/markdown");
  assert.equal(mimeForName("a.JSON"), "application/json");
  assert.equal(mimeForName("a.yml"), "application/yaml");
  assert.equal(mimeForName("a.csv"), "text/csv");
  assert.equal(mimeForName("a.svg"), "image/svg+xml");
  assert.equal(mimeForName("a.ts"), "text/plain");
  assert.equal(mimeForName("Makefile"), "text/plain");
});

test("formatBytes 走 B / KB / MB 三档", () => {
  assert.equal(formatBytes(0), "0 B");
  assert.equal(formatBytes(-5), "0 B");
  assert.equal(formatBytes(512), "512 B");
  assert.equal(formatBytes(1536), "1.5 KB");
  assert.equal(formatBytes(3 * 1024 * 1024), "3.0 MB");
});

test("文本文件不拦", () => {
  assert.equal(downloadBlockedReason({ kind: "text", size: 2048 }, "a.md"), null);
  assert.equal(downloadBlockedReason({ kind: "text", size: 0 }, "empty.txt"), null);
});

test("二进制文件说明拿不到字节，不假装能下", () => {
  const reason = downloadBlockedReason({ kind: "binary", size: 4096 }, "logo.png");
  assert.ok(reason);
  assert.match(reason, /logo\.png/);
  assert.match(reason, /二进制/);
  assert.match(reason, /下载不了/);
});

test("超过 2MB 被截断时说清楚会存成半截", () => {
  const reason = downloadBlockedReason({ kind: "truncated", size: 3 * 1024 * 1024 }, "big.json");
  assert.ok(reason);
  assert.match(reason, /3\.0 MB/);
  assert.match(reason, /512 KB/);
  assert.match(reason, /半截/);
});

test("tooLarge 也给一句人话", () => {
  const reason = downloadBlockedReason({ kind: "tooLarge", size: 5 * 1024 * 1024 }, "huge.log");
  assert.ok(reason);
  assert.match(reason, /huge\.log/);
  assert.match(reason, /2 MB/);
});

test("textToBase64 中文不乱码，也不带换行", () => {
  const base64 = textToBase64("你好 PiDeck\n第二行");
  assert.equal(base64.includes("\n"), false);
  assert.equal(Buffer.from(base64, "base64").toString("utf8"), "你好 PiDeck\n第二行");
  assert.equal(textToBase64(""), "");
  // 超过一个分块（32KB）也要完整
  const long = "内容".repeat(20_000);
  assert.equal(Buffer.from(textToBase64(long), "base64").toString("utf8"), long);
});

test("downloadDoneText 有位置时带上位置", () => {
  assert.equal(downloadDoneText("a.md", "下载/PiDeck/a.md"), "已保存 a.md 到 下载/PiDeck/a.md");
  assert.equal(downloadDoneText("a.md", ""), "已保存 a.md");
});
