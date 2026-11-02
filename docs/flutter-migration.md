# Flutter 混合栈（add-to-app）

> 目标：把客户端逐步从「Capacitor WebView 外壳」迁到 Flutter。
> 现状：**混合栈已接通（0.1.19），设置页已迁到 Flutter（0.1.21）**。
> 原生壳里点齿轮直接进 Flutter 设置页；WebView 仍是主界面（会话列表、聊天页都还在里面），
> 浏览器 / 老包里设置页自动回退到 Web 版。

## 为什么不是「塞进 WebView」

Flutter 的渲染是 Skia/Impeller 自绘 + 自己的引擎（libflutter.so），它要求一个
`FlutterActivity`/`FlutterFragmentActivity` 作为宿主，没有「在 WebView 里跑 Flutter」这条路。
所以只有两种做法：

1. **add-to-app（当前方案）**：Flutter 编译成 Android library（`:flutter`），
   和 Capacitor 的 WebView 并存，谁先上谁后上由页面决定。
2. **整体重写**：Dart 重写全部界面，WebView 只留作过渡。

选了 1：WebView 里的 20k 行 TS 一行都不用动，Flutter 页先做一块试验田，
哪块 UI 迁过去了就把入口指过去，剩下的继续跑 WebView。

## 代码地图

| 位置 | 作用 |
|---|---|
| `flutter_module/lib/main.dart` | 入口：按 `route` 分派页面（`settings` / `chat` / `sse-probe`，其余落到占位页） |
| `flutter_module/lib/host_channel.dart` | `pideck/flutter_host` 通道的 Dart 封装：`getArgs` / `getSettings` / `getChat` / `settingsAction` / `chatAction` / `close`，以及订阅 `setSettings` / `setChat` / `showNotice` |
| `flutter_module/lib/settings/` | 设置页：`settings_models.dart`（JSON → 模型，有单测）+ `settings_page.dart`（纯 UI） |
| `flutter_module/lib/chat/` | 聊天页：`chat_models.dart`（`ChatSnapshot` 解析，有单测）+ `markdown.dart`（手写极简 markdown，有单测）+ `markdown_view.dart`（块渲染）+ `chat_page.dart`（列表 / 贴底 / 提问卡 / 输入栏） |
| `flutter_module/lib/probe/` | 流式探针（0.1.24，调试用，见后文） |
| `app/src/platform/flutter-chat.ts` | 聊天桥：`historyItemsOf` / `buildChatSnapshot` / `FlutterChatMirror`（40ms 尾沿合并，有单测） |
| `flutter_module/.android/` | Flutter 生成的 Android 宿主工程。**必须提交**（`include_flutter.groovy` 在里面），只忽略 `local.properties` 与 `build/` |
| `app/android/settings.gradle` | `evaluate(include_flutter.groovy)`，把模块暴露成 Gradle 项目 `:flutter` |
| `app/android/app/build.gradle` | `implementation project(':flutter')` |
| `dev/pideck/mobile/FlutterHostActivity.java` | 承载 Flutter 页的 Activity（`FlutterFragmentActivity`），提供 `MethodChannel("pideck/flutter_host")`：`getArgs` / `getSettings` / `getChat` / `settingsAction` / `chatAction` / `close`，反向 `setSettings` / `setChat` / `showNotice` |
| `dev/pideck/mobile/FlutterLauncherPlugin.java` | Capacitor 插件 `FlutterHost`：`open` / `available` / `pushSettings` / `pushNotice` / `pushChat` / `closePage`，事件 `pageVisibility` / `settingsAction` / `chatAction` |
| `dev/pideck/mobile/PiDeckApp.java` | 自定义 Application，用 Activity 计数维护 `isAppVisible()`（见下） |
| `app/src/platform/flutter-host.ts` | 前端封装：`isFlutterHostAvailable` / `openFlutterPage` / `pushFlutterSettings` / `pushFlutterNotice` / `pushFlutterChat` / `closeFlutterPage` / `onFlutterPageVisibility` / `onFlutterSettingsAction` / `onFlutterChatAction`。老包/浏览器环境静默返回 false / no-op |

启动链路：WebView 里点入口 → `FlutterHost.open()` → `startActivity(FlutterHostActivity)`
→ Flutter `main()` 通过 MethodChannel 取 `{route, sessionId}` → Flutter 页渲染 → 返回键 / 页面按钮 `close()`
→ `finish()` 回到原来的 WebView（WebView 实例没被销毁，状态还在）。

## 工具链要求（硬性）

| 项 | 值 | 说明 |
|---|---|---|
| Flutter | 3.47.9 stable（Dart 3.13.7） | 本机装在 `D:/Flutter/flutter`，路径写进 `flutter_module/.android/local.properties` 的 `flutter.sdk` |
| Gradle | 8.14.3 | Flutter 3.47 的最低要求是 8.14.0，原来的 8.11.1 直接报错 |
| AGP | 8.11.1 | Flutter 3.47 的最低要求是 8.11.1，原来的 8.7.2 直接报错 |
| compileSdk | 35（宿主）/ 36（Flutter 模块） | 模块自己用 `flutter.compileSdkVersion`，两者可以不同 |
| minSdk | **24**（原 23） | Flutter 引擎要求 24，宿主与模块不一致会在 manifest merger 失败。代价：不再支持 Android 6.0 |
| NDK | 28.2.13676358 | 必须显式写进 `app/android/variables.gradle` 的 `ndkVersion`，原因见「坑」第 2 条 |
| JDK | 21 | 不变 |

CI 也要装 Flutter：`.github/workflows/*` 里在 `npm ci` 之前插
`subosito/flutter-action`（`flutter-version: 3.47.9`），并保证 `flutter_module/.android/local.properties`
由脚本生成（`flutter config --android-sdk ... && flutter pub get`），不能提交。

## 构建

```bash
cd app && npm run apk:release      # 和以前一样，Flutter 是 Gradle 里的一环，不需要额外命令
```

Gradle 首次会自己装 NDK 28.2.13676358（约 1GB，落在 `$ANDROID_HOME/ndk/`）。

**国内网络**：Gradle 下 AGP/Flutter 依赖时会直连 Google 仓库，非常慢，看起来像「卡死」。
本机出包时给 daemon 加代理即可（不要写进项目的 `gradle.properties`，否则 CI 无代理会炸）：

```powershell
$env:GRADLE_OPTS = "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"
```

`flutter` 命令本身则认 `HTTP_PROXY`/`HTTPS_PROXY`；注意 pub 的国内镜像 `pub.flutter-io.cn`
会报 `Got TLS error trying to find package crypto`，走代理用官方 pub.dev 更稳。

## 5 个坑

### 1. RunWatch 的前台服务判定会被 Flutter 页误伤（踩了两次）

`RunWatchService` 原来靠 `MainActivity.onResume/onPause` 喂 `setAppVisible`。
跳进 `FlutterHostActivity` 时 WebView 那个 Activity 会 `onPause` → 服务以为用户切走了 → 通知时机全错。
第一次修：改由 `PiDeckApp` 实现 `Application.ActivityLifecycleCallbacks`，用 `startedCount` 计数
（>0 即可见），**新增任何 Activity 都自动纳入**。

但 0.1.19 上仍然会在 Flutter 页停留时弹完成通知，因为还有第二条路径：
`app/src/main.ts` 的 `visibilitychange` 里把 `document.visibilityState === "visible"` 报给原生
（`setRunWatchVisible` → `RunWatchPlugin.setVisible` → `RunWatchService.setAppVisible`），
**把上面正确的计数结果又覆盖掉了**。跳进 Flutter 页时 Capacitor 的 `Bridge.onPause()` 会调
`cordovaWebView.handlePause(keepRunning)` → `webView.onPause()` → `visibilityState` 变 `hidden`。

结论（硬性）：**`document.visibilityState` 在混合栈下不等于「App 是否在前台」**，
它只说明「WebView 有没有被盖住」。所以：

- 可见性**只有一个真相源**：`PiDeckApp.isAppVisible()`（Activity started 计数）。
  `RunWatchService` 直接读它，不再自己存一份；`RunWatchPlugin.setVisible` 保留成 no-op。
- JS 侧要判「真后台」用 `isAppInBackground({ visibilityHidden, flutterPageOpen })`
  （`app/src/platform/run-watch.ts`），其中 `flutterPageOpen` 由
  `FlutterHostActivity.onStart/onStop` → `FlutterLauncherPlugin.notifyPageVisibility` →
  `pageVisibility` 事件送来，JS 侧用 `onFlutterPageVisibility()` 订阅。
- WebView 重新变 `visible` 时无条件把 `flutterPageOpen` 复位为 `false`（兼当兜底，
  避免那条事件因 WebView 被暂停而丢失后标志卡死）。

### 2. 不写 `ndkVersion` 就拿到 165MB 的 libflutter.so

AGP 的 `stripReleaseDebugSymbols` 只在 `ndkVersion` 对应的 NDK 已安装时才真正 strip，
否则打一行 `Unable to strip the following libraries` 然后原样打包。
实测：release 包 **327 MB**（每个 ABI 未 strip 的 libflutter.so 165/151 MB），strip 后 42 MB。
`variables.gradle` 里显式 `ndkVersion = '28.2.13676358'` 即可。

### 3. 包体变大

`abiFilters 'arm64-v8a', 'armeabi-v7a'`（release 早就有）：

| 组成 | 体积 |
|---|---|
| libflutter.so | 11.8 MB（arm64）+ 8.6 MB（v7a） |
| libapp.so（AOT 产物） | 3.5 MB + 3.8 MB |
| flutter_assets | 约 1 MB |
| 原有 Capacitor/ML Kit | 约 13 MB |
| **合计** | **42 MB**（0.1.18 是 13.5 MB） |

要瘦身就只留 arm64（去掉 v7a 省 12 MB），或者后续 Flutter 页做完了把 WebView 那套拆掉。

### 4. 深链与返回键

`FlutterHostActivity` 是 `exported=false` + `launchMode=standard`，
所以「从通知点进来直接进某个 Flutter 页」现在做不到——通知仍指向 WebView 的 deep link。
真要做：在 `MainActivity` 的 intent-filter 之外，让 `PiDeckApp` 记录 `pendingFlutterRoute`，
再由 JS 侧 `consumeLaunchSession` 消费后调 `openFlutterPage`。
返回键交回系统（`FlutterFragmentActivity` 自己处理），不要拦截。

### 5. `MethodCall.argument()` 只认 Map，传字符串直接抛 `ClassCastException`

Dart 这边 `invokeMethod('settingsAction', jsonEncode(action))` 看起来挺自然，但宿主的
`call.argument("json")` 实现是：

```java
if (arguments instanceof Map) return ((Map) arguments).get(key);
else if (arguments instanceof JSONObject) return ((JSONObject) arguments).opt(key);
else throw new ClassCastException();   // ← 不是返回 null
```

异常从 `setMethodCallHandler` 里逃出去，Dart 只收到一个被 `catch (_) {}` 吞掉的
PlatformException——**全页所有按钮一起失灵，而且日志里什么都不明显**。0.1.21 就是这个症状。

规矩：**Dart → 宿主的带参调用一律 `invokeMethod(m, {'json': ...})`**，宿主侧再用
`FlutterHostActivity.settingsJsonFrom(call)` 取值（它同时兼容 Map / JSONObject / String，
不会抛）。`test/host_channel_test.dart` 用 mock messenger 锁住了这个形状。

推论：需要「一定能成」的动作（比如返回）就别绕 WebView 转圈，走专用方法让宿主直接 `finish()`。

## 第一个迁完的页：设置页（0.1.21）

采用的是「**视图迁移**」而不是「页面重写」：

```
main.ts  settingsData()  ──JSON──▶  Flutter SettingsScreen（只负责画）
         createSettingsHandlers()  ◀──JSON──  用户点开关 / 按钮
```

数据源和动作执行**仍然是 WebView 那一套**（主题、通知、设备列表本来就存在这边；模型/技能/扩展/DSH
概况的请求也在 `main.ts`）。Flutter 侧一行状态都不存，所以不存在两套状态打架。

### 通道约定（`pideck/flutter_host`）

| 方向 | 名字 | 载荷 |
|---|---|---|
| JS → 原生 → Dart | `getSettings` | 无参，返回当前缓存的 JSON 字符串 |
| JS → 原生 → Dart | `setSettings`（原生主动 invoke） | 完整 `SettingsData` JSON |
| JS → 原生 → Dart | `showNotice` | 一行提示文本（替代 Web 版的 `toast`） |
| Dart → 原生 → JS | `settingsAction` | `{type, ...}`，`type` 见下表 |

| `type` | 附带字段 | 谁执行 |
|---|---|---|
| `close` | — | `closeSettings()` → `finish()` |
| `refresh` | — | `refreshSettings()` |
| `theme` | `mode` | `runtime.store.setTheme` |
| `notify` | `enabled` | `setNotificationsEnabled` |
| `deviceActivate` | `id` | 切设备 |
| `addDevice` / `disconnect` / `openUsage` | — | 对应的 Web 版流程 |
| `toggleSkill` | `section`,`name`,`enabled` | 用 `settingsSkills` 反查后调协议 |
| `toggleExtension` | `key`,`enabled` | 用 `settingsExtensions` 反查 |
| `dshAction` | `id`,`action`（`run`/`stop`/`uninstall`） | 用 `settingsDshPlugins` 反查 |
| `dshInstall` | `idPrefix`,`name`,`purpose` | `installDshPlugin` |

**动作只传 id / key，不传整个对象**：对象以 WebView 侧的 `settingsSkills` / `settingsExtensions` /
`settingsDshPlugins` 为准，避免 Flutter 传来的字段和实际状态不一致。

