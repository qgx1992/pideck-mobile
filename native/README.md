# PiDeck 原生客户端（Kotlin / Compose）

**这个目录就是整个仓库的交付物**（0.5.0 起）。`docs/native-android-evaluation.md` 里记着当初
为什么从 WebView 壳迁到全原生；0.4.x 的 Capacitor 壳（`app/`）与更早的 TypeScript 协议核心（`src/`、
`tests/`）已在 0.5.41 从 master 删除，完整内容留在分支 `archive/webview-shell`。

版本 0.5.41 / `versionCode 541`，包名 `top.caoayu.pideck`，Kotlin 2.0 + Jetpack Compose。

## 工程结构

- 自己一套 Gradle 工程，版本自己锁（Kotlin 2.0.21 / AGP 8.7.3 / Compose BOM 2024.12.01 / Hilt 2.52）；
- 分层：`data/`（模型 + HTTP/SSE + 解析）、`ui/`（chat / home / sessions / devices / settings / pairing）、
  `watch/`（前台服务 + 通知）、`di/`（Hilt）；
- 测试全在 `app/src/test/`，300 条纯 JVM 单测（解析、分帧、分组、行索引、统计口径），无 Compose 测试。

## 构建

```bash
cd native
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 纯 JVM 单测
```

需要 JDK 21 + Android SDK（`local.properties` 里写 `sdk.dir`，该文件不入库）。
仓库已 gitignore `native/**/build/`、`native/.gradle/`、`native/local.properties`。

## 已经能做什么

完整能力表见仓库根 `README.md` 的「它现在能做什么」，这里只列模块入口：

| 模块 | 文件 | 说明 |
|---|---|---|
| 协议模型 | `app/src/main/java/dev/pideck/nativeapp/data/Models.kt` | 对齐桌面端 `webTypes.ts`；全部 `ignoreUnknownKeys` |
| HTTP + SSE | `app/src/main/java/dev/pideck/nativeapp/data/PideckApi.kt` | OkHttp 4.12 + `okhttp-sse`：`/api/state`（ETag 304）、`messages/page`、`/api/chat`、`/api/sessions/:id/stream` |
| 配对解析 | `app/src/main/java/dev/pideck/nativeapp/data/PairingLink.kt` | http 链接 / `#/?token=` / `pideck://pair` / 裸 `host:port` |
| 设备与令牌 | `app/src/main/java/dev/pideck/nativeapp/data/DeviceRepository.kt` | 设备清单进 DataStore，令牌进 EncryptedSharedPreferences（Keystore，带 AEAD 失效自愈） |
| 聊天页 | `app/src/main/java/dev/pideck/nativeapp/ui/chat/` | 时间线（Markdown / GFM 表格 / 代码高亮 / 思考块 / 过程组 / 工具卡 / 图片）、Composer、回合统计行、历史跳转 |
| 侧栏 | `app/src/main/java/dev/pideck/nativeapp/ui/chat/SessionDrawer.kt` | 三枚可拖动的分段（活动 / 聊天 / 项目） |
| 设置 | `app/src/main/java/dev/pideck/nativeapp/ui/settings/` | 技能与扩展开关、诊断（崩溃栈）、外观、通知 |
| 后台 | `app/src/main/java/dev/pideck/nativeapp/watch/RunWatchService.kt` | 多设备 `dataSync` 前台服务 + 跑完通知（点通知直达会话） |

## 一些约定

**不要**在客户端里造第二套协议逻辑：字段名与桌面端一致，上游新增字段靠 `ignoreUnknownKeys` 兜住，
需要展示时再补模型。桌面端升级后对账方法：把 `resources/app.asar` 用 `npx @electron/asar extract` 解开，
比路由字符串集合、`/api/state` 与流帧的 `data-*` 集合（基线版本记在 `data/HostCompat.kt` 的 `BASELINE`）。

`native` 不适合当包名的一段，所以源码包名是 `dev.pideck.nativeapp`（与 `applicationId` 无关）。

## 运行时动作与输入增强（0.5.15 起）

| 位置 | 做什么 | 走哪个接口 |
|---|---|---|
| 顶栏溢出菜单 | 导出会话 HTML，存到「下载/PiDeck」（Android 10+ 走 MediaStore，不需要存储权限） | `GET /api/sessions/:id/export-html` |
| 顶栏溢出菜单 | **权限位按后端分发**：pi 会话显示「安全等级」（关闭 / 标准 / 严格 + 自定义等级，可切「跟随全局默认」）；dsh 会话显示「权限预设」：只读 / 工作区可写 / 完全访问 | pi：`POST /api/sessions/:id/security-level`；dsh：`POST runtime/permission` + 写回会话记录 |
| 顶栏溢出菜单 / 顶栏胶囊 | 上下文用量（百分比环的等价物）+ 立即压缩，可带「保留什么」的提示词 | `POST runtime/state`、`POST runtime/compact` |
| 输入框打 `@` | 递归扫工作区后补全路径（跳过 .git / node_modules / build 等，上限 90 个目录、3000 个文件），支持子序列模糊匹配；目录补尾斜杠 `@src/`，含空格写成 `@"my file.kt"` | `GET /api/files?dir=` 逐目录广度优先 |
| 输入框打 `/` | 客户端内置 `/new`、`/compact [提示词]`，其后是本机真实存在的全部宿主命令（扩展 / 模板 / 技能），技能按 `/skill:名称` 插入 | `POST runtime/commands`（`/new`、`/compact` 由客户端拦截） |

