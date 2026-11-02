# 项目约定

给协作的 agent / 开发者看的硬性规则。改了流程请同步更新本文件。

## 版本号：用户已授权「改完直接出本地包」

**用户长期授权：改完代码就直接出本地包，不用再问。**

- 每次出包的默认动作：`patch + 1`（0.1.11 → 0.1.12），`versionCode = major*100 + minor*10 + patch`
  （0.1.12 → 112，0.2.0 → 200）。
- 只有**用户明确说了「先不打包 / 只改代码」**时才跳过出包；除此之外默认就是「改完 → 打包 → 发布 → 校验」。
- 仍在用户不同意时才做的事：改动 `major` / `minor`（0.1.x → 0.2.0）、改 `applicationId`、发 GitHub Release。
- 这一条只覆盖**本地包**路径；GitHub Release 仍由用户明确指定。

需要同时改的三处（漏一处就会出现「包里是 0.1.8、页面写 0.1.9」这类错位）：

| 文件 | 字段 |
|---|---|
| `app/package.json` | `version` |
| `app/android/app/build.gradle` | `pideckVersionName` / `pideckVersionCode` |
| `app/README.md`、根 `README.md` | 产物文件名里的版本号 |

规则：

- 加功能、修 bug 本身不需要单独确认，**默认连出包一起做完**（见上一节）；
- `versionCode` 必须**单调递增**（0.1.9 → 110，不能复用或回退）。
- 不要在同一个提交里混「功能改动」和「版本号变更」：分成 `fix(...)` / `feat(...)` 与单独的 `chore(release): x.y.z`。
- 回滚版本号的代价不只是流程混乱：本地包已经装到手机上之后，撞上 **Android 不允许降级安装**，只能卸载重装。

## 发布的两条路径

两者独立，用户会明确指定走哪条：

1. **本地包（局域网测试）**：`cd app && npm run apk:release` → `npm run publish:apk -- --release`
   → 更新 `apk/index.html` 的版本、链接与说明 → 校验 8080 上的下载与 sha256。
   `apk/` 在 `.gitignore` 里，目录里只保留最新一个 PiDeck 包（旧包发布后删掉并同步 `SHA256SUMS.txt`）。
2. **GitHub Release**：`git push origin master` → 打 `vX.Y.Z` tag 并推送 → CI 构建签名包并挂到 Release
   → 用 `gh release edit` 补标题与说明（工作流本身不写 body）。

> 本机 `git push` 需要显式走系统代理：`git -c http.proxy=http://127.0.0.1:7897 push ...`
> （代理端口会随 Clash 重启变化，先 `netstat` 确认）。
>
> 出包流水线（`.github/workflows/android-release.yml`）在 gradle 之前会跑 `flutter pub get`，
> 缺 `flutter_module/.android/local.properties` 时手写 `sdk.dir` / `flutter.sdk` —— add-to-app 之后
> gradle 要求这个文件（它是 `flutter pub get` 的产物、不进 git），v0.1.35 的出包就是卡在这。
> 流水线里还有 `flutter analyze` / `flutter test`，所以**本地先跑绿再打 tag**，否则 tag 白打。
>
> CI 的 runner 是 en-US：任何 `toLocaleTimeString()` / `toLocaleDateString()` 之类的本地化输出
> 都会在 CI 与本地之间分叉（踩过：消息时间是 `3:04:`）。要格式化的时间自己算，别靠 locale。

## 局域网下载页（8080）：开机自启的计划任务，不要挂工具子进程

`apk/` 目录用 Python 的静态服务器对局域网开放（手机扫码下载 APK 走这条）。
**不要把它挂在 agent 的 shell 子进程里** —— 任何一条工具命令超时被 kill 时，整个进程组
会一起死，下载页就无声消失了。踩过：`gh release download` 超时、60s guardrail 砍掉进程组，
8080 上的 PID 356 跟着消失，用户端看到的就是「下载页怎么挂了」。

现在走的是 **Windows 计划任务**（登录后延迟 20 秒启动，`pythonw.exe` 无终端窗口）：

```powershell
# 注册 + 立刻启动（幂等，可重复跑；-NoStart 只注册不起）
powershell -NoProfile -ExecutionPolicy Bypass -File app/scripts/install-download-server-task.ps1

# 卸载（顺带杀掉还在监听 8080 的进程）
powershell -NoProfile -ExecutionPolicy Bypass -File app/scripts/install-download-server-task.ps1 -Uninstall
```