入口在 `main.ts` 的 `openSettingsView()`：`isFlutterHostAvailable()` 为真就直接开 Flutter 页，
失败（老包 / 浏览器 / 引擎没起来）回退 `openWebSettingsPage()`，两条路径共用同一份 handler。
Web 版代码**保留不删**，它是回退路径。

`ui/settings-view.ts` 里原来的「实验 → Flutter 混合栈页面」入口已删掉——设置页自己就是 Flutter 版了。

### 主题跟随（0.1.23）

Flutter 页**不自己存主题**，它跟着 WebView 的 `theme.mode` 走：

```
Web 侧改主题 → setThemeMode() → renderSettings()
            → pushFlutterSettings(JSON)  ──▶  _applyJson()
            → widget.onThemeModeChanged(themeModeFrom(data.theme.mode))
            → PiDeckFlutterApp 的 ValueNotifier<ThemeMode> → MaterialApp.themeMode
```

- 颜色不放顶层常量（原来是 `piBackground` 这类），改成 `PiColors extends ThemeExtension<PiColors>`，
  由 `buildPiTheme(Brightness)` 挂到 `ThemeData.extensions`，页面里 `PiColors.of(context)` 取。
  好处是切换主题时整棵树重建、颜色自动跟着走，不用在 MaterialApp 和每个 widget 之间传参。
- `main()` 里先 `loadInitialSettings()` 再 `runApp()`，用 `_themeModeOf()` 算出首帧主题：
  否则浅色用户会先看到一下深色再跳过去。
- `SettingsScreen` 收 `initialJson`，非空时直接打底（`_seed()`），不再走一次 `getSettings` 往返。
- `system` 原样交给 `MaterialApp.themeMode = ThemeMode.system`，让 Flutter 自己跟系统，
  和 Web 侧 `matchMedia` 的判断结果一致。

**后面迁别的页照这个模式**：页面里不许出现 `Color(0x...)` 字面量，一律从 `PiColors.of(context)` 取。

## 流式探针（0.1.24）：聊天页迁移的前置验证

聊天页里 80% 是纯 UI，可以照设置页那套搬。**唯一不确定的是流式读取与后台保活**，
所以在开聊天页之前先用一个探针把它量出来。

入口：设置页 → 关于 → 最下面「流式探针（调试）」（`flutter_module/lib/probe/`）。

两种用例：

| 用例 | 做了什么 | 量什么 |
|---|---|---|
| 会话流 + 发一轮 | 连 `/api/sessions/<id>/stream`，再 `POST /prompt` 投一条真实消息 | prompt→首帧、帧间隔、整轮耗时、帧类型分布 |
| 只读 /api/events 30s | 只连全局状态流 | 连接稳定性、心跳间隔 |

跑的时候**把 App 切到后台几十秒再回来**，报告里的「后台判定」会写清楚后台期间收到几帧 ——
这是决定聊天页用「Dart 直连」还是「继续转发原生 `SsePlugin`」的依据。

实现要点（将来聊天页直接用）：

- `dart:io` 的 `HttpClient` + `response.transform(utf8.decoder)` 是**逐块回调**的，不攒完才给；
  块边界随机，所以解析器必须能跨块拼帧（`SseEventParser.feed()` 的 `_pending`）。
- token 走 query（`?token=`）而不是 header：和 `src/api/client.ts` 的 `withQueryToken()` 一致，
  避开 CORS 预检；但 POST `/prompt` 仍用 `Authorization` 头（和 `headers({withAuth:true})` 一致）。
- 统计里看**中位间隔**比平均值可靠：一个长停顿就能把平均值拉飞。
- 首帧延迟要分两个：`firstMs`（连上流到第一帧）和 `prompt→首帧`（发消息到第一帧）——
  后者才是用户感知的延迟。

### 实测结论（第一轮，0.1.24 报告 + 本机 TCP 层复核）

先纠一个误读：第一份手机报告里「首帧 4.93s / 中位间隔 0ms / `event:message×165`」
**三条都不是服务端的问题**，而是三个不同原因叠在一起：

| 现象 | 真实原因 |
|---|---|
| 首帧 4.93s | 手机连的是 Tailscale 地址（`100.113.205.15:8765`），建连 + 回放传输吃掉了这 5 秒。本机直连首帧 80ms |
| 中位间隔 0ms、平均 6ms | 那是**历史回放**：连上会话流后服务端把整段历史一口气推过来（本会话 155 帧 / 146 KB），不是实时生成 |
| `event:message×165` | 探针自己的 bug：`type` 的正则写成 `[a-zA-Z_]+`，而 pi 的帧类型是 kebab-case（`reasoning-delta` / `tool-input-start` / `start-step`…），全部没认出来。0.1.25 已修 |

复核方法（不用手机，直接量服务端行为）：

```bash
dart run tool/probe_cli.dart "http://127.0.0.1:8765/api/sessions/<id>/stream?token=<token>" 5
```

`tool/probe_cli.dart` 用的是**同一套** `SseEventParser` + `ProbeRecorder`，只是跑在桌面。结论：

- **服务端不攒包**。TCP 层实测：0.001s 就吐出 28,859 字节（几十帧），之后 15s 没有新数据。
  每帧一个 chunk（`38\r\n` / `48\r\n` 这种 chunked 长度），`curl -N` 和 Dart `HttpClient` 都是逐块回调。
- **帧格式**：`data: {"type":"<kebab-case>", ...}\n\n`，纯 JSON，`event:` 字段不填（所以是默认 message）。
- **会话流连上就整段回放**（本会话 155 帧 / 146 KB，`.gitignore` 之外的第二个理由别把探针指向长会话）。
  聊天页接流时必须区分「回放」与「实时」—— 0.1.25 的报告已分成「回放 N / 实时 M」两栏。

### 实测结论（第二轮：后台保活，已定案）

第二份报告（只读 `/api/events`，手机走 Tailscale）：

```
事件数：5    字符数：254424    首帧延迟：204ms
帧间隔 中位/平均/最长：5.00s / 6.16s / 10.01s
帧类型：event:state×5
后台判定：后台期间 0 帧 —— 流被挂起或已断（需要原生保活）
```

读法（两件事跟第一轮不一样）：

- **`event:state` 不是 bug**。`/api/events` 发的是 `event: state` + `data: <全量状态快照>`，
  快照里没有 `type` 字段，所以归类就落到 `event:<event 名>`。本机复核：一帧约 **52 KB**，
  心跳 **5 秒**一次全量快照（不是增量）。聊天页若接这个流，得注意每 5 秒解析一份 50 KB JSON。
- **首帧 204ms**（vs 会话流的 4.93s）—— 因为 `/api/events` 没有历史回放，差别全在回放上。

**定案：聊天页不自己直连，继续走原生 `SsePlugin` 保活，Dart 只负责渲染。**

理由不是「Dart 不行」，而是：

1. 后台 0 帧 —— 手机一进后台，Dart isolate 被挂起，`HttpClient` 的 socket 虽然可能还开着，
   但没人消费（心跳 5s 都收不到），这与现有 `SsePlugin` + 前台服务 + 看门狗的语义不兼容。
2. 现有 `SsePlugin` 已经带了一堆难换的东西：16ms 合并窗口、`FLUSH_THRESHOLD_CHARS=2048`、
   读超时 0（不主动断流）、JS 侧 50s 看门狗、后台保活。在 Flutter 里重写一套等于把这些坑再踩一遍。
3. 真要在 Dart 里做，得配 `flutter_local_notifications` + 前台服务 + 唤醒锁，
   那还不如直接复用已经验证过的原生链路。

所以聊天页的形态是：**原生 `SsePlugin` 读流 → 过桥给 Dart → Dart 渲染列表**，
Dart 侧只需要一个「增量合并 + 虚拟列表 + 滚动锁定」，这正好是 Flutter 的强项。

> 后台判定这一条是在约 10 秒的后台窗口里测出来的（帧间隔里的 10.01s 就是那一次）。
> 0.1.26 把只读模式的时长改成可选 30s / 90s、报告里加了「后台停留」累计时长，
> 要更硬的证据就用 90s 再跑一次、后台待满 60s。

## 聊天页（0.1.27）：视图迁移

聊天页比设置页多一层难度：数据每帧都在变。这里仍然走**视图迁移**，
但没有「一次性整份推送」，而是给 `ChatView` 的十个入口包了一层钩子，
每次渲染完毕把最新状态合并成一份快照推过去。

```
SSE（原生 SsePlugin）→ src/api/frames.ts（ChatReducer，唯一状态机）
  → app/src/main.ts:1968 chatView.setLive(...)      ← Web 版渲染 DOM
  → mirrorChatView() 的包装 → syncFlutterChat()      ← 同一份数据组装快照
  → FlutterChatMirror（40ms 尾沿合并）→ pideck/flutter_host 通道 pushChat
  → Dart ChatScreen（只画）→ 动作回传 chatAction → main.ts 里同一套函数
```

**为什么不在 Dart 里重写 `ChatReducer`**：它是协议核心（14 种帧、`closeOpenBlocks`、
`error` 不收口这些边界），桌面端与手机端共用。复制一份 = 两个真相源，协议一加帧类型就漂移。
所以 Dart 侧**不允许**做任何协议推断（拼 delta、猜工具状态、自己算 token），只能画已有字段。

### 数据契约（`ChatSnapshot`，改名要两边一起改）

| 字段 | 说明 |
|---|---|
| `sessionId` / `title` | 空 `sessionId` 表示宿主还没准备好，Dart 侧直接忽略该帧 |
| `status` / `statusText` | `idle` / `running` / `finished` / `error` / `aborted` |
| `error` | `{title, detail?, hint?}`，来自 `describeHistoryFailure()` |
| `empty` / `loading` | 空态文案、首屏加载中 |
| `history[]` | 已落盘消息；`role=tool` 时用 `toolName`/`toolState`/`toolDetail` 表达工具条目 |
| `live` | 当前轮的 `{messageId, blocks}`，块就是 `ChatBlock`（text/reasoning/tool） |
| `pending` | 只取第一条 `PendingUiRequest`（整份透传，含 `batchQuestions`） |
| `perf` | `PerfMetrics` 的展示子集（首字 / 流式 tok/s / tokens） |

动作方向（Dart → `main.ts` 的 `dispatchFlutterChatAction`）：
`send` / `stop` / `respond` / `messageAction` / `refresh` / `close`。
**只传 id 与必要参数**，不传整条消息或整个选项对象 —— 那边的状态可能已经变了。

### 三个必须照抄的行为

1. **贴底阈值 80px**（`chat-view.ts:65-68`）。用户往上翻就停止自动贴底，否则长回答时
   用户想回头看历史会被每帧拽回底部。Dart 侧在 `_onScroll` 里算
   `maxScrollExtent - offset <= 80`，新快照落地后 `addPostFrameCallback` 里 `jumpTo(max)` ——
   必须等下一帧，`setState` 之后 `maxScrollExtent` 还是旧值。
2. **40ms 合并推送**（不是 16ms）。过桥是 `invokeMethod(String)`，几十 KB 的 JSON 要序列化 +
   跨线程投递 + 再 parse；原生 `SsePlugin` 自己已经在 16ms 合并过一轮，再加一层 16ms 只会发热。
   内容没变不重复过桥（状态轮询 1s 一次）。
3. **`setHistory` 整块替换、`setLive` 增量**。Dart 侧对应 `ListView.builder` 的
   `itemCount = history.length + live.blocks.length + 1`，末尾那个 1 是 footer
   （状态 / 错误 / 提问卡 / 指标）—— 不把 footer 塞进列表项，否则每次状态变化都会让整列表重新布局。

### 踩过的坑

1. **提问卡的输入框不能用 `TextEditingController(text: ...)` 现建**。流式时每 40ms 来一份新快照，
   每次 build 新建 controller 会把光标位置和输入法组合状态一起冲掉（表现为「打字打一半跳回开头」）。
   必须用常驻 controller，只在 `requestId` 变化时写入 `prefill`。
2. **多选的临时选择不能放在 `build` 里的局部变量**。同理，每次重建都清空。放 State 里，
   换 `requestId` 时清。
3. **`batch_ask` 的提交格式**是 `{"answers":[{id,type,value,label,wasCustom}]}` 的 JSON **字符串**
   （`ask-model.ts:serializeBatchAnswers`），不是对象。`value: null` = 没碰过，`value: ""` = 主动留空，
   两者在 PiDeck 侧含义不同，不能合并。
4. **长按消息的操作菜单还在 WebView 里**（`openMessageActions` 弹的是 DOM 底部弹层，
   在 Flutter 页底下看不见）。当前做法：Flutter 里只给「复制」，另一个入口是
   「更多操作（回 Web 版）」—— 发动作后 `closeHost()` 让开，用户才能看见那个弹层。
   等 `message-actions-model.ts` 的菜单项抽成数据（`{id, label, danger}`）后在 Flutter 里重做。

### 调试入口

设置页 → 关于 → **「聊天页预览（调试）」**（0.1.27 起）。它走 `chatPreview` 动作 →
`openFlutterChatPage(activeSessionId)`，要求当前已经有打开的会话（WebView 必须先把这个会话
开起来：历史、SSE、reducer 都在它身上）。

## 侧栏 Drawer（0.1.28）：比聊天页更彻底的视图迁移

侧栏是聊天页的抽屉，所以跟着聊天页一起迁。这一页的分支比聊天页还多
（分段 / 搜索 / 项目折叠 / 预览截断 / 多设备分块 / 徽标），因此策略更进一步：
**Dart 里一行规则判断都不写**，只实现一个通用渲染器，画 `flat` / `device` / `group` 三种段。

```
src/ui/sidebar-model.ts + sidebar-tabs-model.ts   ← 分组/折叠/截断/计数的唯一实现
  → app/src/platform/flutter-sidebar.ts buildSidebarSnapshot()
  → app/src/main.ts syncFlutterSidebar()（在 renderSidebar() 末尾，内容相同就跳过）
  → 通道 pushSidebar → Dart SidebarDrawer（只画）→ 动作回传 sidebarAction
```

