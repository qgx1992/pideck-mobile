#!/usr/bin/env node
/**
 * 一键出 APK：build web → cap sync → gradlew assembleDebug / assembleRelease
 *
 *   npm run apk            # debug 包（带 debug 签名，可直接 adb install）
 *   npm run apk:release    # release 包（读取 android/keystore.properties 签名）
 *
 * 为什么要这个脚本：gradlew.bat 在启动前就要求 JAVA_HOME（org.gradle.java.home 来不及生效），
 * 所以这里自己找 JDK（环境变量 → winget 装到 Program Files 的 Temurin）再把它注入子进程环境。
 *
 * 用法：npm run apk
 */

import { execFileSync } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import path from "node:path";
import process from "node:process";

const APP_DIR = path.resolve(import.meta.dirname, "..");
const isWindows = process.platform === "win32";
const javaBin = isWindows ? "java.exe" : "java";

function findJdk() {
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
      // macOS 的 JDK 在 Contents/Home 下
      candidates.push(path.join(root, name, "Contents", "Home"));
      candidates.push(path.join(root, name, "jdk-21"));
    }
  }
  return candidates.find((dir) => existsSync(path.join(dir, "bin", javaBin))) ?? null;
}

function run(command, args, options = {}) {
  console.log(`\n$ ${command} ${args.join(" ")}`);
  // Windows 上 npm/npx/gradlew 都是 .cmd/.bat，必须走 shell
  execFileSync(command, args, { cwd: APP_DIR, stdio: "inherit", shell: isWindows, ...options });
}

const release = process.argv.includes("--release");

const jdk = findJdk();
if (!jdk) {
  console.error("✗ 找不到 JDK：请先装 JDK 21（winget install EclipseAdoptium.Temurin.21.JDK）或设置 JAVA_HOME");
  process.exit(1);
}
console.log(`JDK: ${jdk}`);

const gradle = path.join(APP_DIR, "android", isWindows ? "gradlew.bat" : "./gradlew");
if (!existsSync(path.join(APP_DIR, "android"))) {
  console.error("✗ 缺少 android 平台：先跑 npx cap add android");
  process.exit(1);
}

run("npm", ["run", "build"]);
// 必须 sync 而不是 copy：新装的插件要写进 capacitor.settings.gradle，否则 Gradle 根本不编译它
// （踩过：加了 @capacitor/app 但只 copy，backButton 事件永远不到 JS）
run("npx", ["cap", "sync", "android"]);

const env = { ...process.env, JAVA_HOME: jdk };

if (release) {
  const props = path.join(APP_DIR, "android", "keystore.properties");
  if (!existsSync(props)) {
    console.error("✗ 缺少 android/keystore.properties：先跑 npm run gen:keystore");
    process.exit(1);
  }
  console.log("签名来源: android/keystore.properties（keystore 与密码不入库）");
}

const task = release ? "assembleRelease" : "assembleDebug";
// JDK 走命令行传入，不写进 gradle.properties（那个文件要跨平台）
run(gradle, [task, "--no-daemon", "--console=plain", `-Dorg.gradle.java.home=${jdk}`], {
  cwd: path.join(APP_DIR, "android"),
  env,
  shell: isWindows,
});

const apkFolder = release ? "release" : "debug";
const apkName = release ? "app-release.apk" : "app-debug.apk";
const apk = path.join(APP_DIR, "android", "app", "build", "outputs", "apk", apkFolder, apkName);
console.log(`APK: ${apk}`);