- 任务名 `PiDeck Download Server`，实际命令：`pythonw.exe "…\app\scripts\serve-apk.py" --port 8080`
- 服务本体是 `app/scripts/serve-apk.py`，**不是** `python -m http.server`：后者是单线程，
  手机拉 14MB 包时整站被堵住；且 `pythonw` 下 `sys.stdout/stderr` 是 `None`，
  基类的 `log_message` 一写就抛 `AttributeError`（在发响应头之前炸），必须先把它们接管到日志文件。
- 日志：`%LOCALAPPDATA%\PiDeck-DownloadServer\server.log`（**不能**放进 `apk/`，那是对外明文目录）。
- 改了 `serve-apk.py` 之后要重启任务才生效：
  `powershell -NoProfile -Command "Stop-ScheduledTask 'PiDeck Download Server'; Start-ScheduledTask 'PiDeck Download Server'"`

临时起一份（调试 / 换端口）用这个，同样要 detached：

```powershell
powershell -NoProfile -Command "$p = Start-Process -FilePath 'C:\Users\Administrator\AppData\Local\Programs\Python\Python312\pythonw.exe' -ArgumentList '-m http.server 8080 --bind 0.0.0.0 --directory \"D:\A-my project\PiDeck-Mobile\apk\"' -PassThru -WindowStyle Hidden; 'pid=' + $p.Id + ' exited=' + $p.HasExited"
```

> 两个 PowerShall 坑：脚本路径别写 `python` / `pythonw` 裸名（会命中 `WindowsApps` 的空壳
> 别名而静默失败，表现为 `http=000`）；`.ps1` 里带中文必须存成 **UTF-8 with BOM**，
> 否则 Windows PowerShell 5.1 按 GBK 解，直接解析失败（`字符串缺少终止符`）。

起完必须核对四件事：

```bash
netstat -ano | grep LISTENING | grep :8080          # 有 0.0.0.0:8080 且 PID 指向 pythonw.exe
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/
curl -s -o /dev/null -w "%{http_code}\n" http://<本机局域网IP>:8080/   # 页面里的二维码指向这个地址
curl -s http://<本机局域网IP>:8080/<包名>.apk | sha256sum             # 与 apk/SHA256SUMS.txt 一致
```

其它约定：

- `apk/` 下**只放能公开的东西**。它是对局域网无鉴权明文开放的（还带目录列表），
  任何凭据 / 客户端配置 / 未脱敏的笔记都不能放在这里（踩过：`apk/proxy/` 里三份代理配置曾对外 200）。
- 页面里的二维码用 `app/scripts/gen-qr.py` 生成、`app/scripts/verify-qr.mjs --expect <地址>` 校验。
- 静态服务器没有 deny 规则，要挡住某个文件只能让它不在这棵目录树里。

## Flutter 混合栈（add-to-app）

客户端正在从「Capacitor WebView 外壳」逐步迁到 Flutter，**混合栈已经接通**（0.1.19 起）。
细节见 `docs/flutter-migration.md`，这里只列必须记住的硬约束：

- Flutter 模块在 `flutter_module/`，Gradle 通过 `app/android/settings.gradle` 里的
  `evaluate(include_flutter.groovy)` 暴露成 `:flutter`；`app/android/app/build.gradle` 里
  有 `implementation project(':flutter')`。**没有 Flutter SDK 的机器现在出不了包。**
- 工具链版本是卡死的，不要随手降：Gradle **8.14.3**、AGP **8.11.1**、minSdk **24**、
  NDK **28.2.13676358**（写在 `app/android/variables.gradle` 的 `ndkVersion`）。
  少了 `ndkVersion` 这一行，AGP 不 strip，release 包会变成 **327 MB**（实测），strip 后 42 MB。
- `flutter_module/.android/` 要提交（`include_flutter.groovy` 在里面），只忽略
  `local.properties`（含本机 `flutter.sdk` 绝对路径）和 `build/`。
- WebView 侧入口在 `app/src/platform/flutter-host.ts`（`openFlutterPage(route, sessionId?)`），
  设置页「实验」区块只在原生插件可用时出现，浏览器里点不到。