**为什么不把 `buildSidebarGroups` 翻成 Dart**：它是纯函数、单测覆盖得很好，翻一份就等于
维护两套「展开其余 N 个」的截断规则。踩过的具体分歧点：当前会话被截断时该组要**临时展开**
（不写回用户的显式意图），如果 Dart 自己算，「展开其余」按钮就会指向错的数量。

### 动作清单（`sidebarAction` 的 `type`）

| type | 参数 | 谁执行 |
|---|---|---|
| `openSession` | `id` | `openSession(id)`（Flutter 页不关，快照跟着换） |
| `toggleTab` | `tab` | `sidebarTab = tab` + 持久化 + 重画 |
| `search` | `query` | `sidebarQuery` + 重画 |
| `toggleProject` | `projectId` | `toggleProject()` |
| `toggleSection` | `key` | `project:` 前缀 → `toggleSessionsExpanded()`，否则 `toggleFlat()` |
| `createSession` | `projectId` | `createSession(projectId)` |
| `sessionRename` | `id`、`title` | `runSessionAction(id,"update",{title})`（空标题回推提示） |
| `sessionCopy` / `sessionDelete` | `id` | `runSessionAction()` |
| `sessionExport` | `id` | `exportSessionHtml(id)` |
| `sessionMore` / `projectMenu` / `newSession` | `id` | **先关 Flutter 页**再开对应的 WebView 弹层 |
| `openSettings` | — | `openSettings()`（会开 Flutter 设置页） |
| `close` | — | 关页回 WebView |

`toggleSection` 的两条分支必须分开：项目组的「展开全部会话」用的是
`expandedSessions`，扁平段的「展开其余」用的是 `flatExpanded`，两个集合互不相干。

### 三个必须照抄的行为

1. **搜索框 controller 常驻**：`didUpdateWidget` 里只在「内容不同**且**没聚焦」时同步，
   否则宿主每推一份新快照就会把光标和输入法组合状态冲掉（聊天页提问卡踩过同一个坑）。
2. **长按菜单在 Flutter 里重做**：WebView 的弹层长在 DOM 上，被 Flutter 页盖着根本看不见。
   「更多操作（回网页版）」这一项就是给这种情况留的出口 —— 先 `closeFlutterPage()` 再开 WebView 菜单。
3. **`clipped()` 展开时也要返回 `hiddenCount`**：否则 Dart 侧拿不到「收起」入口，
   点开「展开其余 6 个」之后就再也收不回去。

调试入口：从「聊天页预览」进去，左上角汉堡键。

## 长按消息菜单 + 会话点击（0.1.29）：把最后两处 DOM 依赖搬过来

0.1.28 之前，聊天页里有两件事必须回 WebView 做：长按消息的菜单（DOM 弹层，被 Flutter 页盖着看不见）
和「点侧栏会话切过去」。0.1.29 把这两处补齐，聊天页才算能独立用。

### 菜单项不再维护两处

Dart 里**没有**自己的动作表，`ChatSnapshot.messageMenus` 直接装 `actionsForRole(role, backend)` 的结果：

```json
{ "messageMenus": {
    "user":      [{"id":"copy","label":"复制文本"}, …, {"id":"delete","label":"删除","danger":true}],
    "assistant": [{"id":"copy","label":"复制文本"}, {"id":"fork","label":"分叉会话"}]
} }
```

好处是后端能力一变两边一起变：ACP 会话在 `actionsForRole()` 里只剩「复制」，Dart 那边不用改一行。
Dart 侧只负责「怎么画」（danger 标红）和「哪两步要本地确认」。

### 哪两步必须在 Flutter 里做

| 动作 | 谁做确认 | 为什么 |
|---|---|---|
| 删除 | Flutter `_confirm()` | 原确认框在 WebView 里，被盖着看不见；确认过就直接执行，不再回传 |
| 编辑 | Flutter `_editText()` | 多行输入框同理；内容没变就不发请求 |
| 复制 / 重发 / 分叉 | 无需确认 | 回传 `messageAction` 由 WebView 执行 |

### 「返回网页版」之后别再弹回来

用户点了聊天页右上角「返回网页版」，说明他这轮想用 WebView 版。所以 `main.ts` 里有个
`chatUseWebView` 标记：置上之后点侧栏会话不再走 `openFlutterChatPage()`，直接 `openSession()`。
**不写 localStorage** —— 重启 App 就回到 Flutter 版（那是默认形态），不给用户留一个找不回来的开关。

### 一条容易漏的回归

`onOpenSession` 原来直接 `openSession(sessionId)`，改成 `openSessionFromSidebar()` 之后**必须**保留
`onEndpoint(run, …)` 那层包装 —— 多设备场景下 `run` 负责切端点，丢了就会出现「点 A 设备的会话，
连的还是 B 设备」。

## 文件保存 / 选图 / 扫码（0.1.30）：把「插件」也搬过来

这一轮搬的不是页面，而是聊天/设置里三处「点一下就要跟系统打交道」的能力。
迁移顺序里第 4 项原本写的是「文件保存 / 选图 / 扫码」，实际做下来分成了两类：

| 能力 | 结论 | 为什么 |
|---|---|---|
| 保存文件 | 原生共用一份实现 | 落盘规则（`Downloads/PiDeck`、重名加后缀、API<29 退私有目录）已经是 `FileSaverPlugin` 里的产品决策，复制一份必然漂移 |
| 选图 | 原生共用一份实现 | `MAX_EDGE=1600` / `JPEG_QUALITY=82` / 8MB 上限在 `image-attach-model.ts` 里；且 `BitmapFactory.inSampleSize` 是**解码时**降采样，Dart 里整张进内存再缩放容易 OOM |
| 扫码 | **不迁**，回 WebView 开面板 | `BarcodeScanner.scan()` 靠 Capacitor bridge 接 ActivityResult，在 `FlutterHostActivity` 里拿不到结果 |

### 保存：抽出 `SavedFiles`

`FileSaverPlugin`（WebView 的 `FileSaver.save`）与 Flutter 通道的 `saveFile` 现在都调
`SavedFiles.save(Context, byte[], name, mime)`：MediaStore `Downloads/PiDeck` + `IS_PENDING` 两段式写入、
失败删空条目、API<29 退 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`。
Dart 侧只有一句 `saveTextFile({name, text, mime})`，内部 `base64Encode(utf8.encode(text))`。

入口两处：代码块右上角「保存到手机」（文件名按语言标记给扩展名，认不出给 `.txt`）、
工具卡展开后的「保存到手机」（几十 KB 的输出，长按选中复制很难受）。

### 选图：为什么用 `GetContent` 而不是相册库

`registerForActivityResult(new ActivityResultContracts.GetContent())` 一行就够，
系统选择器自带多来源（相册/文件/截图），也不用申请存储权限。
代价是拿不到「多选」——但产品上限本来就是 4 张（`MAX_IMAGES`），先单张多次添加。

解码在 `FlutterFiles.readImage()` 里做，三步都不能省：

1. `inJustDecodeBounds` 先取尺寸，按 `longest / (sample*2) >= MAX_EDGE` 定 `inSampleSize`；
2. 缩放后再 `compress(JPEG, 82)`；
3. **按 EXIF 旋转**。浏览器 canvas 会自动应用 EXIF，`BitmapFactory` 不会 —— 少了这步竖拍照片会躺着。

回给 Dart 的是裸 base64（不带 `data:` 前缀），`PickedImage.dataUrl` 才拼前缀；
WebView 侧需要 `data:` 开头，所以 `main.ts` 的 `attachmentsOf()` 负责补。

### 扫码：`openWebSheet` 通道方法

Dart 点「扫码配对」→ `openWebSheet('connection')` → 原生先 `notifyListeners("webSheet")`
再 `finish()` 自己 → `main.ts` 收到后 `leaveFlutterThen(() => openConnectionSheet())`。
**顺序不能反**：先关 Flutter 页再开弹层，否则弹层开在被盖住的 WebView 上。

> 这也是 `leaveFlutterThen()` 存在的第三个理由（前两个是工作区选择器和各种 sheet）。
> 判断标准很简单：这个界面依赖 WebView 的插件或 DOM 弹层吗？是就走这条。

## 顶栏与输入框附属面板（0.1.31）：把「算出来的东西」也推过去

设置页、聊天页、侧栏、文件能力都搬完之后，聊天页里最后一层「点一下闪回网页版」的东西是
顶栏（模型 / 思考 / 安全 / 计划 / 上下文）和输入框的附属面板（提示词 / 上下文用量）。

这一层和前面几页的区别：**它的内容不是数据，是判断**。要不要显示「模型」入口取决于
`composerToolChips(backend)`（ACP 会话没有模型概念），思考档位文案取决于 `thinkingLabel()`，
安全级别取决于「全局默认 + 本会话覆盖」两级状态。所以做法比侧栏更彻底：

> Dart 侧一个判断都不写。`ChatHeaderView` 里只有「画什么」，没有任何 `if (backend === "acp")`。

### 数据契约

顶栏跟着聊天快照一起推（`ChatSnapshot.header`），选择面板也跟着推（`ChatSnapshot.picker`）：

```jsonc
{
  "header": {
    "title": "会话标题", "meta": "PiDeck · 运行中", "running": true,
    "chips": [
      { "key": "model", "label": "claude-sonnet" },
      { "key": "thinking", "label": "思考 高" },
      { "key": "security", "label": "标准", "muted": true },
      { "key": "plan", "label": "计划", "active": true }
    ],
    "context": { "percent": 95, "overflow": true, "compacting": false, "label": "190K / 200K" },
    "sendMode": "steer", "showSendMode": true, "canSteer": true, "promptCount": 3,
    "todo": { "open": 2, "done": 1, "current": "搬侧栏" },
    "subagents": { "active": 1, "total": 2 }
  },
  "picker": {
    "kind": "model", "title": "选择模型", "searchable": true,
    "items": [{ "id": "anthropic\u0000claude-sonnet", "label": "Claude Sonnet", "section": "anthropic", "selected": true }]
  }
}
```

`chips[].key` 同时是点击时回传的动作名（`{"type":"chip","key":"model"}`），所以
**新增一个 chip 必须同时在 `dispatchFlutterChatAction` 里加分支** —— 这是这套设计唯一的
「两处要一起改」的地方，写在 `flutter-chat-header.ts` 的注释里了。

### 选择面板放在快照里，而不是另开通道

面板内容（模型列表、提示词模板）都是 WebView 异步取回来的，本来就要经过一次
`setChat`。另开一条 `setChatPicker` 通道的代价是：两处 `flutterPicker` 状态、两处去重、
以及「面板开了但快照还没到」的中间态。塞进同一个快照就全没了 —— `FlutterChatMirror`
本来就在做 40ms 合并与内容去重。

### 面板用内联的，不用 `showModalBottomSheet`

弹层的开合状态由 Dart 自己的 `Navigator` 管，而面板内容由快照推 —— 两边一旦不同步就会出现
「关了又弹」：用户点了关闭，`closePicker` 还没到，下一份快照里 `picker` 仍在，弹层又被拉起来。
内联面板是纯函数式的：`picker == null` 就收起，没有中间状态。搜索框在 Dart 本地过滤
（模型几十条、模板几十条，不值得为每次按键过一次桥），只在回车时发一次 `pickerQuery`。

### 提示词数量是懒加载的

「提示词」按钮只在 `promptCount > 0` 时出现，而模板列表要发一次 `/runtime/commands`。
所以 `openFlutterChatPage()` 里顺手拉一次（失败就当 0，按钮不显示，不阻塞开页）。
这条规则来自 `prompt-picker-model.ts` 的注释：模板正文不在 Web API 里，选中只能插 `/名字 `，
**不要**自己拉正文。

### 动作清单（`chatAction` 里新增的 `type`）

| type | 载荷 | 行为 |
|---|---|---|
| `chip` | `key` | `plan` 直接切计划模式；`dsh` 走 `leaveFlutterThen(openDshToolsSheet)`；其余开对应面板 |
| `context` | — | 开上下文面板（百分比明细 + 「压缩上下文」） |
| `prompts` | — | 开提示词面板（插 `/名字 ` 到输入框） |
| `slash` | — | 开斜线命令面板（0.1.32） |
| `mention` | — | 开 @ 文件面板（0.1.32） |
| `sendMode` | — | 切换排队 / 插话 |
| `pickerQuery` | `kind`, `query`, `context` | 重新取一次面板内容（模型 / 提示词 / 斜线 / @ 文件）；`context` 原样带回，@ 提及靠它留在当前目录 |
| `pickerSelect` | `kind`, `id` | 先关面板再落地（压缩上下文要好几秒，面板开着会让人以为没点中） |
| `closePicker` | — | 收起面板 |

## 看图 + 斜杠 + @提及（0.1.32）：把「插入输入框」搬到 Dart

这一批解决的是同一个矛盾：**面板内容由 WebView 算，但面板开着时输入框在 Dart 手上**。
选择面板从 0.1.31 起就在快照里，可选完之后「插到光标处」这一步原来还要过桥回 WebView ——
而 WebView 的输入框此刻是隐藏的，插进去用户根本看不见。

### 纯文本动作：`insert` 字段

`ChatPickerItem` 加了 `insert`：**有它就是纯文本动作**。

- Dart 拿到 `insert` 直接写进自己的 `TextEditingController`，然后发一条 `closePicker` 收工；
- 没有 `insert`（模型 / 思考 / 安全 / 压缩上下文 / `dir:` 目录）照旧回 `pickerSelect`，由 WebView 真去执行。

提示词、斜线命令、@ 文件全部改成这一路。`applyFlutterPickerChoice` 里那条 prompt 分支保留做兜底
（老版本 Dart 传上来的仍是 `pickerSelect`），但新路径不再经过它。

### 触发字符：只认「刚敲进去的那一个字符」

`/` 与 `@` 由 Dart 侧的输入框监听触发（WebView 的输入框这页不在场）。规则抽进了
`flutter_module/lib/chat/composer_insert.dart` 的三个纯函数，理由是这三条都踩过：

| 函数 | 守的是什么 |
|---|---|
| `composerTrigger()` | 只认长度 +1、光标折叠、字符正好是 `/` 或 `@`；`/` 还要求输入框里没有别的东西。粘贴 `@src/a.ts` 不该弹面板 |
| `insertIntoComposer()` | 光标为 -1（刚清空、还没聚焦）时**追加到末尾**，不要因为起点 -1 就把整段文字当选区替换掉 |
| `resolvePickerTap()` | 有 `insert` 就插文本 + 关面板，否则回 `pickerSelect` |

再一个坑：打完 `/` 叫出面板，选中后插入的是 `/goal `，若直接按光标插入就成了 `//goal `；
`@` 同理变 `@@src/a.ts `。所以触发时置 `_composerHoldsTrigger`，下一条插入整段替换。

