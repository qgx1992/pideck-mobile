#!/usr/bin/env node
/**
 * 把刚打出来的 APK 发布到局域网目录（apk/），并刷新校验值。
 * 用法：npm run apk && npm run publish:apk
 */

import { copyFileSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";

const APP_DIR = path.resolve(import.meta.dirname, "..");
const source = path.join(APP_DIR, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const targetDir = path.resolve(APP_DIR, "..", "apk");
const target = path.join(targetDir, "PiDeck-0.1.0-debug.apk");

copyFileSync(source, target);

const buffer = readFileSync(target);
const sha256 = createHash("sha256").update(buffer).digest("hex");
const size = statSync(target).size;
writeFileSync(path.join(targetDir, "SHA256SUMS.txt"), `${sha256}  PiDeck-0.1.0-debug.apk\n`, "utf8");

console.log(`✓ ${target}`);
console.log(`  ${(size / 1024 / 1024).toFixed(2)} MB  sha256=${sha256}`);
console.log("  局域网地址示例：http://<电脑IP>:8080/PiDeck-0.1.0-debug.apk");
