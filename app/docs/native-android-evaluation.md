# 全原生 Android 重写的技术评估

> 评估对象：Kotlin 2.0.21 + Jetpack Compose（BOM 2024.12.01）+ Hilt 2.52 + KSP，AGP 8.7.3，
> minSdk 26 / compileSdk·targetSdk 35，OkHttp 4.12 + kotlinx.serialization 1.7.3，
> DataStore + WorkManager，测试侧 Ktor 3.0.3 mock harness，二维码配对用 zxing，
> 不用图片库（自己 LruCache + BitmapFactory）。
>
> 对照现状：Capacitor 7 壳（`app/`，3677 行 TS + 1673 行 Java）+ vendored 上游 Web 客户端
> （`app/vendor/pideck`，1180 个文件 / 117,118 行，上游 0.9.9 @ `ba8aa6a`）。
>
> 文中「人日」均为**估算**，不是排期承诺。

## 一句话结论

**这套栈本身是教科书级的现代原生组合，逐项都没有选错**；但「更像原生 App」不是这个项目当前
的瓶颈——瓶颈是**协议面与界面会从「一份代码」变成「两份代码」**。如果只是想要更好的手感与
系统集成，不需要全原生；如果确定手机端是产品主线、且愿意接受每次上游更新都要对表，那可以走，
路径应该是「原生骨架 + 聊天页暂留 WebView」而不是一次性重写。

## 逐项体检

| 选择 | 判断 | 要注意的具体点 |
|---|---|---|
| Kotlin 2.0.21 + AGP 8.7.3 | ✅ 稳 | 2.0 起 Compose 编译器随 KGP 发布，不必再单独对齐 `composeOptions.kotlinCompilerExtensionVersion`；AGP 8.7.3 是支持 compileSdk 35 的版本线，别再往上升 AGP 除非一起升 Gradle。 |
| Compose BOM 2024.12.01 | ✅ | 对应 Compose 1.7.x 线。BOM 只统一 Compose 自身，`androidx.activity` / `lifecycle` / `navigation-compose` 的版本要自己锁，否则会出现「BOM 里的 compose 和手写的 activity 版本打架」。 |
| Hilt 2.52 + KSP | ✅ 但最容易翻车 | KSP 版本必须与 Kotlin 严格对应（2.0.21 配 `2.0.21-1.0.x`）；Hilt 2.52 起 KSP 是一等公民，`kapt` 与 `ksp` 混用时 `javapoet` 冲突是经典报错。**别保留任何 `kapt`**。 |
| minSdk 26 | ✅ 比现在宽 | 现在壳是 **24**（`android/variables.gradle:3`）。提到 26 意味着丢掉 Android 6/7 老机，但换来 `adaptive icon`、`ShortcutManager`、`Java 8+` 全量 API。对个人项目没损失。 |
| compile/targetSdk 35 | ✅ 但强制 edge-to-edge | API 35 起系统**强制** edge-to-edge，这是 0.3.0–0.3.11 折腾了十一版的根源。原生下是 `enableEdgeToEdge()` + `Modifier.safeDrawingPadding()` 两行，`WindowInsets` 一条链解决——**这是全原生最实在的收益**，`app/shell/safe-area.ts`（108 行）+ `safe-area-css.ts`（119 行）可以直接删。 |
| OkHttp 4.12 | ✅ | SSE **别手写行解析**，用官方 `com.squareup.okhttp3:okhttp-sse`（`EventSources.createFactory`，自带重连与 `Last-Event-ID`）。现在手写了两份（`shell/native-sse.ts` 286 行 + `SsePlugin.java` 410 行），是全项目最容易出隐性 bug 的地方。 |
| kotlinx.serialization 1.7.3 | ✅ | 配 kotlinx.serialization 1.7.3 需要 Kotlin ≥ 2.0。上游 JSON 里有大量可选字段（`meta`、`runtimes`），`ignoreUnknownKeys = true` + `explicitNulls = false` 一定要开，否则上游加字段就崩。 |
| DataStore | ⚠️ 一半 | 设备清单（`pideck.devices`）、上次会话这些用 DataStore 很合适；**但 web 令牌不能明文放 DataStore**——现在 JS 侧就是明文 `localStorage`，那已经是既成事实的弱项，重写时别继承。令牌走 `EncryptedSharedPreferences`，或自己用 Keystore 包一层。 |
| WorkManager | ❌ 用错地方 | 现在的「回答完推通知」在 `RunWatchService.java`（627 行）里是**前台服务**持有的长连 SSE。WorkManager 的最短周期是 15 分钟、且不能维持 socket，用它做这件事只会变成「延迟十几分钟才通知」或者「被 Doze 掐掉」。后台通知的正确形态是 `ForegroundService`（`dataSync` 类型）+ `startForeground` + 常驻通知，WorkManager 只适合「一次性补拉、失败重试」。 |
| Ktor 3.0.3 mock harness | ✅ 很对 | 这是这套方案里最值钱的一项：真起一个假桌面端，把 `/api/state`、`/api/sessions/:id/stream`、`/api/sessions/:id/messages/page` 全 mock 出来，SSE 分帧、慢速 delta、断流重连都能写成真回归。现在只有 jsdom smoke 连真桌面（`app/scripts/smoke-ui.mjs`），覆盖不到网络层。 |
| zxing 配对 | ✅ 够用 | 配对是短 URL/二维码字符串，zxing 完全够。**但壳里已经装了 Capacitor ML Kit 扫码（`@capacitor-mlkit/barcode-scanning`）**，它还带着 `libbarhopper_v3.so`（每 ABI 3–6 MB，release 已裁 x86）。全原生时换 zxing 反而能把这几个 .so 从包里拿掉。摄像头预览用 CameraX，别用 `zxing-android-embedded`（它拖 Activity + 老式 View 体系）。 |
| 不用图片库 | ❌ 建议改 | 这是唯一我明确建议改的一项。`LruCache + BitmapFactory` 能跑，但要自己处理：`inSampleSize` 按目标尺寸算（不是按屏幕）、`inJustDecodeBounds` 两趟解码、`RGB_565`、OOM 时降级、旋转/配置变化、Compose 侧 `ImageBitmap` 转换与 `<AsyncImage>` 的等价物、列表回收。附件/截图动辄 4000×3000，ARGB_8888 解码峰值约 48 MB，滑几次就 OOM。**Coil 3** 用 OkHttp 做引擎（正好复用你的 client）、Compose 原生、有内存+磁盘两级缓存，几十行替代这套自研。自研的唯一好处是少一个依赖，代价是这块 bug 会长期存在。 |

