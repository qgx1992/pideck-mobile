# PiDeck 原生客户端（Kotlin / Compose）

这是「全原生」方案（`docs/native-android-evaluation.md` 里的 C 方案）的实现工程，
与仓库根的 Capacitor 壳（`app/`）并行存在，`applicationId` 是 `top.caoayu.pideck.native`，
所以两个包可以同时装在一台手机上做对照。

## 为什么单独一个 Gradle 工程

- 不干扰 `app/android` 的 Capacitor 构建链（那边是 Java + AGP 8.11.1 + `variables.gradle`）；
- 原生侧自己锁版本（Kotlin 2.0.21 / AGP 8.7.3 / Compose BOM 2024.12.01 / Hilt 2.52），互不牵连；
- 迁移期结束后整个目录就是交付物，不会有「壳里残留原生代码」的中间态。

## 构建

```bash
cd native
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 纯 JVM 单测
```

需要 JDK 21 + Android SDK（`local.properties` 里写 `sdk.dir`，该文件不入库）。
仓库已 gitignore `native/**/build/`、`native/.gradle/`、`native/local.properties`。

## 已实现（第一梯队）

| 模块 | 文件 | 说明 |
|---|---|---|
| 协议模型 | `data/Models.kt` | 对齐上游 `webTypes.ts`；全部 `ignoreUnknownKeys` |
| HTTP + SSE | `data/PideckApi.kt` | OkHttp 4.12 + `okhttp-sse`，`/api/state`（带 ETag 304）、`messages/page`、`/api/sessions/:id/stream`、`/api/chat` |
| 配对解析 | `data/PairingLink.kt` | 认 http 链接 / `#/?token=` / `pideck://pair` / 裸 `host:port` |
| 设备与令牌 | `data/DeviceRepository.kt` | 设备清单进 DataStore，**令牌进 EncryptedSharedPreferences**（Keystore） |
| 设备页 | `ui/devices/*` | 添加=先真连一次 `/api/state` 再落盘；删除；空态引导 |
| 会话列表 | `ui/sessions/*` | 项目分组、运行中绿点、相对时间、下拉刷新入口 |
| 主题 | `ui/theme/Theme.kt` | 与桌面端同一套基色的深/浅色 |

## 待办（按梯队）

1. 聊天页：时间线（Markdown / 代码高亮 / 思考块 / 工具卡 / 虚拟滚动）+ Composer + 回合统计
2. 扫码配对（CameraX + zxing）
3. 工作区抽屉（文件 / git / todo / 子代理）、设置 / 技能扩展 / 用量
4. 前台服务通知（`dataSync` 前台服务 + SSE，替代壳里的 `RunWatchService`）
5. Ktor mock harness（`/api/state`、`/api/sessions/:id/stream` 假桌面端）与 UI 回归

## 与 WebView 壳的关系

迁移期两边都会存在。**不要**把桌面端 UI 再往这里搬第二遍的协议逻辑——原生侧只实现 API 客户端，
字段名与上游一致；上游新增字段时 `ignoreUnknownKeys` 保证不炸，需要展示时再补模型。

注意：`native` 不能作为 Kotlin 包名的一段语义关键字风险，所以源码包名是 `dev.pideck.nativeapp`。

## 出包

```bash
# 开发用弱包（debug 签名，可 everywhere 装）
./gradlew :app:assembleDebug
# apk 在 app/build/outputs/apk/debug/app-debug.apk

# 正式包（复用 ../app/android/keystore.properties 里那份发布签名）
./gradlew :app:assembleRelease
# apk 在 app/build/outputs/apk/release/app-release.apk
```

签名为什么要复用壳那份：`app/android/keystore.properties` + `pideck-release.keystore` 是同一个开发者
的长久身份，丢一次就有无数老版本无法覆盖升级；原生工程再生成一份只是平白多两个必须备份的文件。
两个应用的 `applicationId` 不同，共用一张证书不会互相顶掉。

**签名换了就不能覆盖安装**：0.1.24 及更早是 debug 签名，升到 0.1.25 的 release 包必须先卸载旧版本
（Android 会报「签名不一致」）。版本号无关，证书不同就是不行。

校验：

```bash
"C:/dsh-build/sdk/build-tools/35.0.0/aapt2.exe" dump badging app/build/outputs/apk/release/app-release.apk | head -2
"C:/dsh-build/sdk/build-tools/35.0.0/apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
```