### @ 提及的目录钻取

`/api/files` 只列一层，所以面板要能「往下点」。做法：

- 目录条目 id 是 `dir:<相对路径>`，**不带 `insert`** → Dart 回 `pickerSelect`；
- WebView 收到 `dir:` 前缀不关面板，直接换成那个目录的内容（`openFlutterPicker("mention", "", trail)`）；
- `..` 也走 `dir:`（指向上一级），所以只需要一条规则；
- 面板自己在 `ChatPickerView.context` 里带出当前位置，用户在面板里打字搜索时原样带回 ——
  不然一搜索就跳回项目根目录。

### 看图：`ImageViewerPage`

`flutter_module/lib/chat/image_viewer.dart`：`PageView` + `InteractiveViewer`，捏合缩放、左右翻页、点一下关掉。
两条踩坑记录：

1. **`PageView` 的滑动收不到**。`InteractiveViewer` 的缩放手势会在手势竞技场里先赢，祖先的横向拖动全被吃掉
   （`tester.drag` 实测完全不动）。所以 `PageView` 关掉物理滚动只当动画容器，翻页改成听
   `InteractiveViewer.onInteractionEnd` 的速度：scale ≈ 1 且横向速度 > 240 才翻页；
   放大状态下的拖动仍然交给 `InteractiveViewer`，否则放大后看不了边角。
2. **大图解码要限宽**。`data:` 内联图的原始像素常常比屏幕大几倍，`Image.memory` 加
   `cacheWidth = 屏宽 × devicePixelRatio`，不然后台几张图就能把内存吃满。

### 新建会话 / 切后端：**故意没迁**

`openWorkspacePicker`（新建会话选工作区 + 后端）与 `openBackendSheet`（已有会话切后端）仍走
`leaveFlutterThen(...)` 回 WebView。理由不是没时间，是结构上不对：

- 它们由**侧栏抽屉**发起，而抽屉盖在聊天页上面，内联面板会出现在抽屉背后；
- 「新建会话」成功后要换一个会话，那需要聊天页整体重开，属于页面级导航而不是面板；
- 两件都是低频动作，回 WebView 的代价只有一帧切换。

真要迁，需要先给 Dart 侧加「全屏页面级面板」（`Navigator.push` 一个选择页），那是另一件事。

## 历史跳转 + 待办展开 + 空态建议（0.1.33）：本地能算的就不问 WebView

这一批三个都是「用户点了要立刻有反应」的小东西，做法上共同点只有一个：
**能在 Dart 侧用手上已有的快照算出来的，就不要绕回 WebView。**

### 「历史」跳转：二分定位，不按平均高度估

Web 版是 `chatView.scrollToMessage(id)`，操作的是 DOM。Dart 侧的历史是一份
`ChatSnapshot.history`（有 `id`），所以跳转完全可以在本地做：

1. `_onPickerTap` 里特判 `picker.kind === "history"`：发 `closePicker`，然后本地滚，**不**发 `pickerSelect`。
2. `ListView.builder` 是懒构建的，目标项没渲染就没有 `BuildContext` 可以 `ensureVisible`。
   于是在 `[0, maxScrollExtent]` 上**二分**：跳到中点 → 这一帧渲染出来的下标区间
   （`_renderedHistoryRange()` 扫 `_historyKeys` 里 `currentContext != null` 的项）若包含目标就收敛，
   否则按目标在中点之上/之下砍半。下标随偏移严格单调，3~5 跳就落到目标附近。
3. 落到之后 `Scrollable.ensureVisible(alignment: 0.12)`，再高亮 1.4 秒（`_highlightId`）。

为什么不用「平均高度 × 下标」估算：长回答和短回答的高度差一个数量级，
估算值在最需要它的长会话里最不准；二分只依赖单调性，和高度分布无关。

**没做到的地方**（诚实记录）：只能跳到**已加载**的历史。更早的消息需要先向上滚动加载，
目标不在快照里时给一句「这条消息不在已加载范围内」，而不是假装跳了。

### 待办清单：展开态是本地状态

顶栏那一行摘要（`任务 2/5 · 正在写测试`）现在可点，展开后逐条列出（对勾 / 实心点 / 空心圈，
完成项划掉）。三个约定：

- **展开态不推回 WebView**：它纯属本机 UI 偏好（和 Web 版 `createTodoStrip()` 的 `collapsed`
  一样只存内存），推回去只会多一个需要同步的字段。
- 条目**不可点选**：清单由 agent 维护，用户点它没有语义。
- `total` 从快照带过来（`ChatTodoSummary.total`），老快照没有这个字段时退回 `open + done`，
  否则会显示 `任务 0/0`。

### 空态建议：只填不发

`empty-suggestions.ts` 的三条按项目类型给的提示词，随快照的 `suggestions` 一起推；
Dart 只在「没历史、也没有正在跑的块」时画（`bare`），点一下把文字填进输入框并聚焦 —— 和 Web 版
`onSuggestion` 一致。**不直接发送**：用户几乎总要先改两个字。

## 项目长按菜单（0.1.34）：把「到不了的分支」留下

项目分组头的长按菜单原来 `leaveFlutterThen(() => openProjectMenu(id))`：抽屉开着，网页弹层在背后，
用户看到的是抽屉先关掉、闪一下、然后菜单才出来。现在菜单在 Flutter 底部弹层里画：

- 三项：在此项目新建会话 / 展开或收起该项目 / 从列表移除项目（danger）；
- 动作照旧发回侧栏通道（`createSession` / `toggleProject` / 新增的 `projectRemove`），
  执行逻辑只有 `main.ts` 一份；
- 二次确认在 Dart：`AlertDialog` 明说「只从列表移除，不会删除磁盘上的项目文件」。

**为什么没有「内置 Chat 项目不能移除」这条**：Web 版 `openProjectMenu` 里有这个分支，但它到不了 ——
Chat 项目在侧栏是「聊天」段的平铺行，`buildSidebarGroups()` 产出的分组里根本没有它
（`app/tests/flutter-sidebar.test.ts` 有一条测试钉住这点）。所以快照里没有 `chatProject` 字段，
Dart 侧也无条件给「从列表移除」。搬迁移时先问一句「这条分支前端能不能触发」，
搬过去到不了的分支只会多一处需要维护的假逻辑。

顺带把 `confirmRemoveProject` 拆成「确认框」+ `removeProject(projectId)`，
让 Web 的弹层和 Flutter 的确认框走同一个函数 —— 各写一份一定会漏掉 `activeProjectId` 的清理。

## 通用底部弹层（0.1.35）：把「弹层」也变成数据

到 0.1.34 为止，从 Flutter 页能点到的最后三处 WebView 弹层是：新建会话（工作区选择器）、
会话菜单里的「切换后端」、聊天页 DSH chip 的「DSH 工具」。它们都只能 `leaveFlutterThen()`
——先关掉 Flutter 页、闪一下、再在网页版里弹出。

现在统一成一份 `SheetView`（`app/src/platform/flutter-sheet.ts`）：

```ts
{ kind, title, note?, items: [{ id, label, description?, group?, danger?, disabled?, selected?, actionable? }] }
```

- WebView 侧算「有哪些行、每行点了发什么 id」；Flutter 侧（`flutter_module/lib/common/app_sheet.dart`）
  只负责画分组列表并回传 `sheetPick { id }`；
- 侧栏面板走 `SidebarSnapshot.sheet`，聊天页面板走 `ChatSnapshot.sheet`，两条通道分开，
  避免抽屉和聊天页同时画一份；
- **面板无状态**：选中哪一行不放在 Dart。点后端那一行时，`main.ts` 改自己的 `choice` 变量、
  重算整份视图再推一次——`AppSheetHost` 用 `ValueNotifier` 原地换内容，所以不闪、不重开动画。
  无状态的好处是面板关掉再打开不用同步两处状态；代价是每次点选重算一次（几十行，可忽略）。

具体到三个界面：

| 界面 | 行 | 点完发生什么 |
|---|---|---|
| 新建会话 | 后端组（仅当后端多于一个）+ 工作区组（项目名 / 路径 / 会话数） | `rememberBackendChoice()` + `createSession(projectId, choice)` |
| 切换后端 | 后端列表，当前项打勾；会话已拉起 runtime 时直接提示锁定原因 | `runSessionAction(update, backendPatch())` 再重开会话 |
| DSH 工具 | 目标 / 子代理 / 技能三组，纯信息行不给箭头 | 子代理 → `leaveFlutterThen(openDshSubagentHistory)` |

两个刻意的取舍：

1. **切换后端改成「点一行即应用」**，Web 版是「选完再按应用」。手机上两步选择只为了一次撤销，
   换来的是一次多余点击；而锁定态本来就在点开面板时提示了。
2. **DSH 子代理历史仍回网页版**：那是要滚动的长文本（角色前缀 + 多段），
   搬过来的收益远小于再写一套排版。

## 视觉对齐 Web（0.1.37）：别凭手感重画（**已被 0.2.0 取代**，留作历史）

用户反馈「新加的原生 UI 感觉怪怪的」。查下来不是某个组件写错了，而是**重画的时候用了自己的手感**：
气泡、工具卡、输入框、空态各用各的圆角与间距，一屏里像拼出来的。基准其实一直在仓库里 ——
`app/src/styles.css` 的 `:root` 就是设计 token：

| token | 值 | Dart 侧对应 |
|---|---|---|
| `--bg` | `#18181b` | `PiColors.background` |
| `--bg-soft` | `#1f1f23` | `PiColors.surface` |
| `--bg-elev` | `#26262b` | `PiColors.surfaceAlt` |
| `--border` | `#333338` | `PiColors.divider` |
| `--radius` | `12px` | 卡片/气泡圆角 |

这一轮改掉的具体偏差：

| 位置 | 之前（Dart） | 现在（照 Web） |
|---|---|---|
| 助手消息 | **没有气泡**，裸 Markdown | `.msg-body`：`bg-soft` 底、1px 边线、圆角 12、内边距 10/12 |
| 用户消息 | 右对齐 + 82% 限宽 + 圆角 14 | 整宽、圆角 12、内边距 10/12 |
| 用户气泡底色 | `accent.withValues(0.16)` 直接叠页面底色 | `alphaBlend(accent 16%, bg-soft)`，边线 `alphaBlend(accent 32%, border)` |
| 角色行 | 没有 | `.msg-role`：11px 暗色，「我」/「Pi」 |
| 时间 | 塞在气泡里，10.5px | `.msg-time`：气泡**外**、10px |
| 图片 | 宽 140、只有圆角 8 | `.msg-image`：128×128 `cover`、圆角 10、1px 边线 |
| 工具卡 | 有底色、圆角 8 | `.tool`：无底色、圆角 10、内边距 6/10 |
| 输入框 | 圆角 20、`surface` 底 | 圆角 10、`--bg` 底、聚焦时边线混 accent 60% |
| 空会话 | 三个 chip 飘在输入框上方 | `.empty-state` 整块：92px 圆底徽标 + 23px 标题 + 13.5px 副标题 + 胶囊提示词 |
| 提示词 chip | 圆角 999 但有边线、12.5px、12/7 内边距 | `.suggestion-chip`：无边线、`bg-elev` 底、13px、8/16 内边距 |
| 看图页按钮 | `PiColors.text` | 固定白色 —— 看图页是纯黑底，浅色主题下 `text` 是深灰，按钮等于隐形 |
| 设置页分组卡 | 圆角 14 | 圆角 12 |

**两条可以直接用的判断**：
1. **Dart 里不该出现自己编的视觉常量**。要间距去 `styles.css` 里复制，要颜色用 `PiColors` 里与
   token 一一对应的那个字段；`--bg-soft` 不是 `surfaceAlt`，混用会让深色主题下整屏脏一档。
2. **半透明色要 `alphaBlend` 到它真正的底色上**，不要直接 `withValues(alpha:)` 叠页面底色 ——
   前者得到与 Web `color-mix(in oklab, …)` 接近的效果，后者在深色下会明显更艳。

## 「按哪个键都慢半拍」（0.1.38）：四笔白工

用户反馈「有些按键组件特别卡」。查下来没有任何一处是「按钮动画慢」，而是**每一次界面刷新都在做无用功**，
而 Flutter 页的刷新频率是跟着 WebView 的快照推送走的。