- 可见性判定已从 `MainActivity.onResume/onPause` 移到 `PiDeckApp`（`Application.ActivityLifecycleCallbacks`
  + `startedCount`），新增 Activity 不需要再手动喂 `RunWatchService.setAppVisible`。
- **`document.visibilityState` 不等于「App 是否在前台」**：跳进 Flutter 页时 WebView 会被
  `onPause`，它变成 `hidden`，而用户就在看屏幕。所以：
  1. 可见性只允许由 `PiDeckApp.isAppVisible()` 判定，**不要**再从 JS 上报
     （`RunWatchPlugin.setVisible` 已改成 no-op，别把它改回去）；
  2. JS 判后台一律用 `isAppInBackground({ visibilityHidden, flutterPageOpen })`
     （`app/src/platform/run-watch.ts`），不要手写 `visibilityState === "hidden"`；
  3. `flutterPageOpen` 来自原生 `pageVisibility` 事件（`onFlutterPageVisibility()`），
     由 `FlutterHostActivity.onStart/onStop` 发出。新增任何盖住 WebView 的原生页都要接这条。
- 国内出包：Gradle 直连 Google 仓库极慢（看起来像卡死）。本机用
  `$env:GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"`，
  **不要**写进 `gradle.properties`（CI 没有代理会炸）。
- **Dart → 宿主的带参调用一律传 Map**：`invokeMethod(m, {'json': ...})`。宿主的
  `MethodCall.argument()` 在 arguments 不是 Map 时**抛 ClassCastException**（不是返回 null），
  异常会逃出 `setMethodCallHandler`，Dart 只拿到被 `catch (_)` 吞掉的 PlatformException ——
  症状是整页按钮全失灵且日志安静（0.1.21 踩过）。宿主取值用
  `FlutterHostActivity.settingsJsonFrom(call)`，`test/host_channel_test.dart` 锁住形状。
- 设置页（0.1.21 起）在原生壳里就是 Flutter 版，走「视图迁移」：数据仍由
  `app/src/main.ts` 的 `settingsData()` 组装并 `pushFlutterSettings` 推过去，用户动作走
  `settingsAction` 回传到 `dispatchFlutterSettingsAction()`，**与 Web 版共用同一套 handler**
  （`createSettingsHandlers()`）。别在 Flutter 侧另存一份状态，也别往 handler 里加第二份逻辑。
- 需要「一定成功」的返回动作走专用方法让宿主直接 `finish()`（`closeHost()`），
  不要绕 WebView 转一圈。
- **Flutter 页面里不许写死颜色**：一律 `PiColors.of(context)`（`flutter_module/lib/app_theme.dart`
  的 `ThemeExtension`，深浅两套由 `buildPiTheme(Brightness)` 挂上去）。主题的唯一来源是 Web 侧
  `theme.mode`，经 `setSettings` 推送 → `themeModeFrom()` → `MaterialApp.themeMode`；
  Flutter 侧不存主题，也不要自己读 `MediaQuery.platformBrightness`（0.1.23 踩过：页面写死深色）。
- 聊天页（0.1.27 起）同样走「视图迁移」，并且有一条额外硬规矩：**聊天状态机只存在于
  `src/api/frames.ts` 的 `ChatReducer`**。Dart 侧（`flutter_module/lib/chat/`）只允许画
  `ChatSnapshot` 里已有的字段，**不许拼 delta、猜工具状态、自己算 token** —— 复制一份 reducer
  就是两个真相源，协议一加帧类型必然漂移。
- 聊天快照一律经 `FlutterChatMirror` 推（`app/src/platform/flutter-chat.ts`，40ms 尾沿合并 +
  内容不变不推），**不要**在渲染路径里直接 `pushFlutterChat()` 每帧过桥。
- 聊天页里任何「每 40ms 会重建」的部件都不能在 `build` 里创建状态：提问卡的输入框用常驻
  `TextEditingController`、多选的临时选择放 State（0.1.27 踩过：每次重建光标跳回开头、勾选被清空）。
