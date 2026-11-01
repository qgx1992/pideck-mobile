# Flutter 混合栈（add-to-app）

> 目标：把客户端逐步从「Capacitor WebView 外壳」迁到 Flutter。
> 现状：**混合栈已接通**（0.1.19）。WebView 仍是主界面，Flutter 通过独立 Activity 承载，
> 设置页 →「实验」→「Flutter 混合栈页面」可以打开，用来验证两条栈能共存。

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
| `flutter_module/` | Flutter 模块（`flutter create --template=module`）。`lib/main.dart` 是试验页 |
| `flutter_module/.android/` | Flutter 生成的 Android 宿主工程。**必须提交**（`include_flutter.groovy` 在里面），只忽略 `local.properties` 与 `build/` |
| `app/android/settings.gradle` | `evaluate(include_flutter.groovy)`，把模块暴露成 Gradle 项目 `:flutter` |
| `app/android/app/build.gradle` | `implementation project(':flutter')` |
| `dev/pideck/mobile/FlutterHostActivity.java` | 承载 Flutter 页的 Activity（`FlutterFragmentActivity`），提供 `MethodChannel("pideck/flutter_host")`：`getArgs` / `close` |
| `dev/pideck/mobile/FlutterLauncherPlugin.java` | Capacitor 插件 `FlutterHost`：`open({route, sessionId})` / `available()` |
| `dev/pideck/mobile/PiDeckApp.java` | 自定义 Application，用 Activity 计数驱动 `RunWatchService.setAppVisible`（见下） |
| `app/src/platform/flutter-host.ts` | 前端封装：`isFlutterHostAvailable()` / `openFlutterPage(route, sessionId?)`。老包/浏览器环境静默返回 false |

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

## 4 个坑

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

## 逐页迁移顺序（建议）

按「依赖原生插件少 → 多」排，迁一页就把入口切过去，随时能停：

1. **设置页**（`ui/settings-view.ts`，438 行）：纯列表 + 开关，只有一个 `toggleNotify` 走插件。最适合练手。
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
- [x] 真机：设置页 →「实验」→ Flutter 页能打开（0.1.19 已确认）
- [x] 真机：进 Flutter 页期间 RunWatch 通知判定仍然正确（0.1.20 修：`isAppInBackground` 单测覆盖 4 种组合）