| 位置 | 之前 | 为什么卡 | 现在 |
|---|---|---|---|
| `app/src/platform/flutter-chat.ts` `PUSH_INTERVAL_MS` | 40ms（≈25 次/秒） | 每次推送都带**整份历史** JSON，过桥后 Dart 侧再 `jsonDecode` 一遍 | 80ms（≈12 次/秒） |
| `flutter_module/lib/chat/markdown_view.dart` | `MarkdownView` 是 `StatelessWidget`，build 里直接 `parseMarkdown(source)` | 快照一来整页重建 → **每条可见消息重解析一遍**；长回复 + 流式时一帧几十次分词 | 走 `parseMarkdownCached()`（FIFO 48 条，见 `lib/chat/markdown.dart`） |
| `flutter_module/lib/chat/chat_page.dart` `_ChatImage` | `data:` 图每次 build 都 `base64Decode` | 一张 1600px 的图几百 KB base64，滚动/流式时每帧解一次 | `_imageBytesCache`（FIFO 24 条）+ `cacheWidth: 384`（缩略图只要 128 逻辑像素） |
| `chat_page.dart` `_onComposerChanged` | 每敲一个字 `setState(() {})` | `_composerText` 只用于判断「刚打的是 / 还是 @」，**不驱动任何 UI** —— 整页重建纯白工 | 删掉 setState |
| `chat_page.dart` 面板搜索框 | `onChanged: (v) => setState(() {})` | 同上，整页重建 | `_pickerPanel` 外层套 `ValueListenableBuilder<TextEditingValue>`，只重建面板；搜索词归零挪到 `_applyJson`（build 期间清 controller 会触发「build 中 setState」断言） |

**判断准则（写进 AGENTS.md）**：
1. 快照驱动的页面里，任何 `setState` 都要能说出「哪个可见元素依赖它」。说不出就是在拿整页重建换一个没人看的变量。
2. 纯函数式转换（markdown 解析、base64 解码、格式化）只要输入稳定就该缓存；缓存用 FIFO + 上限，别用无界 map
   （流式时每帧都是新 key，无界就是内存泄漏）。
3. 图片永远给 `cacheWidth`：解码成本跟原始像素成正比，而缩略图只有 128 逻辑像素。
4. 推送频率是产品参数，不是越快越好 —— 40ms 与 80ms 在流式文本上看不出差别，但解码与重建成本差一倍。

## 0.2.0：从「对齐网页」改成 Material 3（目标变更）

0.1.21–0.1.38 的迁移是「把 WebView 里的像素搬进 Flutter」：字号、圆角、间距都照抄 `app/src/styles.css`。
结果是**能跑，但看着还是网页** —— 手画的胶囊、虚线框、12px 圆角、贴着边框的小灰字，都是 Web 的手段，
不是一台安卓原生应用的样子。用户的原话是「加这个 flutter 我是想重构整个 UI，当前怎么还是 web 样式」。

从 0.2.0 起，**`app/src/styles.css` 不再是视觉基准**。基准换成 Material 3：

### 设计层（`flutter_module/lib/app_theme.dart`）

- `buildPiTheme(Brightness)` → `ThemeData(useMaterial3: true)`，配色 `ColorScheme.fromSeed(seedColor: 0xFF4C6FE0)`，
  亮/暗两套，`themeMode` 默认 `system`（跟系统走）。
- **`PiColors` 降级成「语义色薄适配层」**：`background/surface/surfaceAlt/accent/danger/text/textSecondary/textMuted/divider`
  全部映射自 `colorScheme`（`surface` / `surfaceContainerLow` / `surfaceContainerHigh` / `primary` / `error` /
  `onSurface` / `onSurfaceVariant` / `outlineVariant`）。好处是**还没逐个改写的页面立刻跟着 M3 调色板走**，
  不会出现「聊天页是 M3、侧栏还是旧配色」的两套视觉。
- 组件主题一次定死，页面里不再写像素：`appBarTheme`（surface 底、无 tint）、`cardTheme`（radius 16、`surfaceContainerLow`、
  `outlineVariant` 边）、`bottomSheetTheme`（顶部 radius 28 + `showDragHandle: true`）、`dialogTheme`（radius 24）、
  `inputDecorationTheme`（filled + radius 20 + focused 用 `primary`）、`chipTheme`（radius 8、`surfaceContainerHigh`）、
  `filledButton/outlinedButton/textButton`（radius 20）、`snackBarTheme`（floating）、`popupMenuTheme`（radius 16）。
- M3 角色色（`primaryContainer` / `onPrimaryContainer` 等）**不进 `PiColors`**：需要时直接 `Theme.of(context).colorScheme`，
  语义与角色分开，免得 PiColors 变成一个什么都装的袋子。

### 各页面变成了什么

| 位置 | 0.1.x（照抄 Web） | 0.2.0（M3） |
|---|---|---|
| 顶部信息条 | 手画胶囊（`InkWell` + 圆角容器 + 边框） | `ActionChip`（带涟漪与无障碍语义），选中态 `secondaryContainer` |
| 空态提示词 | 手画胶囊、`bg-elev` 底、无边线 | `ActionChip`，点一下仍只填输入框不发 |
| 空态 | 92px 半透明圆 + 23px 字 | `CircleAvatar(primaryContainer)` + `headlineSmall`/`bodyMedium` |
| 用户消息 | 整宽气泡、accent 16% 叠色、12 圆角、带边框 | 靠右、`primaryContainer`、20 圆角（右下 6）、正文 `onPrimaryContainer` |
| 助手消息 | 整宽气泡 + 边框（与用户对称） | **不套气泡**：整宽正文（正文里还有代码块/表格/工具卡，套框就是框套框），时间用 `labelSmall` |
| 角色标签「我 / Pi」 | 11px 小灰字 | 去掉 —— 左右对齐本身已经说明是谁 |
| 工具卡 | 1px 实线框 | `surfaceContainerLow` 低层卡片、12 圆角 |
| 输入区 | 自绘 `Container` + 竖线分隔 + 带边框 TextField | `Material(surface)` + `TextField`（filled、`surfaceContainerHighest`、radius 24）+ `IconButton.filledTonal`（附件/提示词）+ `IconButton.filled`（发送） |
| 侧栏页签 | `Row` + 手画按钮 | `SegmentedButton`（整条连在一起，M3 的标准控件） |
| 设置分组 | 半透明容器 + 12 圆角 + 更小的灰字标题 | `Card`（radius 16）+ `titleSmall` + 主色标题 |
| 通用弹层 | `backgroundColor: transparent` + 手画把手 + 16 圆角 | `showDragHandle: true` + `useSafeArea`，形状交给 `bottomSheetTheme`；行用 `ListTile` |

### 判断准则（已写进 AGENTS.md）

1. 能用 M3 现成控件就别自己画：`ActionChip` / `SegmentedButton` / `ListTile` / `Card` / `IconButton.filledTonal`
   自带尺寸、涟漪、无障碍语义与主题联动；手画的版本永远会在某个模式下对不上。
2. 页面里**不写像素值**（字号、圆角、内边距）—— 要么走 `textTheme`，要么走组件主题；只有布局间距可以用常量。
3. 需要角色色（`primaryContainer` 之类）时直接取 `colorScheme`，不要往 `PiColors` 里加字段。
4. 迁移一个页面的顺序：先让它只用 `PiColors`（保证颜色跟随 M3），再把自绘控件换成 M3 控件，最后删掉自绘样式。

## 0.2.1：把 Flutter 页变成主界面（shell 模式）

0.1.x 的形态是「WebView 是 App，Flutter 是从网页版点进去的二级页」，所以无论迁了多少页，
一启动看到的第一屏永远是网页版那个侧栏 + 顶栏 —— 这也正是 0.2.0 之后用户的第一句反馈。
0.2.1 把关系倒过来：**Flutter 页盖住整屏当主界面，WebView 退到后台继续当协议引擎。**

### 启动流程

```
MainActivity(WebView 起来，加载 pideck)
  → bootstrap()：读配对 → connectAll() → 连上后 hideSplash()
  → enterFlutterShell()：shellModeEnabled() 且没开着页 ? openFlutterChatPage(当前会话) : 什么都不做
  → openFlutterPage("chat", id) 把 Flutter Activity 盖到最上面
```

于是：

- 用户看到的第一屏是原生的会话列表抽屉 + 聊天页；
- SSE、`ChatReducer`、发送链路、图床上传都还留在 WebView 里，Flutter 只画它推过来的快照，
  **协议没有第二份实现**；
- 点侧栏会话时 `openSession()` 照旧执行，`syncFlutterChat()` 把新快照推上去，Flutter 页不重建。

### 退出与兜底

| 用户动作 | 结果 |
|---|---|
| Flutter 页返回键 | `PopScope` 先收抽屉，再收选择面板，都收不掉就发 `exitApp` → Web 侧 `CapacitorApp.exitApp()` |
| 点 AppBar 的「返回网页版」 | 发 `close` → `setShellMode(false)` 写进 `localStorage`，回到网页版，**以后启动也不再弹 Flutter** |
| 某机型后台 WebView 被冻结（收不到流式帧） | 同上，退回网页版即可确认「是引擎被冻」而不是「Flutter 画错了」 |

存储键 `pideck-flutter-shell`（`"flutter"` / `"web"`），只在用户主动选网页版时才写 `web`；
`enterFlutterShell()` 打不开就自己 `setShellMode(false)`，不会卡在「两个界面都没起来」。

### 系统栏 / 手势条

`MaterialApp.builder` 包一层 `AnnotatedRegion<SystemUiOverlayStyle>`（值为 `piSystemUiOverlayStyle(dark)`），
`main()` 里 `SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge)`：

- 状态栏与底部手势条都**透明**，内容延伸到系统栏后面（不再出现一条和主题不搭的灰条）；
- 图标明暗跟着 `Theme.of(context).brightness` 走，跟随系统切换时会自动重算；
- `ContrastEnforced: false` —— 否则系统会给透明栏补一层遮罩色，又变回「不是我的主题色」。

**还有一半在 Android 主题里**：`FlutterHostActivity` 原来借用网页版的 `AppTheme.NoActionBar`，它为了让
`@capacitor/status-bar` 能控制网页版状态栏，显式 `windowOptOutEdgeToEdgeEnforcement=true` —— 于是 Flutter 页
顶上永远是一条固定深灰色带（`@color/appBackgroundDark`），Dart 侧怎么设都改不动。0.2.2 给宿主页单开了
`AppTheme.FlutterHost`（`res/values/styles.xml` 与 `res/values-v35/styles.xml` 各一份，因为同名 style 不会跨目录合并）：
不退出强制 edge-to-edge、`statusBarColor`/`navigationBarColor` 透明、`windowBackground` 兜底深色防闪白。
判断标准很直接：**状态栏色带的颜色跟 App 主题无关，就说明是 Android 主题在管，不是 Flutter 在画。**

### 判断准则

1. **壳可以换，引擎不搬**：`src/api/frames.ts` 的 reducer / SSE 解析只留一份。
2. 主界面由 Flutter 画，但**每个动作都要落回 Web 侧同一条路径**（`openFlutterChatPage`、`dispatchFlutterChatAction`，
   而不是在 Dart 里自己造发送逻辑）。
3. 任何「盖住整屏」的页面都必须处理返回键（`PopScope`），否则 Android 会直接 finish 掉整个 App。
4. 系统栏样式一处定义（`piSystemUiOverlayStyle`），不要在每个页面里各写一份 `SystemChrome.setSystemUIOverlayStyle`。

## 0.2.3：主题跟着走 + 右侧工作区抽屉

### 主题为「明明选了跟随系统，界面还是黑的」

不是 `ThemeMode.system` 没生效，是**主界面的快照里根本没有主题字段**。

Flutter 页的主题模式只从两处拿：启动时宿主缓存（`settings.theme.mode`，只有进过 Flutter 设置页才会被写）
和设置页的回调。聊天主界面把快照从 Web 推到 Dart，却从没推过主题 —— Web 版里改了「跟随系统」，
主界面不知道，就一直用缓存里的 `dark`。

修法三处：

1. `app/src/platform/flutter-chat.ts` 的 `ChatSnapshot` 带 `themeMode`（Web 侧的 `getThemeMode()`）；
2. `app/src/main.ts` 在 `onThemeChange` 里重推一次快照（主界面盖着整屏，不跟着变就是「设置改了没反应」）；
3. Dart 侧把主题模式提成全局 `ValueNotifier<ThemeMode>`（`flutter_module/lib/theme_mode.dart`），
   `main.dart` 的 `MaterialApp` 直接听它，`chat_page.dart` 的 `_applyJson` 调 `applyThemeMode(next.themeMode)`。

判断准则：**凡是「App 级」的状态（主题、语言、安全等级），都不要只存在某个页面自己的回调里**，
要么进快照，要么进全局 notifier —— 否则换一个入口改，另一个入口就看不见。

### 右侧「工作区」抽屉

Web 版这块读三样东西：Git 状态与最近提交、目录树、会话的待办历史。全部异步，且各有解析规则
（`parseGitStatus` / `parseGitLog` / `sortWorkspaceEntries`）。所以照老办法：**Web 侧取数 + 压快照，Dart 只画**。

快照形状（`app/src/platform/flutter-workspace.ts`）：

```json
{
  "open": true, "tab": "git", "title": "PiDeck", "loading": false, "empty": "",
  "branch": "master", "changedTotal": 2,
  "changes": [{ "letter": "M", "path": "app/src/main.ts", "group": "已修改" }],
  "commits": [{ "sha": "abc1234", "subject": "feat: x", "date": "11-02 18:00" }],
  "crumbs": ["PiDeck", "app"], "entries": [{ "name": "src", "path": "app/src", "dir": true }],
  "preview": { "path": "a.md", "content": "# hi", "truncated": false },
  "todos": [{ "text": "✓ 一
• 二", "status": "completed", "at": "1/2 · 18:00" }]
}
```

动作：`workspaceOpen` / `workspaceClose` / `workspaceTab` / `workspaceEnter` / `workspaceCrumb` /
`workspaceRefresh` / `workspacePreview` / `workspacePreviewClose` / `workspaceMention`。

