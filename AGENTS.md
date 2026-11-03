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

## 界面栈：WebView 本地 UI + 桌面网页版（0.3.0 起，Flutter 已移除）

0.2.x 期间客户端曾走「Flutter add-to-app」把聊天/侧栏/工作区搬进 Dart。这条路在 0.3.0 全部拆掉
（`flutter_module/`、`FlutterHostActivity`、`src/platform/flutter-*.ts` 及其测试都已删除），原因：
同一套界面维护两遍，协议再移植一份，每次桌面端加字段都要两边对表，而真正「对齐桌面端」的解法是
**直接用桌面端那份 UI**。现在 App 里只有两套东西：

1. **本地 Web UI**（`app/src/**`，Capacitor WebView）：保留的兜底界面，`npm run build` 的产物，
   也是设置页、连接面板等入口的所在地。它自己维护协议（`src/api/`），功能面比桌面网页版窄。
2. **桌面网页版**（下面一节）：把桌面端自带的浏览器 UI 直接跑在 App 里，功能面与桌面端一致。

改代码时的两条硬约束：
- **不要再往 App 里移植桌面端界面**：需要桌面端的能力就走桌面网页版，需要壳的能力（通知、后台看护、
  文件落盘、扫码）才写原生。
- **`document.visibilityState` 重新变回可信信号**：没有 Flutter 页盖 WebView 了，
  `isAppInBackground()` 只看 `visibilityHidden`（`app/src/platform/run-watch.ts`）。

## 桌面网页版与本地外壳（0.2.20 起，主推荐路径）

桌面端 0.9.9 自带一份**给浏览器用的同一套 UI**（`out/renderer/web.html`，靠 `createBrowserApi`
把整个 `window.piDesktop` 映射成同源 `fetch`），并且已经做过手机走查。客户端的终态是
**壳 + 桌面端那份 UI**，不再自己维护第二套界面与第二份协议：

- 入口：设置 → 连接面板 → 「用桌面网页版打开」（`app/src/platform/desktop-web.ts` →
  `DesktopWebPlugin` → `DesktopWebActivity`）。
- `DesktopWebActivity` 用**裸 WebView** 加载页面，不是 Capacitor 的 BridgeActivity：
  `native-bridge.js` 只在本地 origin 注入，远程页拿不到插件，所以返回键 / 文件选择 / 外链
  这些直接用原生 WebView 接口实现。
- `LocalWebServer`（`app/android/.../LocalWebServer.java`）：只监听 `127.0.0.1`、随机端口，
  静态资源回源一次后落盘缓存（桌面掉线也能出界面），`/api/*` 转发并统一注入
  `Authorization: Bearer`，SSE 逐块 flush。**改它必须跑**
  `powershell -NoProfile -ExecutionPolicy Bypass -File app/android/tools/localweb-test/run.ps1`（14 项）。
- 五个踩过的坑：① 入口页在桌面端是 `GET /`（优先回 `web.html`），**不是** `/index.html`；
  ② 上游 404 要透出 404，不能拿入口页兜底；③ 响应头 `Connection` 必须改写成 `close`；
  ④ **静态回源必须自己拆分帧** —— 桌面端对 HTML/JS 用 `Transfer-Encoding: chunked`，把原始 body
  当正文发出去，`903

` 这种 chunk 头就成了页面内容，React 不挂载 = 白屏（0.2.20 事故）；
  gzip 回源同理要解开。⑤ **缓存要带格式版本号**（`CACHE_VERSION`），否则取坏的副本会一直留在
  手机上，升级 App 也照样白屏。
- 写 `.ps1` 带中文必须 **UTF-8 with BOM**（Windows PowerShell 5.1 按 GBK 解），
  `-D...` 开头的 java 参数必须整个加引号，否则会被 PowerShell 当成自己的参数。

## 改动完成后必须跑的验证

```bash
npm test && npm run typecheck                 # 协议核心
cd app && npm test && npm run typecheck       # 客户端
cd app && npm run build && npm run verify:send
powershell -NoProfile -ExecutionPolicy Bypass -File app/android/tools/localweb-test/run.ps1  # 本地外壳 14 项
```

出包前还要确认 Android 侧能编过：`cd app/android && ./gradlew assembleDebug`（或直接 `npm run apk:release`）。

`verify:send` 是 mock 的发送链路（14 项），能挡住「发送后不回显 / 流式块重复 / 按钮状态错」这类回归。
