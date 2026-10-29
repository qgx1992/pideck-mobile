#!/usr/bin/env node
/**
 * 生成发布签名用的 keystore 与 keystore.properties。
 *
 * 为什么单独一个脚本：签名文件与密码**绝对不能入库**，也不该手抄进 build.gradle。
 * 这里生成一个随机强密码写进 `android/keystore.properties`（已被 .gitignore 排除），
 * Gradle 构建时自动读取。
 *
 * 用法：
 *   npm run gen:keystore                 # 生成随机密码
 *   PIDECK_KEYSTORE_PASSWORD=xxx npm run gen:keystore   # 用自己的密码
 *   node scripts/gen-keystore.mjs --force               # 覆盖已有的（小心！）
 *
 * 注意：脚本**不会**把密码打印到终端（避免留在日志/录屏里），
 * 生成后请立刻备份 android/pideck-release.keystore 与 android/keystore.properties。
 * 丢了这两个文件，后续版本就无法覆盖安装升级——只能换包名重新发。
 */

import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { randomBytes } from "node:crypto";

const APP_DIR = path.resolve(import.meta.dirname, "..");
const ANDROID_DIR = path.join(APP_DIR, "android");
const KEYSTORE_FILE = path.join(ANDROID_DIR, "pideck-release.keystore");
const PROPS_FILE = path.join(ANDROID_DIR, "keystore.properties");
const ALIAS = "pideck";
const VALIDITY_DAYS = "10950"; // 30 年

const force = process.argv.includes("--force");
const isWindows = process.platform === "win32";
const keytoolName = isWindows ? "keytool.exe" : "keytool";

function findJdkHome() {
  const candidates = [];
  if (process.env.JAVA_HOME) candidates.push(process.env.JAVA_HOME);
  const roots = [
    "C:/Program Files/Eclipse Adoptium",
    "C:/Program Files/Java",
    "C:/Program Files/Microsoft",
    "/Library/Java/JavaVirtualMachines",
    "/usr/lib/jvm",
  ];
  for (const root of roots) {
    if (!existsSync(root)) continue;
    for (const name of readdirSync(root)) {
      candidates.push(path.join(root, name));
      candidates.push(path.join(root, name, "Contents", "Home"));
    }
  }
  return candidates.find((dir) => existsSync(path.join(dir, "bin", keytoolName))) ?? null;
}

function randomPassword() {
  // 去掉容易混淆的字符，方便人工抄写；长度 28 足够
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  const bytes = randomBytes(28);
  let out = "";
  for (const byte of bytes) out += alphabet[byte % alphabet.length];
  return out;
}

if (!existsSync(ANDROID_DIR)) {
  console.error("✗ 缺少 android 目录：先跑 npx cap add android");
  process.exit(1);
}

if ((existsSync(KEYSTORE_FILE) || existsSync(PROPS_FILE)) && !force) {
  console.error(
    "✗ 已存在签名文件：\n" +
      `    ${KEYSTORE_FILE}\n    ${PROPS_FILE}\n` +
      "  覆盖会导致后续版本无法覆盖安装升级。确实要重来请加 --force。",
  );
  process.exit(1);
}

const jdk = findJdkHome();
if (!jdk) {
  console.error("✗ 找不到 JDK（需要 keytool）：装 JDK 21 或设置 JAVA_HOME");
  process.exit(1);
}
const keytool = path.join(jdk, "bin", keytoolName);

const password = process.env.PIDECK_KEYSTORE_PASSWORD?.trim() || randomPassword();
if (password.length < 6) {
  console.error("✗ 密码至少 6 位（Android keystore 的硬要求）");
  process.exit(1);
}

const dname = "CN=pideck-mobile, OU=Mobile Client, O=qgx1992, L=Local, ST=Local, C=CN";

try {
  execFileSync(
    keytool,
    [
      "-genkeypair",
      "-v",
      "-keystore", KEYSTORE_FILE,
      "-alias", ALIAS,
      "-keyalg", "RSA",
      "-keysize", "4096",
      "-validity", VALIDITY_DAYS,
      "-storepass", password,
      "-keypass", password,
      "-dname", dname,
    ],
    { stdio: ["ignore", "ignore", "pipe"] },
  );
} catch (error) {
  console.error("✗ keytool 生成失败：", String(error.stderr ?? error.message).slice(0, 400));
  process.exit(1);
}

writeFileSync(
  PROPS_FILE,
  [
    "# 发布签名配置（Gradle 自动读取）。此文件与 keystore 都不入库。",
    "# 丢了这两个文件 → 后续版本无法覆盖安装升级，必须立刻备份到密码管理器。",
    "storeFile=pideck-release.keystore",
    `storePassword=${password}`,
    `keyAlias=${ALIAS}`,
    `keyPassword=${password}`,
    "",
  ].join("\n"),
  "utf-8",
);

console.log("✓ 已生成发布签名：");
console.log(`    keystore : ${KEYSTORE_FILE}`);
console.log(`    props    : ${PROPS_FILE}`);
console.log(`    alias    : ${ALIAS}（RSA 4096，有效期 ${VALIDITY_DAYS} 天）`);
console.log("");
console.log("密码写在 keystore.properties 里，脚本不回显。请立刻：");
console.log("  1) 把 keystore 与 properties 一起备份到密码管理器/离线介质");
console.log("  2) 确认 git status 里没有这两个文件（.gitignore 已排除）");
console.log("  3) 随后跑 npm run apk:release 出签名包");