两个刻意的取舍：

- **开合：意图 + 快照，两层**（0.2.4 修正）：用户点击 / 从右边缘滑动只写一份「意图」
  （`_workspaceIntent`），立刻按意图画，同时发 `workspaceOpen` / `workspaceClose` 给 Web；
  等某份快照的 `open` 和意图一致，意图才退休、之后完全听快照。
  最初的写法是「只按快照开合、并禁掉滑动」，结果按钮点了没反应 —— 因为
  `endDrawer` 从 null 变成非 null 并不会让抽屉自己滑出来，必须显式 `openEndDrawer()`；
  而滑动又被禁掉，用户就没有任何办法打开它。教训见 AGENTS.md。
- **文件预览在 Drawer 里就地显示**，不回 WebView：抽屉是覆盖层，跳走再回来会丢掉位置。

## 0.2.5：控件状态要跟着「输入」走，不能只在 build 时读一次

用户报的症状很具体：**输入框打完字，发送键一直是灰的，得用手指拉一下消息列表才变亮。**

根因不是按钮写错了，而是 0.1.38 为了治卡顿删掉了 `onChanged` 里的 `setState`
（那次确实是整页重建、每敲一个字卡一下）。删掉之后 `canSend` 变成「上一帧算出来的值」：

```dart
// 改前（0.2.4）：build 时读一次，输入变化不会触发重建
final bool canSend = _composer.text.trim().isNotEmpty || _images.isNotEmpty;
...
onPressed: canSend ? _send : null,
```

滚动消息列表会触发别处的 `setState`，整页重建一次，按钮就"突然"亮了 ——
这就是那个"拉一下才变"的观感。修法是把重建范围锁在按钮自己身上：

```dart
// 改后：只听 controller，输入一变只重建这一个按钮
ValueListenableBuilder<TextEditingValue>(
  valueListenable: _composer,
  builder: (context, value, _) => IconButton.filled(
    onPressed: (value.text.trim().isNotEmpty || hasImages) ? _send : null,
    ...
  ),
),
```

同一个毛病在侧栏搜索框里还有一处：`suffixIcon` 读 `_search.text` 决定要不要显示「清空」，
打完字按钮不出现，用户只能一个字一个字删。也改成 `ValueListenableBuilder`。

**判据**：`TextEditingController` / `ValueNotifier` 驱动的任何可见状态（按钮可用、清空按钮、
字数提示、面板内容），都必须在该控件的**祖先**里挂监听；写在 `build` 里读一次就是错的。

### 长按弹不出菜单：`SelectableText` 会吃掉手势

`MarkdownView` 原来用 `SelectableText.rich` 画正文，它自己处理长按（开始选词），
外层 `GestureDetector.onLongPress` 永远收不到 —— 长按助手消息弹不出菜单，
而菜单里本来就有「复制」。现在正文默认 `selectable: false`（用 `Text.rich`），
代码块仍保留 `SelectableText`（那里需要复制片段）。整条复制走长按菜单。

### 组件可用性的自动化体检

Flutter 页上的按钮不直接干活，只发一个动作名给 WebView。动作名两边各写一份字面量，
少接一个 case 的症状是**点了完全没反应**、不崩不报错、日志安静 —— 只能靠真机点。

现在有两层检查：

1. **静态接线**（`app/tests/flutter-actions.test.ts`）：扫 Dart 源码里的 `'type': '...'`、
   `_send('...')`、`_act('...')`，和 `main.ts` 三个 dispatch 函数的 `case` 集合对账 ——
   缺分支（点下去没人接）和孤儿分支（改名后留下的死代码）都会红。动态动作名
   （`question.kind`、通用转发的 `type`）登记在 `DYNAMIC_ALLOW` 里。
   顶栏 chip 的 key、Web 产出方、dispatch 分支三者也要对得上。
2. **交互行为**（`flutter_module/test/chat_components_test.dart`、`sidebar_components_test.dart`、
   `settings_components_test.dart`、`composer_send_test.dart`）：把宿主通道换成记录器，
   逐个点过去，断言「发出了哪条动作、参数对不对」。覆盖顶栏四个按钮、信息 chips、
   输入区四个键、运行中的停止键、消息长按菜单、空态提示词、侧栏页签/搜索/会话行/
   长按菜单/项目分组/底部三个按钮、设置页主题/通知/刷新/返回。

写这类测试时踩到两个坑：

- **`Scaffold.drawer` 里的 widget 即使没打开也在树里**，`find` 找得到但点不到 ——
  测试会给出假红（"点了没反应"）或者更糟的假绿。测抽屉内容就直接把 `SidebarDrawer` 当页面画。
- **`pumpAndSettle()` 在有输入框的页面上永远超时**（光标闪烁是无限动画），用固定时长的
  `pump(Duration(...))`。

## 0.2.6：重启后那页「空壳」——任务栈恢复出来的孤儿 Activity

症状：**从后台被系统回收后重新打开，看到的是一页什么都没有的原生界面**（没有会话、
标题空、侧栏全空），按返回键直接把 App 退掉，怎么都回不到主界面。

不是 shell 开关的问题，是 Android 恢复任务栈的顺序：

1. 用户停在 Flutter 聊天页（`FlutterHostActivity` 在栈顶）；
2. 进程被回收。栈的内容记在系统里，Activity 本身已经没了；
3. 重新打开 App，系统按「恢复到上次的前台页面」重建 —— **先重建栈顶那个 Flutter 页**，
   `MainActivity`（WebView，负责连设备、拿快照）反而后起。

于是 Flutter 页的宿主缓存（`lastChatJson` 这类 `static volatile`）随进程一起没了，
Dart 拿到的是 `"{}"`：空快照、空侧栏。更糟的是 `PopScope` 的返回键会发 `exitApp`，
用户按一下就整退出——没有任何出路。

修法在原生侧（`FlutterHostActivity.onCreate` 开头）：

```java
if (!FlutterLauncherPlugin.webViewAlive()) {
    // 冷启动重建：WebView 宿主不在，拉回主界面再 finish 自己
    Intent main = new Intent(this, MainActivity.class);
    main.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    startActivity(main);
    finish();
    return;
}
```

`webViewAlive()` 就是 `FlutterLauncherPlugin.instance != null` —— 插件由 Capacitor 在
`MainActivity` 建桥时 `load()`，主界面活着它就在。别用 `getApplication() == null` 之类绕远路。

顺带两条清单属性（`AndroidManifest.xml` 的 `FlutterHostActivity`）：
`android:launchMode="singleTop"`（连点两次不要再叠一层）、
`android:excludeFromRecents="true"`（它不该单独出现在最近任务里）。

### 同一个症状还有第二个原因：一次失败就把开关永久关掉

`enterFlutterShell()` 早先写的是 `if (!opened) setShellMode(false)` —— 意思是
「打不开原生页就退回网页版」，但 `setShellMode` 是**写 localStorage 的持久开关**。
一旦某次启动偶发失败（Activity 还没就绪、启动瞬间被切后台），之后每次启动都不再进原生界面，
用户看到的就是「重启 App 不显示主界面」，而且没有任何入口能改回来。

现在：失败只 `console.warn` 留在网页版，**不写存储**。同时设置页多了「界面 → 用原生界面做主界面」
开关（`SettingsData.shell.native`、动作 `shell`），两边都能改 —— 被卡住时用户有自救入口。

配套一处细节：`openSessionFromSidebar()` 的条件要把 `!shellModeEnabled()` 也算进去，
否则用户刚关掉原生主界面，点侧栏会话又被弹进 Flutter 页。

## 0.2.7：启动钻进旧会话、进页闪网页版、抽屉永远转圈

三个都是「顺序」问题，不是组件写错。

### 1. 冷启动自己打开了一个旧会话

`enterFlutterShell()` 原来写的是 `activeSessionId ?? sessions[0]?.id` —— 冷启动时前者是
`null`，于是退到「列表里第一个会话」。用户开机看到的是一个早就结束的对话，还以为
「主界面没了」。

改成 `activeSessionId ?? ""`：**只有当前确实选中的会话才恢复**。空字符串表示「主界面」，
会话列表在抽屉里，点哪个进哪个。空态副标题也跟着分叉（`emptySubtitle()`）：
有会话但没选中时说「点左上角菜单选择会话」，一个会话都没有才继续念新用户话术。

### 2. 从网页版点会话，先闪一下网页版再进原生页

`openFlutterChatPage()` 原来是：

```ts
if (sessionId !== "" && activeSessionId !== sessionId) await openSession(sessionId); // ← 先渲染网页版
... await openFlutterPage("chat", sessionId);                                        // ← 再盖上原生页
```

`openSession()` 会把网页版的侧栏和聊天区整个画一遍（几百毫秒），用户看到的就是那一下闪。

反过来了：先把 mirror 建好并推一版当前快照 → `await openFlutterPage()` 盖住整屏 →
**再**在背后 `await openSession()`。这一步用户看不见，快照会通过 mirror 流式推过去。

### 3. 右侧抽屉「一直在加载」

两个来源：

- 桌面端接口不返回时没有兜底。现在 `withDeadline()` 给 git / 目录请求加了 12s 上限，
  超时按失败处理（`读取超时（桌面端没有响应）`），绝不会停在 `loading: true`。
- 推送丢一次就一直转。Dart 侧加 `_kickWorkspaceWatchdog()`：抽屉开着且快照仍是
  `loading` 时每 4s 补一次 `workspaceRefresh`，最多 3 次；拿到 `loading: false` 就清零。

顺带两处：
- 默认页从「改动」改成**文件**（`emptyWorkspaceSnapshot().tab`、`flutterWorkspace.tab`、
  `WorkspaceSnapshot.tab` 三处都要改，漏一处就会出现「Dart 画文件页、Web 以为在改动页」）。
- 内置 Chat 项目没有目录，别拿空路径去问桌面端要文件列表（那边会挂住）：
  `workspaceProjectPath() === "" && tab !== "todos"` 时直接给「这个项目没有目录」。
  `workspaceProjectId()` 也不再退回 Chat 项目，优先挑第一个真有路径的项目。

## 0.2.8：点了没反应的三种写法

真机反馈两条：「右侧栏右上角的 X 关不掉」「左侧栏切换会话没效果、也不显示最新消息」。
三条独立原因，全是**本地状态和快照对不上**这一类的，写下来当判据。

### 1. 关闭按钮只发动作，没改本地意图

抽屉的开关是「本地意图 + Web 快照」两层（0.2.4）。X 按钮当时只做了第一层的一半：

```dart
onPressed: () => onAction({'type': 'workspaceClose'})   // 只发动作
```

`_workspaceIntent` 还停在打开时写下的 `true`，而 `_syncWorkspaceDrawer()` 是
`want = _workspaceIntent ?? _workspaceOpen` —— 快照回来说 `open: false` 反倒跟意图打架，
于是 post-frame 又 `openEndDrawer()`。**凡是能改变抽屉开合的入口，都必须走 `_setWorkspaceOpen()`**
（它同时写意图、发动作、开合），`WorkspaceDrawer` 因此加了 `onClose` 回调。

### 2. `sessionId` 为空被当成「坏数据」丢掉

`_applyJson()` 原本第一件事是：

```dart
if (next.sessionId.isEmpty) return;   // ✗
```

空 `sessionId` 是**合法状态**——还没选会话的主界面就是它。结果主界面那份快照被整份丢掉，
页面停在上一次收到的内容上：切会话、回主界面、改标题全都不刷新。
现在只保留「JSON 解析失败」这一层兜底，换会话时额外做两件事：滚动锚点掰回贴底（`_stick = true`）、
清掉历史项的 `GlobalKey`。否则会沿用上一个会话的滚动偏移，用户看到一堆旧消息。

### 3. 等 Web 侧往返的那几百毫秒，页面还是旧会话

点侧栏会话 → `openSession` → 请求历史 → 推快照，中间是两次往返。这段时间页面没变化，
用户判定「没点中」就再点一次。Dart 侧现在先本地切（`ChatSnapshot.withSession(id, title)`：
换 id/标题、清内容、置 `loading`），真实快照到了直接覆盖。
侧栏行的动作因此多带一个 `title`（Web 侧只认 `id`，多一个键不影响分发）。

### 顺手修掉的第四处

首屏空态的显示条件原来是 `bare && snapshot.suggestions.isNotEmpty` —— 没提示词的项目直接一片空白。
改成 `bare && !snapshot.loading`。

### 测试

- `flutter_module/test/chat_session_switch_test.dart`（3 项）：空 `sessionId` 的快照也要渲染 /
  切会话后列表换成新会话的最新消息 / 点侧栏会话页面立刻切过去。
- `flutter_module/test/workspace_drawer_test.dart` 新增 1 项：点 X 之后抽屉不会回来。

## 0.2.9：关抽屉把页面关没了（黑屏），以及「切过去停在中间」

两个问题都不大，但都属于「看起来像玄学」的那一类，根子都在这句：**抽屉是挂在当前路由上的一段
local history，不是一层可以随便插拔的 widget。**

### 一、点 ✕ 黑屏

0.2.4 为了让「快照说关着就别弹」，把抽屉写成了条件挂载：

```dart
endDrawer: workspaceOpen ? WorkspaceDrawer(...) : null,
```

看着干净，实际上有两条路会打架：

1. 用户点 ✕ → `_setWorkspaceOpen(false)` 先 `setState`，下一帧 build 时 `workspaceOpen` 已经是
   false，**抽屉 widget 直接从树里消失**，但它压进路由的那条 local history 条目还在；
2. 关抽屉的老写法是 `Navigator.of(context).pop()` —— pop 的是「路由栈顶的那一条」，条目还在时关抽屉，
   条目不在了就把**整页路由**弹掉。于是：黑屏（路由没了，Scaffold 也没了），再按返回键把
   Activity 收掉，露出底下的网页版。