- 侧栏（0.1.28 起）是**最彻底的视图迁移**：分组 / 折叠 / 搜索 / 预览截断 / 多设备分块全部只在
  `src/ui/sidebar-model.ts` 与 `src/ui/sidebar-tabs-model.ts` 里算，Dart 侧
  （`flutter_module/lib/sidebar/`）只实现 `flat` / `device` / `group` 三种段的通用渲染器，
  **不许在 Dart 里判任何规则**（尤其「展开其余 N 个」的截断与「当前会话被截断时临时展开」）。
- 侧栏动作里 `toggleSection` 必须按 key 前缀分派：`project:<id>` → `toggleSessionsExpanded()`，
  其余 → `toggleFlat()`。两个展开集合（`expandedSessions` / `flatExpanded`）互不相干。
- WebView 的弹层被 Flutter 页盖着看不见。**新加弹层先走通用弹层**（`app/src/platform/flutter-sheet.ts`
  的 `SheetView` + Dart 的 `AppSheetHost`）：WebView 算行（`id` / `label` / `description` / `group` /
  `selected` / `actionable`），Flutter 画并回传 `sheetPick { id }`，执行逻辑仍在 `main.ts`。
  只有下面这几种才用 `leaveFlutterThen()`（先 `closeFlutterPage()` 再开会话/弹层）：
  页面级导航（扫码配对 `webSheet("connection")`）、Web 顶栏独有的五张弹层（主题 / 连接 / 端点 / 用量）、
  以及要滚动排版的长文本（DSH 子代理历史）。
- 侧栏长按菜单**在 Flutter 里画**（0.1.34 起项目菜单也是；会话菜单 0.1.28 起就是本地的）：
  动作用侧栏通道同名发回去（`sessionRename` / `sessionCopy` / `sessionExport` / `sessionDelete` /
  `projectRemove` / `createSession` / `toggleProject`），执行逻辑只有 `main.ts` 一份。
  二次确认（删会话、移除项目）在 Dart 侧做——Web 的 `openSheet` 确认框被抽屉挡着，用户看不见。
- **快照驱动的页面里，`setState` 必须能说出「哪个可见元素依赖它」**。说不出就是在用整页重建换一个没人看的
  变量（0.1.38 的输入框 `onChanged` 就是这样：每敲一个字重建整页）。纯函数式转换（markdown 解析、base64 解码）
  输入稳定就缓存，缓存必须有上限（FIFO），否则流式时每帧一个新 key 就是内存泄漏；图片永远给 `cacheWidth`。
- **视觉参数照 `app/src/styles.css` 抄，别凭手感**：`--radius` = 12、气泡内边距 10/12、角色行 11px、
  时间行 10px 且在气泡外、消息图 128×128、输入框圆角 10、空态是「徽标 + 标题 + 副标题 + 提示词」整块。
  0.1.37 之前的偏差清单见 `docs/flutter-migration.md`。半透明色一律 `Color.alphaBlend(色.withValues(alpha: x), 真正的底色)`，
  不要直接叠页面底色（Web 的 `color-mix` 是对着 `--bg-soft` 混的）。纯黑底的页面（看图页）不许用主题色画按钮。
- 往 Dart 搬菜单时**不要照搬到不了的分支**：`openProjectMenu` 里「内置 Chat 项目不能移除」
  永远走不到（Chat 项目在「聊天」段平铺，进不了项目分组），所以 Dart 侧没有这个判断，
  快照里也就没有对应的 `chatProject` 字段。搬之前先验证前端能不能触发那条分支。
- 消息长按菜单（0.1.29 起）**不许在 Dart 里维护动作表**：菜单项由 `ChatSnapshot.messageMenus`
  带来，源头是 `src/ui/message-actions-model.ts` 的 `actionsForRole(role, backend)`
  （ACP 会话只剩「复制」）。Dart 只决定怎么画（danger 标红）与哪两步本地确认
  （删除二次确认、编辑输入框）—— 这两步的确认框原本在 WebView 里，被盖着看不见。
- 用户点了聊天页的「返回网页版」就置 `chatUseWebView = true`，之后点侧栏会话直接 `openSession()`，
  **不写 localStorage**（重启回默认的 Flutter 版）。改 `renderSidebarInner` 的 `onOpenSession` 时
  必须保留 `onEndpoint(run, …)` 那层包装，否则多设备下会连错端点。