## 真正的成本：协议面从一份变两份

现在手机端的界面**就是**桌面端那份代码（`app/vendor/pideck/src/renderer/src`，1180 文件 / 117,118 行），
壳只负责「浏览器没有的东西」：

- `app/shell/`：boot 注入、fetch/EventSource 桥、原生 SSE、`/api` 令牌、安全区、系统栏、返回键、
  后台看护、配对、动效、回合统计 —— 共 3677 行。
- `app/android/.../*.java`：8 个文件 1673 行（`RunWatchService` 627、`SsePlugin` 410、`NavigationBarPlugin` 204…）。

上游这一份代码带来的功能面，用数字衡量：**21 个 web 入口组件**（`WebTimeline` / `WebComposer` /
`WebWorkspaceDrawer` / `WebSidebar` / `WebSearchDialog` / `WebDshToolsPanel` / `WebSkillsExtensionsDialog` /
`WebFilePreview` / `WebBranchBar` / `WebModelSheet` / `WebThinkingSelector` / `WebPromptPicker` /
`WebSecurityMenu` / `WebSessionStrips` / `WebBackendSelector` / `WebBottomSheet` / `WebAssistantText` …），
跨 **12 个 API 前缀、29 处调用**（`/api/state`、`/api/sessions/*`、`/api/projects/*`、`/api/files`、
`/api/file-content`、`/api/git/{status,log,diff}`、`/api/prompts`、`/api/usage`、`/api/events`、`/api/dsh/plugins`）。

全原生 = 这 21 个组件、29 处调用、以及它们背后所有交互（虚拟滚动、Markdown+KaTeX+Mermaid、diff 视图、
分支切换、文件树、命令面板、附件压缩、排队队列、计划模式）**在 Kotlin 里再造一遍**，并且**每次上游加字段
继续对表**。

这个成本项目里已经付过两次学费：

1. 0.2.1–0.2.13 把聊天/侧栏/工作区搬进 Flutter（`flutter_module` 14.5k 行 Dart）→ 0.3.0 整体删除。
2. 0.2.19–0.3.11 走「桌面网页版 + 本地回源服务器」→ 0.3.12 整体删除。

两次的结论都是同一句：**同一套界面维护两遍，比修 WebView 的边界问题更贵。**

## 三种边界