`Scaffold.isEndDrawerOpen` 在 widget 被摘掉之后是 `false`，两个 post-frame 回调谁先谁后完全看
这一帧里快照什么时候到（80ms 一次），所以症状是「有时候关得掉、有时候黑屏」。

**修法（两条一起，缺一不可）**

- 抽屉**常挂**：`endDrawer` 永远给 `WorkspaceDrawer`，没数据就喂一个 `loading: true` 的占位快照
  （反正关着的时候看不见）。开合只表达在「开/关」的动作上，不表达在「挂不挂」上。
- 关闭走 `ScaffoldState.closeEndDrawer()`，**永远不要** `Navigator.pop()`。前者是幂等的接口，
  后者是「弹路由栈」，两者的语义差着一条历史记录。

回归测试在 `flutter_module/test/workspace_drawer_test.dart`：点 ✕ 之后断言 `isEndDrawerOpen` 为
false、`ChatScreen` 还在、并且通过 mock 通道收上来的动作里**没有 `exitApp`**（老实现弹到空路由时
会走 PopScope 的兜底退出）。

### 二、切会话停在中间

`_stick`（是否贴底）在切会话时没重置，`_afterLayout()` 又只在 `_stick` 为 true 时才跳。更细的一条：
就算跳了，`jumpTo(maxScrollExtent)` 落到的是**列表最底**，而列表最后一项是 footer（加载中、错误卡、
提问卡、token 统计），footer 高的会话里看起来就是「停在页尾空白处」。

现在切会话走专门的 `_scrollToLatest()`：先 `jumpTo(maxScrollExtent)` 把最后一条建出来（懒构建列表
不建就拿不到 `RenderBox`），下一帧再 `Scrollable.ensureVisible(lastKey.currentContext, alignment: 1)`
—— 把**最新那条消息**的底边对齐视口底边，footer 自然被挤到看不见的地方。

### 记进 AGENTS.md 的两条

- 抽屉 / 弹层这类「挂在路由上的东西」，不要用「挂不挂」表达开关状态，也不要拿 `Navigator.pop()` 当关闭；
- 切会话要显式重置滚动锚点，且「贴底」不等于「停在最新一条」。

## 逐页迁移顺序（建议）

按「依赖原生插件少 → 多」排，迁一页就把入口切过去，随时能停：

1. ~~**设置页**（`ui/settings-view.ts`）~~ —— **0.1.21 已完成**，见上一节。后续迁别的页可以照抄那套：
   数据留在 WebView 组装，Flutter 只画 + 回传动作 id。
2. ~~**会话列表 / 抽屉**（`ui/sidebar-view.ts`）~~ —— **决定不单独迁**（0.1.23 定案）。
   理由：侧栏是 WebView 里的抽屉，而 Flutter 是独立 Activity，嵌不进去。单独迁只能做成一个全屏页，
   代价是丢掉滑动手势与长按菜单（菜单本身在 WebView 的 DOM 弹层里），而它在最终形态里本来就不该
   独立存在 —— 聊天页迁到 Flutter 后，侧栏是那个页面的 `Drawer`。所以**并进第 3 条一起做**。
   届时要做的事（已和用户确认）：
   - 数据：把 `sidebar-model.ts` 的 `ProjectGroupView` / `FlatSectionRow` 加一层 JSON 序列化，
     照 `settingsData()` 的做法整份推给 Dart（侧栏数据全是 HTTP，无原生依赖，这部分最容易）。
   - 长按菜单：**在 Flutter 里用底部弹层重做**（重命名 / 复制 / 删除 / 后端切换…），
     不要“关掉 Flutter 页回 WebView 弹原菜单”那条路（会闪一下，而且菜单状态在两边）。
     重做的代价是菜单内容要维护两处 —— 做的时候把菜单项也抽成数据（`{id, label, danger}`），
     让 Web 版和 Dart 版都从同一份数据渲染。
3. **聊天页**：**0.1.27 已打通「视图迁移」链路**，**0.1.28 补齐侧栏 `Drawer`**，
   **0.1.29 补齐长按消息菜单 + 会话点击直达**（见上两节）。剩下的是打磨。
   流式仍由原生 `SsePlugin` 读、WebView 的 `ChatReducer` 算，Dart 只渲染（0.1.26 定案）。
4. ~~**文件保存 / 选图 / 扫码**~~ —— **0.1.30 已完成**，但做法和当初的设想不一样：没有引 `file_picker` /
   `mobile_scanner` 两个 pub 包，而是把 `SavedFiles` / `FlutterFiles` 抽成原生共用实现（详见上一节）。
   理由：压缩参数与落盘规则已经是 `image-attach-model.ts` / `FileSaverPlugin` 里的产品决策，
   引 pub 包等于把它们重实现一遍；而扫码的 `BarcodeScanner.scan()` 本来就得靠 Capacitor 的
   bridge 接 ActivityResult，在 Flutter 宿主的 Activity 里拿不到结果，索性回 WebView 开面板。

5. ~~**顶栏 + 输入框附属面板**~~ —— **0.1.31 已完成**（见上一节）：顶栏 chips、上下文用量、
   模型 / 思考 / 安全 / 提示词面板、排队与插话切换都进了 Flutter。

6. ~~**看图 / 斜杠 / @提及**~~ —— **0.1.32 已完成**。
7. ~~**历史跳转 / 待办展开 / 空态建议**~~ —— **0.1.33 已完成**。
8. ~~**项目长按菜单**~~ —— **0.1.34 已完成**。
9. ~~**通用底部弹层：新建会话 / 切换后端 / DSH 工具**~~ —— **0.1.35 已完成**（见上一节）。
10. ~~**右侧工作区抽屉**~~ —— **0.2.3 已完成**（Git 改动 / 文件浏览 / 待办历史）。
   至此 Flutter 页里点得到的东西**没有一处**需要闪回网页版，除了扫码配对与子代理历史这两条
   「页面级 / 长文本」的例外。

**剩下没迁的**：
启动页 `splash.ts`、以及十来个 sheet（连接、端点、主题、安全、DSH 工具、用量、工作区选择器、
后端、重命名、发送历史、上下文）、扫码覆盖层 `scan-overlay.ts`、文件引用/下载、
`acp-notice.ts`、`run-completion.ts`。
这些都不在主路径上：点它们会 `leaveFlutterThen()` 回 WebView 开原生弹层，行为一致，只是闪一下。

各插件对应关系：

| Capacitor 插件 | Flutter 等价 |
|---|---|
| `SsePlugin` | 不换：原生读流，过桥给 Dart 渲染（探针实测后台 0 帧，见上） |
| `NavigationBarPlugin` | `SystemChrome.setSystemUIOverlayStyle(SystemUiOverlayStyle(systemNavigationBarColor: ..., systemNavigationBarIconBrightness: ...))` |
| `FileSaverPlugin` | 已解决：抽出 `SavedFiles` 共用，Flutter 通道直接调（0.1.30） |
| `RunWatchPlugin` | `flutter_local_notifications` + 自写 MethodChannel；前台服务那段 Java 建议直接复用 |

**复用现成 Java 代码是允许的**：`MainActivity` 里已注册的插件都是普通 Capacitor 类，
想在 Flutter 侧用同一份能力，写个 `MethodChannel` 转发过去比重写更快，也更不容易出现两套行为。

## 验证清单

> 2026-11-02：用户反馈「都验证过了」，0.1.21–0.1.35 的真机条目全部勾掉。
> 以后新加的条目仍然要到真机上点过才勾 —— 这份清单的意义是「有人点过」，不是「代码写完了」。

- [x] `cd app && npm run apk:release` 出包成功，签名校验通过（`apksigner verify`）
- [x] release 包 43 MB，`lib/*/libflutter.so` 已 strip（11.8 MB / 8.6 MB）
- [x] WebView 侧流程未受影响：627 个前端单测 + 14 项 `verify:send` 全绿
- [x] `flutter analyze` 无 issue，`flutter test` 通过（`settings_models_test.dart` 4 项：完整解析 / 缺字段宽松 / `sseNative` 缺省 / `maskToken`）
- [x] 真机：进 Flutter 页期间 RunWatch 通知判定仍然正确（0.1.20 修：`isAppInBackground` 单测覆盖 4 种组合）
- [x] 真机：点齿轮直接进 Flutter 设置页，主题 / 通知 / 设备 / 概况 / 技能 / 扩展 / DSH 七个分区都能显示（0.1.21）
- [x] 真机：改主题、关通知、切设备、刷概况、开关技能/扩展、DSH 运行/停止、DSH 安装（含错误 ID 校验）均生效且提示正常
- [x] `flutter test` 59 项（`chat_models_test.dart` 覆盖顶栏 chips / 上下文 / 待办 / 子代理 / 选择面板解析）
- [x] `app/tests/flutter-chat-header.test.ts` 13 项（chip 顺序、能力收敛、上下文短标签、三种面板的组装）
- [x] 真机：顶栏 chip 点开模型 / 思考 / 安全面板，选完 chip 文案立刻变（0.1.31）
- [x] 真机：上下文 chip 显示百分比，点开能看到明细并触发「压缩上下文」
- [x] 真机：ACP 会话顶栏只剩标题（没有模型 / 思考 / 计划 chip）
- [x] 真机：返回键与页内返回都能回 WebView，且回来后会话列表/聊天页状态未丢
- [x] `flutter test` 40 项全绿（新增 `chat_models_test.dart` 12 项、`markdown_test.dart` 11 项）
- [x] 聊天桥的纯逻辑：`app/tests/flutter-chat.test.ts` 15 项（历史条目转换 / thinking 剥离 / 工具状态 / 快照组装 / 40ms 合并 / 去重）
- [x] 真机：设置页 → 关于 → 聊天页预览，历史消息与流式增量都能显示，滚动贴底不跳（0.1.27）
- [x] 真机：聊天页里发一条消息 → WebView 侧收到并真的发出去，回复流式显示在这页上
- [x] 真机：运行中按停止能停下来
- [x] `flutter test` 48 项全绿（`chat_models_test.dart` 新增 2 项长按菜单解析）
- [x] 长按菜单的数据来源：`app/tests/flutter-chat.test.ts` 新增 3 项（pi 的 user 5 项 / assistant 2 项、ACP 只剩复制、不传 backend 按 pi）
- [x] 真机：长按一条提问 → 菜单五项齐全、删除标红并二次确认、编辑能改内容后重发（0.1.29）
- [x] 真机：长按一条回答 → 只有「复制文本 / 分叉会话」两项（ACP 会话只有「复制文本」）
- [x] 真机：点侧栏里的会话 → 直接进 Flutter 聊天页且是该会话；点「返回网页版」后再点侧栏会话不再弹 Flutter 页
- [x] `flutter test` 56 项全绿（`host_channel_test.dart` 新增 8 项：选图解析 / 取消 / 报错、`PickedImage.fromJson` 兜底、`saveTextFile` 的 base64 与 mime、`openWebSheet` 只传名字）
- [x] 真机：聊天页点「+」选一张竖拍照片 → 缩略图不躺倒、发出后 WebView 侧缩略图与消息里都正常（0.1.30）
- [x] 真机：只选图不写字直接发 → 服务端收到「看下这张图」+ 图片
- [x] 真机：代码块点「保存到手机」→ 通知栏/文件管理器里 `下载/PiDeck/snippet-*.txt` 内容完整；工具卡同理
- [x] 真机：设置页「扫码配对 / 连接设置」→ 回到 WebView 并弹出连接面板，扫码能扫
- [x] `flutter test` 81 项全绿（新增 `image_viewer_test.dart` 9 项、`composer_insert_test.dart` 13 项）
- [x] `app/tests/flutter-chat-header.test.ts` 15 项（新增 insert 随 JSON 过去、模型条目不带 insert）
- [x] 真机：聊天里点图片 → 全屏看、双指缩放、左右甩翻页、点空白或 ✕ 关闭（0.1.32）
- [x] 真机：输入框打 `/` → 出斜线命令面板，选中后命令文本进输入框且**只有一个斜线**（0.1.32）
- [x] 真机：输入框打 `@` → 出文件面板，点目录能往下钻、`..` 回到上一级、选中文件插入 `@路径 `
- [x] 真机：@ 面板里打字搜索时仍停在当前目录（不会跳回项目根）
- [x] 真机：「+」旁提示词按钮 → 选一项后模板名进输入框

## 0.2.10：聊天数据面下沉到 Dart（WebView 不再是唯一的帧流主人）

### 问题

0.2.1 之后聊天页是 Flutter 画的，但**帧流、历史、待答请求全在 WebView 里**：`src/api/frames.ts`
的 reducer 跑在 WebView 的 JS 里，Flutter 只是把 `ChatSnapshot` 画出来。这在 App 被切到后台时
会露馅 —— WebView 的 JS 定时器被系统压缩，帧到了也推不出来，回到前台看到的是「停在最后一条」，
再等一轮快照才补齐。

### 做了什么

把**读侧**（帧流 / 历史 / 待答请求）搬到 Dart，写侧（`send` / `stop` / `respond`）和控制面
（顶栏 chips、长按菜单、选择面板、工作区、主题）继续留在 WebView：

```
        ┌──────────────── Flutter ────────────────┐
        │ ChatRuntime（帧流 / 历史 / pending）     │  ← 新：数据面
        │ ChatSnapshot.fromJson ← 合并 ← WebView  │  ← 旧：控制面
        └────────────────────────────────────────┘
```

- `flutter_module/lib/runtime/sse.dart`：`sse.ts` 的逐行移植（`\r\n|\r|\n`、末尾孤立 `\r` 等下一分片、
  注释行、多行 data、`id` 跨事件保留、BOM、`flush()` 残留）。
