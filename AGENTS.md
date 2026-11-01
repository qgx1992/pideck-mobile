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

## 改动完成后必须跑的验证

```bash
npm test && npm run typecheck                 # 协议核心
cd app && npm test && npm run typecheck       # 客户端
cd app && npm run build && npm run verify:send
```

`verify:send` 是 mock 的发送链路（14 项），能挡住「发送后不回显 / 流式块重复 / 按钮状态错」这类回归。
