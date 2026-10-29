#!/usr/bin/env node
/**
 * 把刚打出来的 APK 发布到局域网目录（apk/），并刷新校验值。
 *
 * 用法：
 *   npm run apk && npm run publish:apk                      # debug 包
 *   npm run apk:release && npm run publish:apk -- --release # 签名发布包
 *
 * 注意：apk/ 目录可能同时发布别的项目（比如 DSH Mobile），
 * 所以校验清单是「合并」而不是覆盖，并且会自动补上目录里未登记的 APK。
 */

import { copyFileSync, existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";

const APP_DIR = path.resolve(import.meta.dirname, "..");
const APP_VERSION = JSON.parse(readFileSync(path.join(APP_DIR, "package.json"), "utf8")).version;

const release = process.argv.includes("--release");
const variant = release ? "release" : "debug";
const source = path.join(APP_DIR, "android", "app", "build", "outputs", "apk", variant, `app-${variant}.apk`);
const fileName = `PiDeck-${APP_VERSION}-${variant}.apk`;
const targetDir = path.resolve(APP_DIR, "..", "apk");
const target = path.join(targetDir, fileName);

if (!existsSync(source)) {
  console.error(`✗ 找不到构建产物：${source}\n  先跑 npm run apk${release ? ":release" : ""}`);
  process.exit(1);
}

copyFileSync(source, target);

const buffer = readFileSync(target);
const sha256 = createHash("sha256").update(buffer).digest("hex");
const size = statSync(target).size;

const sumsFile = path.join(targetDir, "SHA256SUMS.txt");
const sha256Of = (file) => createHash("sha256").update(readFileSync(file)).digest("hex");

/** 读现有清单 → 去掉同名条目 → 保住其它项目的行 */
const lines = existsSync(sumsFile)
  ? readFileSync(sumsFile, "utf8")
      .split("\n")
      .map((line) => line.trim())
      // 已删除的文件不该继续留在清单里；同名条目要去重
      .filter((line) => {
        if (!line) return false;
        const name = line.split(/\s+/).pop();
        return name !== fileName && existsSync(path.join(targetDir, name));
      })
  : [];

// 目录里还有没登记的 APK（别的项目刚发布过）→ 顺手补上
const listed = new Set(lines.map((line) => line.split(/\s+/).pop()));
for (const name of readdirSync(targetDir).sort()) {
  if (!name.endsWith(".apk") || listed.has(name) || name === fileName) continue;
  lines.push(`${sha256Of(path.join(targetDir, name))}  ${name}`);
}

lines.push(`${sha256}  ${fileName}`);
writeFileSync(sumsFile, `${lines.join("\n")}\n`, "utf8");

console.log(`✓ ${target}`);
console.log(`  ${(size / 1024 / 1024).toFixed(2)} MB  sha256=${sha256}`);
console.log(`  局域网地址示例：http://<电脑IP>:8080/${fileName}`);