- 文件落盘只允许走 `SavedFiles.save()`（`app/android/app/src/main/java/dev/pideck/mobile/SavedFiles.java`）：
  WebView 的 `FileSaverPlugin` 与 Flutter 通道的 `saveFile` 共用它。**不要**在任一入口另写一份
  MediaStore 代码 —— 两边规则一旦不同，就会出现「网页版能存、Flutter 版报错」。
- 选图只走 `FlutterFiles.readImage()`：压缩参数（`MAX_EDGE = 1600` / `JPEG_QUALITY = 82` /
  `MAX_FILE_BYTES = 8 MB`）与 `app/src/ui/image-attach-model.ts` 保持一致，且**必须按 EXIF 旋转**
  （浏览器 canvas 自动应用 EXIF，`BitmapFactory` 不会，少了这步竖拍照片会躺着）。Dart 侧不许
  自己 `decodeImage` 后缩放（大图会 OOM，原生是解码时降采样）。
- 扫码**不在 Flutter 里重写**：`BarcodeScanner.scan()` 靠 Capacitor bridge 接 ActivityResult，
  Flutter 宿主的 Activity 拿不到结果。走 `openWebSheet('connection')` → 原生先发 `webSheet`
  事件再 `finish()` → JS 侧 `leaveFlutterThen(() => openConnectionSheet())`。**顺序不能反**。
- 顶栏与输入框附属面板（0.1.31 起）同理：`ChatHeaderView`（`app/src/platform/flutter-chat-header.ts`）
  里只有「画什么」，**Dart 侧不许出现 `if (backend === "acp")` 这类能力判断** —— 那是
  `composerToolChips()` / `buildHeaderView()` 的事。`chips[].key` 就是动作名，
  新增一个 chip 必须同时在 `dispatchFlutterChatAction` 里加 `case "chip"` 的分支。
- 选择面板（模型 / 思考 / 安全 / 提示词 / 上下文）跟着 `ChatSnapshot.picker` 一起推，
  **不要**另开 `setChatPicker` 通道；Dart 侧用内联面板渲染，不用 `showModalBottomSheet`
  （弹层状态归 `Navigator` 管、内容归快照管，不同步就会出现「关了又弹」）。
  搜索在 Dart 本地过滤，只在回车时发一次 `pickerQuery`（`context` 字段原样带回，
  @ 提及靠它留在当前目录）。
- 面板条目带 `insert`（提示词 / 斜线 / @ 文件）就**由 Dart 自己插进输入框**再发 `closePicker`：
  面板开着时输入框的文字以 Dart 为准，绕回 WebView 插进那个隐藏的输入框用户看不见。
  插入与触发字符的规则在 `flutter_module/lib/chat/composer_insert.dart`（纯函数，有单测）——
  别在 `chat_page.dart` 里重写一遍，那里踩过两个坑：`/` 插成 `//goal`、光标 -1 时把整段文字删掉。
- 看图用 `flutter_module/lib/chat/image_viewer.dart`，**不要换回 `PageView` 自带滑动**：
  `InteractiveViewer` 会抢走横向拖动，翻页只能听 `onInteractionEnd` 的速度。
- 「历史」跳转（0.1.33 起）**在 Dart 本地滚**，不发 `pickerSelect` 回 WebView：历史 id → 下标就在
  `ChatSnapshot.history` 里。懒构建列表里定位用**二分**（`_renderedHistoryRange()` 看这一帧构建出
  哪些下标），不要用「平均高度 × 下标」估算 —— 长回答和短回答差一个数量级，长会话里最不准。
- 待办清单的**展开态是 Dart 本地状态**（`_todoExpanded`），不要推回 WebView；清单条目本身不可点选
  （由 agent 维护）。`ChatTodoSummary.total` 老快照没有时要退回 `open + done`，否则会显示 `0/0`。
- 空态提示词（`ChatSnapshot.suggestions`）点一下**只填输入框、不直接发送**。

## 改动完成后必须跑的验证

```bash
npm test && npm run typecheck                 # 协议核心
cd app && npm test && npm run typecheck       # 客户端
cd app && npm run build && npm run verify:send
cd flutter_module && flutter analyze && flutter test   # Flutter 侧
```

`verify:send` 是 mock 的发送链路（14 项），能挡住「发送后不回显 / 流式块重复 / 按钮状态错」这类回归。