- `flutter_module/lib/runtime/frames.dart`：`ChatReducer` 的移植（含 `blocksToPlainText`、`markAborted`、
  新增的 `clearLive()`），块的 JSON 形状与 TS 一字不差。
- `flutter_module/lib/runtime/api_client.dart`：`dart:io` 版客户端。GET 走 `?token=`、非 GET 走
  `Authorization: Bearer`（与 `client.ts` 同一套规则，避开 CORS 预检那条历史原因在原生侧已经不需要，
  但保持同形是为了两端行为一致）；401→auth、409→runtime；`streamSse` 用独立的 `HttpClient`，
  取消就是 `close(force: true)`。
- `flutter_module/lib/runtime/history_model.dart`：`historyItemsOf` / `messageText` / `normalizeRole` /
  `splitThinking` / `collectImageSources` 的移植，输出与 TS **逐字节相同**（用 node 跑 TS 版对 12 组
  fixtures 对拍过）。
- `flutter_module/lib/runtime/chat_runtime.dart`：把上面几件拼成一个 `ChangeNotifier`：帧循环（50s 无事件
  即当假死收流重连）、状态循环（`/api/events`）、`settle`（finish 后 400ms 重拉历史再清流式块，
  对齐 Web 的 `settleRun`）、`notifyPromptSent()`（发出去 650ms 后重拉，让用户那条消息尽快出现）。

### 怎么接起来

- 宿主把**当前活动端点**经 intent extra 传进来：`pideckFlutterEndpoint` / `pideckFlutterToken` /
  `pideckFlutterNativeRuntime`（`FlutterLauncherPlugin.open` → `FlutterHostActivity.buildArgs` →
  Dart `loadHostArgs()`）。**不要**改用 `getSettings()` 里那份 connection：它只在打开设置页时推，
  换过端点就是旧的。
- `chat_page.dart` 同时持有两份快照：`_webJson`（WebView 推的完整快照）和 `_runtime.snapshotJson()`
  （Dart 的数据面），合并规则 `{..._webJson, ...runtime.snapshotJson()}` —— **Dart 赢冲突键**。
- 只有 `runtime.historyLoaded == true`（成功拉到过历史）才让 Dart 那份生效。连不上服务端时它全是空值，
  盖上去就是白屏，那种情况必须退回 WebView 那份。
- 回退开关：`localStorage["pideck-native-chat"] = "off"` 之后 `openFlutterChatPage()` 不再传
  `nativeRuntime`，页面回到「只画 WebView 快照」的老路径。

### 为什么写侧不搬

Web 的 `send()` 里还有计划模式注入（`buildPlanSubmission`）、排队 / 插话（`streamingBehavior`）、
todo 归档、无会话自动建会话、`appendLocalUser` 本地回显。搬到 Dart 就是两个真相源，而稳定性痛点
只在读侧 —— 后台被挂起时缺的是帧和重连，不是「谁去 POST /prompt」。

### 两个坑

- `app/tests/flutter-actions.test.ts` 会抓 Dart 里所有 `'type': <字符串字面量>` 当成聊天动作名，
  它分不清「帧类型」和「动作名」。所以 `[DONE]` → finish 帧走 `api_client.dart` 的 `doneFrame()`
  逐键赋值，**别在注释或代码里写出那个字面量**，否则测试会报「Dart 发了个叫 finish 的动作」。
- 同一会话现在有两条 SSE 连接（WebView 的 `SsePlugin` + Dart 的 `HttpClient`）。会话流连上会整段回放，
  两条都吃一次；这是有意的 —— 读侧完全独立，才可能在 WebView 被挂起时还在收帧。

### 测试

- `flutter_module/test/runtime_sse_test.dart`（11 项，与 `tests/sse.test.ts` 同口径）
- `flutter_module/test/runtime_frames_test.dart`（12 项，含块 JSON 形状与 `clearLive`）
- `flutter_module/test/runtime_history_test.dart`（11 项，与 TS 版对拍）

### 0.2.11：`stop` / `respond` 也交给 Dart

0.2.10 只搬了读侧，`send` / `stop` / `respond` 三个动作仍然回传 WebView。其中「停止」和「回答提问卡」
是最不该绕路的两个：按钮就画在 Flutter 页上，用户在等着它立刻生效，而回传一次要等 WebView 那边的
JS 被唤醒（`MainActivity` 已被 Flutter 页 pause）。

现在 `chat_page.dart` 的 `_stop()` / `_respond()` 先走 `ChatRuntime.stop()` / `.respond()`：

- `stop()`：`abort` + `target`，409（运行时换代）时刷一次 `/api/state` 再重发，成功后 `reducer.markAborted()`；
- `respond()`：把界面传回来的 label 映射回 option 的 `value` 再 `POST /api/ui-response`（body 平铺，
  **不是** `{target: ...}` 包一层），发完把本地 `pending` 清掉。

两条路都要过两关才切到 Dart：运行时存在、`historyLoaded` 为真；`_respond()` 还要求运行时手里的
`pending.requestId` 与页面上的是同一条（否则可能答的是上一条）。任一不满足就退回原来的
`sendChatAction({'type': 'stop'|'respond'})`，WebView 那边照旧能处理。

`send` 没动：计划模式注入、排队/插话、todo 归档、无会话自动建会话都在 `main.ts` 的 `send()` 里。

### 0.2.12：`send` 与状态流 / 侧栏快照也交给 Dart

0.2.11 之后聊天页上还剩三件事要绕回 WebView：发消息、整份环境状态（`/api/events`）、侧栏列表。
它们的共同点是「WebView 被盖住时就没有人算」——用户从抽屉点会话、从项目里新建会话、或者只是
切回来看到侧栏还停在十分钟前的样子，都是从这条路上冒出来的。这一版把三条一起搬进 Dart。

**迁移的边界没有变**：Dart 只产数据面字段，控制面（`messageMenus` / `header` / `picker` /
`suggestions` / `sheet` / `workspace` / `themeMode`）仍由 WebView 那份快照提供。合并照旧
`{..._webJson, ...runtime.snapshotJson()}`，唯一的例外是 `header.running`：顶栏是 WebView 算的，
它被挂起时那一格会停在旧值，所以合并后按运行时的 `status` 覆盖（否则该显示「停止」的时候显示「发送」）。

#### 1. 发送：`ChatRuntime.submit()`

逐条对应 `main.ts` 的 `send()`，顺序也照抄：只发图时 message 用 `看下这张图`（`/prompt` 的 message
不能为空）→ `buildPlanSubmission()` 注入计划模式（一次性，发完要通知 WebView 把 chip 关掉）→
`streamingBehaviorFor()`（只在运行中带，ACP 不支持插话时降级 `queue`）→ 乐观回显入 `_optimistic`
→ `POST /prompt` → 失败按 `shouldRollbackRejection()` 决定收不收气泡。

三个必须照抄的细节：

- **`agentMessage` 是平铺的**。`client.sendPrompt` 把 `extra` 展开进 body 顶层
  （`src/api/client.ts:559-572`），所以 Dart 侧写成 `{...extra}` 而不是 `{extra: {...}}`。
  套一层服务端读不到，症状是计划模式静默失效——消息照发，但没有那条隐藏指令。
- **发出到第一帧 `start` 之间要按「运行中」画**（`_awaitingTurn`）。这一段通常是 100–300ms，
  按 `idle` 画会让发送键闪回可点、状态行闪一下「空闲」。
- **乐观回显靠历史对账清，不是发送成功就删**。650ms 后重拉一次 `/messages`，最近三条里有同文本的
  user 消息才把 `_optimistic` 摘掉；服务端回放慢的时候提前删会看到气泡消失再出现。

#### 2. 状态流：`runtime/state_store.dart`

`src/state/store.ts` 的逐条移植，接口一致：先 `refresh()` 对账（`/api/state` + ETag）再进
`/api/events`，只吃 `event === "state"`；auth 错误停（`authRequired`）；协议不支持或连续失败 3 次
降轮询（`pollMs`），另外 `onOpen` 时挂一条 `fallbackPollMs` 兜底轮询；重连退避 1s→15s。

新增一个门禁 `store.ready`（拿到过至少一份完整状态）。它比「state 非空」严格：`normalizeStateJson()`
对坏数据会造一份空状态，空不等于没数据。侧栏和标题都要过这道门，否则第一次启动会先画一帧空列表。

#### 3. 侧栏快照：`sidebar/sidebar_native.dart`

`buildSidebarSnapshot()` 是 `app/src/platform/flutter-sidebar.ts` 的逐字节移植（1050 行、零 import），
33 个用例里有 23 组是 node 直接跑 TS 版生成 `JSON.stringify` 真值、Dart 逐字节对拍。移植时踩到的
「JS 与 Dart 不一样」全部写在文件头注释里，其中最容易复发的三条：

- `||` 与 `??` 不同：`title || "未命名会话"` 空串也要替换，`projectLabel ?? ""` 空串要保留；
- **键序就是字节契约**：可选项 falsy 时整个键要消失，不能留 `null`；
- JS 的 `sort` 自 ES2019 起稳定，Dart 的 `List.sort` 不稳定 —— 用下标兜底。

用户意图（页签 / 搜索 / 折叠 / 展开）落在 `sidebar/drawer_state.dart`：点一下先本地改再发 WebView，
不等回传。只在 `runtime.store.ready && _webSidebar.deviceCount <= 1` 时接管：多设备聚合要每台设备的
`state`，Dart 只连着自己那台，硬算会漏设备 —— 这种情况直接回退 WebView 那份。弹层（新建会话 / 切后端）
仍由 WebView 开，本地只负责列表。

#### 4. 后台看护：Dart 自己拉起来

`ChatRuntime.submit()` 成功后会经 `host_channel.startRunWatch()` 调宿主的 `RunWatchService.start()`。
以前是 WebView 收到 `running` 帧才起前台服务，而用户发完常常直接上滑回桌面——进程被冻结之后
那一轮跑完就没有通知了，Android 12+ 也不允许后台再启动前台服务。

#### 测试

- `flutter_module/test/runtime_prompt_test.dart`（11 项：计划模式隐藏消息逐字符、`streamingBehavior`、
  拒收模板与回滚判定）
- `flutter_module/test/runtime_submit_test.dart`（5 项，真开 `HttpServer`：发送体、乐观回显、
  `agentMessage` 平铺、运行中 `followUp`、两种拒收）
- `flutter_module/test/sidebar_native_test.dart`（33 项，含 23 组 TS 真值字节对拍）
- `flutter_module/test/drawer_state_test.dart`（7 项：意图层 + 喂 `buildSidebarSnapshot()` 端到端）

回退开关没变：`localStorage["pideck-native-chat"] = "off"` 之后聊天页只画 WebView 快照，
发送 / 状态流 / 侧栏全部回到老路径。

### 0.2.13：工作区数据与侧栏数据动作交给 Dart

两块的共同点：**只是「打接口 + 排版」**，没有 WebView 的 DOM 参与，留在那边唯一的效果是
用户点了之后要等一个被盖住的页面被唤醒。

工作区（`workspace/workspace_runtime.dart` + `workspace/workspace_native.dart`）：

- `git` / `files` 两个页签、文件预览、面包屑全部由 Dart 直接打接口：
  `GET /api/git/status`、`GET /api/git/log?limit=20`、`GET /api/files?projectId&dir`、
  `GET /api/file-content?projectId&path`。
- `todos` 页签仍由 WebView 算：待办历史的持久化就在 WebView 的 localStorage
  （`todo-history-model.ts`），搬过去等于另起一套存储，收益低、风险高。
- 门禁是 `store.ready && WorkspaceRuntime.tab != 'todos'`；看**自己那份 tab**，
  因为快照里的 tab 会晚一拍。
- 12s 超时（`_deadline`）沿用 Web 的 `withDeadline`，超时文案也是同一句
  「…超时（桌面端没有响应）」；过期结果用 `_generation` 丢弃，避免快速点页签时旧结果覆盖新页。
- 两个容易抄错的点：`workspaceEnter` / `workspaceCrumb` 的 `depth` 是「保留几段 stack」
  （0 = 回到项目根），不是面包屑下标；`/api/files` 的路径参数名是 `dir`，传 `path` 会被静默忽略。

侧栏数据动作（`sidebar/session_actions.dart`）：

- 接手 `sessionRename` / `sessionCopy` / `sessionDelete`（仅非当前会话）/ `projectRemove` /
  `createSession`：Dart 调接口 → `store.refresh()` → 提示一句 → **带 `native: true` 回传**。
- `dispatchFlutterSidebarAction` 里那条 native 分支只做「刷新 + 重画 + （新建时）打开会话」，
  **不再调接口**。一个动作只有一次副作用；重复执行的表现是重命名两次、删两次、建出两个会话。
- 删当前会话不接手：要清 `activeSessionId`、关 channel、把页面退回空态，那是 WebView 的活。
- 会话导出（`sessionExport`）与切后端（`sessionBackend`）仍在 WebView：前者要落文件（MediaStore
  路径在原生侧但流程在 `exportSessionHtml`），后者要开 WebView 的弹层。

测试：

- `flutter_module/test/workspace_native_test.dart`（17 项，期望值由 node 直接跑 TS 生成）
- `flutter_module/test/workspace_runtime_test.dart`（7 项，真开 HttpServer）
- `flutter_module/test/session_actions_test.dart`（8 项：调了哪个接口、body 长什么样、
  回传带没带 `native`、删当前会话时是否让位给 WebView）

顺带修掉一个测试基建的坑：假 SSE 服务端必须 `response.bufferOutput = false`。
Dart 的 `HttpResponse` 默认把 body 攒到 close 才发，而流式连接永远不 close ——
不关缓冲的话客户端一个字节都收不到，症状是 `store.ready` 永远为假、测试 30s 超时。