| 方案 | 一次性成本（估算） | 长期成本 | 收益 |
|---|---|---|---|
| **A. 现状（纯 WebView）** | 0 | 上游更新自动继承；每次系统行为变化（如 API 35 edge-to-edge）要补一些注入层 | 功能面永远跟桌面端一致 |
| **B. 原生骨架 + 聊天页 WebView** | 10–18 人日 | 中等：骨架（配对/设备/列表/导航/通知）是原生的，聊天仍继承 | 启动速度、返回键、状态栏、扫码、通知全是原生的；聊天界面不用重做 |
| **C. 全原生** | 70–110 人日（1 人 3–5 个月） | 高：每次上游更新要对表；协议层两份实现 | 手感最原生，能做真正的原生交互（长按菜单、系统分享、拖拽） |

### C 的工作量分解（估算）

| 模块 | 人日 | 说明 |
|---|---|---|
| 工程骨架（Hilt、导航、设计系统、CI） | 5–8 | |
| 配对 / 设备管理 / 令牌（扫码、Keystore、多设备） | 3–5 | 现在 `launcher.ts` 466 行 + `device.ts` 102 行 |
| 协议层（OkHttp + SSE + 序列化模型 + 鉴权注入 + 重连） | 6–10 | 上游是 117k 行 TS 的类型面，模型要按需挑 |
| 会话列表 / 搜索 / 分页 / 项目分组 | 5–8 | |
| 消息时间线（Markdown、代码高亮、KaTeX、Mermaid、工具卡、思考块、分支、虚拟滚动、滚动锚定） | **20–30** | 全项目最贵的一块；`WebTimeline.tsx` 背后是 `@tanstack/react-virtual` + `streamdown` + `@pierre/diffs` |
| Composer（附件压缩与上传、排队、模型/思考选择、计划模式、斜杠命令、回合统计） | 12–18 | |
| 工作区抽屉（文件树、预览、git status/log/diff、todo、子代理） | 12–18 | |
| 设置 / 技能与扩展 / 用量 / 后端切换 | 6–10 | |
| 通知与后台（前台服务、Doze、点按跳转、多设备聚合） | 4–6 | 现在 627 行 Java |
| 测试（Ktor mock + UI 测试 + 真机走查） | 10–15 | |
| **合计** | **83–128** | 取中位 ≈ 105 人日 |

## 什么时候值得走 C

同时满足三条才建议：

1. 手机端是**产品主线**（不是「桌面的补充入口」）；
2. 上游 PiDeck 的界面更新频率**下降**（否则一直对表）；
3. 有明确的**只有原生才能做**的需求：相机扫描、系统分享、剪贴板富文本、后台重活、桌面小组件、手表/车机。

现在这三条一条都不满足：手机端是桌面的补充，上游还在活跃加功能（0.4.x 期间加了多设备看护、
回合统计、todo/子代理面板等），也没有必须原生的能力。

## 如果走 B：落地顺序与验收

1. **骨架先行**：Kotlin/Compose 工程 + Hilt + 扫码配对 + 设备列表 + 令牌安全存储（Keystore）。
   验收：能配对、能存多设备、删设备、重命名，且令牌不在明文 XML 里。
2. **协议层复用现有验证**：先用 Ktor mock 把 `/api/state` + `/api/sessions/:id/stream` 跑起来，
   再对真桌面（8765）双跑。验收：SSE 断流 3 秒内重连、慢速 delta 不乱序、`Last-Event-ID` 生效。
3. **前台服务重写通知**（把 `RunWatchService` 的逻辑搬到 Kotlin，仍用 `dataSync` 前台服务）。
   验收：锁屏、切后台 10 分钟以上仍能在答完时推一条，点按直达会话。
4. **聊天页先嵌 WebView**（`WebView` + 现有的 `boot.js` 注入层原样复用，URL 换成 `file:///android_asset` 或
   本地回源）。验收：与现在 A 方案功能面一致，只是外面套了原生导航与原生输入框都不换。
5. 之后按「最疼的页面」逐个原生替换（建议顺序：会话列表 → Composer → 时间线），每替换一页就删掉对应的
   WebView 路径，避免两份并存太久——这正是前两次失败的形态。

## 一句话建议

把栈留着，**先不重写**。如果确实想动，从 B 的第 1、3 步开工（配对骨架 + 前台服务），这两块是纯收益、
不会造成两份 UI 并存；Compose 的时间线放到最后，因为它的成本是其他所有模块之和。
