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
| `flutter_module/lib/main.dart` | 入口：按 `route` 分派页面（`settings` → 设置页，其余落到占位页） |
| `flutter_module/lib/host_channel.dart` | `pideck/flutter_host` 通道的 Dart 封装：`getArgs` / `getSettings` / `settingsAction` / `close`，以及订阅 `setSettings` / `showNotice` |
| `flutter_module/lib/settings/` | 设置页：`settings_models.dart`（JSON → 模型，有单测）+ `settings_page.dart`（纯 UI） |
| `flutter_module/.android/` | Flutter 生成的 Android 宿主工程。**必须提交**（`include_flutter.groovy` 在里面），只忽略 `local.properties` 与 `build/` |
| `app/android/settings.gradle` | `evaluate(include_flutter.groovy)`，把模块暴露成 Gradle 项目 `:flutter` |
| `app/android/app/build.gradle` | `implementation project(':flutter')` |
| `dev/pideck/mobile/FlutterHostActivity.java` | 承载 Flutter 页的 Activity（`FlutterFragmentActivity`），提供 `MethodChannel("pideck/flutter_host")`：`getArgs` / `getSettings` / `settingsAction` / `close`，反向 `setSettings` / `showNotice` |
| `dev/pideck/mobile/FlutterLauncherPlugin.java` | Capacitor 插件 `FlutterHost`：`open` / `available` / `pushSettings` / `pushNotice` / `closePage`，事件 `pageVisibility` / `settingsAction` |
| `dev/pideck/mobile/PiDeckApp.java` | 自定义 Application，用 Activity 计数维护 `isAppVisible()`（见下） |
| `app/src/platform/flutter-host.ts` | 前端封装：`isFlutterHostAvailable` / `openFlutterPage` / `pushFlutterSettings` / `pushFlutterNotice` / `closeFlutterPage` / `onFlutterPageVisibility` / `onFlutterSettingsAction`。老包/浏览器环境静默返回 false / no-op |

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

## 逐页迁移顺序（建议）

按「依赖原生插件少 → 多」排，迁一页就把入口切过去，随时能停：

1. ~~**设置页**（`ui/settings-view.ts`）~~ —— **0.1.21 已完成**，见上一节。后续迁别的页可以照抄那套：
   数据留在 WebView 组装，Flutter 只画 + 回传动作 id。
2. **会话列表 / 抽屉**（`ui/sidebar-view.ts`）：数据全是 HTTP，无原生依赖。
3. **聊天页**：最难，卡在 SSE。`SsePlugin` 现在用 `HttpURLConnection` 读流，搬到 Dart 就是
   `HttpClient().getUrl(...).then((r) => r.transform(utf8.decoder).transform(LineSplitter()))`，
   逻辑几乎 1:1；难点是 `RunWatchService` 的通知联动与流式滚动锚点（`ui/scroll-anchor.ts`）。
4. **文件保存 / 扫码**：`file_picker` + `mobile_scanner` 两个 pub 包，`FileSaverPlugin` 里
   MediaStore 的那套要换成 `path_provider` + `share_plus`，行为差异最大，放最后。

各插件对应关系：

| Capacitor 插件 | Flutter 等价 |
|---|---|
| `SsePlugin` | `dart:io HttpClient` + `StreamTransformer`（无插件依赖） |
| `NavigationBarPlugin` | `SystemChrome.setSystemUIOverlayStyle(SystemUiOverlayStyle(systemNavigationBarColor: ..., systemNavigationBarIconBrightness: ...))` |
| `FileSaverPlugin` | `path_provider` + `share_plus`（或自写 MethodChannel 复用现成 Java 代码） |
| `RunWatchPlugin` | `flutter_local_notifications` + 自写 MethodChannel；前台服务那段 Java 建议直接复用 |

**复用现成 Java 代码是允许的**：`MainActivity` 里已注册的插件都是普通 Capacitor 类，
想在 Flutter 侧用同一份能力，写个 `MethodChannel` 转发过去比重写更快，也更不容易出现两套行为。

## 验证清单

- [x] `cd app && npm run apk:release` 出包成功，签名校验通过（`apksigner verify`）
- [x] release 包 43 MB，`lib/*/libflutter.so` 已 strip（11.8 MB / 8.6 MB）
- [x] WebView 侧流程未受影响：627 个前端单测 + 14 项 `verify:send` 全绿
- [x] `flutter analyze` 无 issue，`flutter test` 通过（`settings_models_test.dart` 4 项：完整解析 / 缺字段宽松 / `sseNative` 缺省 / `maskToken`）
- [x] 真机：进 Flutter 页期间 RunWatch 通知判定仍然正确（0.1.20 修：`isAppInBackground` 单测覆盖 4 种组合）
- [ ] 真机：点齿轮直接进 Flutter 设置页，主题 / 通知 / 设备 / 概况 / 技能 / 扩展 / DSH 七个分区都能显示（0.1.21）
- [ ] 真机：改主题、关通知、切设备、刷概况、开关技能/扩展、DSH 运行/停止、DSH 安装（含错误 ID 校验）均生效且提示正常
- [ ] 真机：返回键与页内返回都能回 WebView，且回来后会话列表/聊天页状态未丢