这几项都需要 `target`（`agentId + runtimeGeneration`），所以运行时没起来时菜单会说明原因而不是静默失败。

安全等级与权限预设是**两套机制**，别看成一个东西：

- `permissionPreset` 是 DSH 的后端字段（`read-only` / `workspace-write` / `danger-full-access`）。pi 那边没有这个概念，
  往 pi 会话记录里写它会让会话在激活时对不上后端预设，表现就是「调完之后这个会话再也发不出消息」。
- pi 的安全等级走 `SecurityStore`：服务端校验后端与等级后落盘并返回最新投影，客户端**不本地猜结果**；
  `levelId: null` 是清除会话级覆盖、跟随全局默认。`/api/state` 的 `security` 里没有 `levels` 时（老主机），
  面板显示「主机没返回安全等级配置」而不是摆一排假等级。
- 后端分发在 `data/RuntimeActions.kt` 的 `privilegeControlFor`：`dsh` → 权限预设；`acp` / `imagegen` / `videogen` → 不摆位；
  其余（含空值与草稿会话）→ 安全等级。开一个 pi 会话时如果记录里还残留 `permissionPreset`，`ChatViewModel`
  会静默 `PATCH {"permissionPreset": null}` 清掉，修好已经被写坏的会话。

## 包名（0.5.0 起）

| 构建类型 | applicationId | 说明 |
|---|---|---|
| release | `top.caoayu.pideck` | 顶替原 WebView 壳的身份，手机上只有一个 PiDeck；可直接覆盖 0.4.x（同证书 + versionCode 更高） |
| debug | `top.caoayu.pideck.native` | `.native` 后缀，与手机里的调试版同 id，可覆盖调试版；也能与正式包共存 |

源码 namespace 仍是 `dev.pideck.nativeapp`（`applicationId` 与它无关，改名只动 `app/build.gradle.kts`）。
版本号从 0.5.0 起按 `major*1000 + minor*100 + patch`（0.5.41 → 541、0.5.9 → 509 < 0.5.10 → 510），
必须始终高于壳最后发过的 412，否则 Android 会以「版本回退」拒绝覆盖安装。规则见仓库根 `AGENTS.md`。

## 出包

```bash
# 开发用弱包（debug 签名，可 everywhere 装）
./gradlew :app:assembleDebug
# apk 在 app/build/outputs/apk/debug/app-debug.apk

# 正式包（读仓库根的 ../keys/keystore.properties 与 pideck-release.keystore）
./gradlew :app:assembleRelease
# apk 在 app/build/outputs/apk/release/app-release.apk
```

签名材料放在**仓库根的 `keys/`**（`keystore.properties` + `pideck-release.keystore`，都不入库）：
它是这个应用的长久身份，丢了就再也无法覆盖升级老版本，必须单独备份到密码管理器。
0.5.41 之前这两个文件借住在 `app/android/` 里，随 0.4.x 归档一起迁出来了。

**签名换了就不能覆盖安装**：0.1.24 及更早是 debug 签名，升到 0.1.25 的 release 包必须先卸载旧版本
（Android 会报「签名不一致」）。版本号无关，证书不同就是不行。

校验：

```bash
"C:/dsh-build/sdk/build-tools/35.0.0/aapt2.exe" dump badging app/build/outputs/apk/release/app-release.apk | head -2
"C:/dsh-build/sdk/build-tools/35.0.0/apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## 下载页只写当前版本

`apk/index.html` 只保留当前版本这一版说明（以及大小 / versionCode / sha256），
**不要**把往期版本一条条贴上去 —— 历史改动看 git 提交。发布时替换当前那段，不追加。

## 桌面端接口的坑（踩过的）

| 接口 | 参数 | 说明 |
|---|---|---|
| `GET /api/files` | `dir`（**不是 `path`**） | 写成 `path` 会被静默忽略、永远只列项目根目录；返回的 `relativePath` 是项目内相对路径，可直接回传 |
| `GET /api/file-content` | `path` + `sessionId` | 项目外/敏感文件会先拒一次，带 `confirm=1` 才读 |
| `GET /api/git/diff` | `group` ∈ merge/index/workingTree/untracked + `path` | 返回的是「改前全文 + 改后全文」，不是补丁，行级比对在客户端算 |
| `POST /api/sessions/:id/update` | `model` / `thinkingLevel` / `title` | 唯一能改会话配置的 HTTP 路径；运行中的会话下一轮才生效 |
| `POST /api/ui-response` | `sessionId` / `requestId` / `agentId` / `runtimeGeneration` / `response` | 批量提问的 `response.value` 是**字符串**里的 JSON |

待办没有写入通道（只有 GET），子任务只有 `/api/sessions/:id/subagents`（只读）。
