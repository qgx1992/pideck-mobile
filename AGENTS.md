# 项目约定

给协作的 agent / 开发者看的硬性规则。改了流程请同步更新本文件。

## 版本号：一批改动涨一次，同一批反复修不涨

**用户长期授权：改完代码就直接出本地包，不用再问。但「出包」不等于「涨版本号」。**
（2026-11 用户明确要求：反复修同一个问题不要每次都出一个新版本。）

判定标准只有一条 —— **是不是「用户眼里的一件事」**（不是你视角的一次提交或一个「新功能」）：

- 同一处功能的追加改造（统计行先加→再改口径→再要 2 秒刷新）、同一轮反馈里的第二第三处修正、
  用户说「再改一下 / 顺便 / 也改成」的，**全部算同一批，不涨号**。
- **涨 patch**：开始一批新的交付（新的需求、或一个已确认交付的问题）时涨一次，例如 0.5.28 → 0.5.29。
- **不涨**：这一批还没验收就又被报问题（「还是没显示」「左侧栏也要」「又漏了一处」），
  继续用同一个 `versionName` / `versionCode` 重新出包，**同号覆盖安装**（Android 允许同 versionCode
  覆盖安装，只是不允许降级）。包里只更新 `SHA256SUMS.txt` 与 `apk/index.html` 的 sha256。
- 想不清是不是同一批时按「不涨」处理：多出一次同号包没代价，多占一个版本号会让人分不清哪版才是结果。
- 只有**用户明确说了「先不打包 / 只改代码」**时才跳过出包；除此之外默认就是「改完 → 打包 → 发布 → 校验」。
- 仍在用户不同意时才做的事：改动 `major` / `minor`（0.5.x → 0.6.0）、改 `applicationId`、发 GitHub Release。
- 这一条只覆盖**本地包**路径；GitHub Release 仍由用户明确指定，且已发出去的 versionCode **不能再复用**
  （Release 之后的新一批只能用更大的号）。

### versionCode 公式（以实测值为准）

```
versionCode = major*1000 + minor*100 + patch      # 0.5.28 → 528，0.2.0 → 200，0.5.9 → 509
```

旧文档写过 `major*100 + minor*10 + patch`（0.5.28 → 78），与实际 APK 里的 `528` 不符，以此处为准。
同一 minor 内 patch 跨 9 也保持单调（`0.5.9 → 509` < `0.5.10 → 510` < `0.5.30 → 530`）。

`CHANGELOG.md` 里**同一个版本号只写一条**：多轮修订并进这条（可以写「0.5.29（含 3 次修订）」），
不要按轮次各起一条 —— 那也是「每次都出一个新版本」的另一种形态。

需要同时改的几处（漏一处就会出现「包里是 0.5.41、页面写 0.5.40」这类错位）：

| 文件 | 字段 |
|---|---|
| `native/app/build.gradle.kts` | `versionCode` / `versionName`（唯一真源，CI 也从这里解析） |
| `CHANGELOG.md` | 顶部新版本那一条 |
| `README.md`、`native/README.md` | 版本号、单测数量 |
| `apk/index.html` | `<h1>`、APK 链接、`<div class="meta">` 里的版本与 sha256（`apk/` 不入库，但对外就是它） |

规则：

- 加功能、修 bug 本身不需要单独确认，**默认连出包一起做完**（见上一节）；
- `versionCode` 必须**单调递增**（0.1.9 → 110，不能复用或回退）。
- 不要在同一个提交里混「功能改动」和「版本号变更」：分成 `fix(...)` / `feat(...)` 与单独的 `chore(release): x.y.z`。
- 回滚版本号的代价不只是流程混乱：本地包已经装到手机上之后，撞上 **Android 不允许降级安装**，只能卸载重装。

## 发布的两条路径

两者独立，用户会明确指定走哪条：

1. **本地包（局域网测试）**：`cd native && ./gradlew --offline -q :app:testDebugUnitTest`
   → `./gradlew :app:assembleRelease`（产物 `native/app/build/outputs/apk/release/app-release.apk`）
   → 拷成 `apk/PiDeck-native-<版本>-release.apk` → 重写 `apk/SHA256SUMS.txt` → 改 `apk/index.html`
   → 校验 8080 上的页面与包 sha256。
   `apk/` 在 `.gitignore` 里，目录里只保留最新一个 PiDeck 包（旧包发布后删掉并同步 `SHA256SUMS.txt`）。
2. **GitHub Release**：`git push origin master` → 打 `vX.Y.Z` tag 并推送 → CI 构建签名包并挂到 Release
   → 用 `gh release edit` 补标题与说明（工作流本身不写 body）。

> 本机 `git push` 需要显式走系统代理：`git -c http.proxy=http://127.0.0.1:7897 push ...`
> （代理端口会随 Clash 重启变化，先 `netstat` 确认）。
>
> 出包流水线（`.github/workflows/android-release.yml`）只构建 `native/`：`setup-java 21` →
> 补 `platforms;android-35` / `build-tools;35.0.0` → 从 secrets 还原签名到 `keys/` →
> `./gradlew :app:testDebugUnitTest` → `./gradlew :app:assembleRelease` → `apksigner` + `aapt2` 校验
> 签名与 versionCode → 上传 Release 附件并删掉 `keys/` 里的签名材料。
> 版本号从 `native/app/build.gradle.kts` 解析，tag 与 gradle 不一致只告警、以 tag 命名产物。
> 流水线里有单测，所以**本地先跑绿再打 tag**，否则 tag 白打。
>
> `ci.yml` 是 PR/push 的快速门禁（只跑 `native/` 的单测，路径过滤 `native/**`）。
>
> CI 的 runner 是 en-US：任何 `toLocaleTimeString()` / `toLocaleDateString()` 之类的本地化输出
> 都会在 CI 与本地之间分叉（踩过：消息时间是 `3:04:`）。要格式化的时间自己算，别靠 locale。

## 下载页（8080）只写当前版本（用户要求，长期有效）

`apk/index.html` 里**不堆历史版本记录**：只留「当前版本这一版改了什么」+ 包大小 / versionCode / sha256 + `SHA256SUMS.txt` 链接。
旧版本的说明写在仓库提交记录里，不要一条条往页面上贴（页面上曾经堆过 77 条、页面膨胀到 39 KB，用户明确要求撤掉）。

- 改版时：直接替换当前版本那段 `<div class="meta">`，不要再往上面追加。
- 历史包本身也不要留在 `apk/`（只留当前一个 PiDeck 包）。

## 局域网下载页（8080）：开机自启的计划任务，不要挂工具子进程

`apk/` 目录用 Python 的静态服务器对局域网开放（手机扫码下载 APK 走这条）。
**不要把它挂在 agent 的 shell 子进程里** —— 任何一条工具命令超时被 kill 时，整个进程组
会一起死，下载页就无声消失了。踩过：`gh release download` 超时、60s guardrail 砍掉进程组，
8080 上的 PID 356 跟着消失，用户端看到的就是「下载页怎么挂了」。

现在走的是 **Windows 计划任务**（登录后延迟 20 秒启动，`pythonw.exe` 无终端窗口）：

```powershell
# 注册 + 立刻启动（幂等，可重复跑；-NoStart 只注册不起）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install-download-server-task.ps1

# 卸载（顺带杀掉还在监听 8080 的进程）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install-download-server-task.ps1 -Uninstall
```

- **进程被带走要能自己回来**（2026-11-14 起）：注册脚本除了「登录后延迟 20 秒」，还挂了一条
  `-Once -RepetitionInterval 10 分钟 -RepetitionDuration 3650 天` 的看门狗触发器，
  `MultipleInstances=IgnoreNew` 保证服务在跑时它只是空转。踩过：11-12 那天常驻实例被整棵进程树带走，
  计划任务只在登录时触发一次，于是 8080 静默消失两天（`LastTaskResult=1`，没人发现）。
  验证办法：`Stop-Process` 掉监听 8080 的 pythonw，20 秒~10 分钟内应自己回来
  （实测 96 秒；`RestartCount 3 × 1 分钟` 也会兜一次），回来后在日志里能看到新的
  `serving ... (pid N)` 行。
- 任务名 `PiDeck Download Server`，实际命令：`pythonw.exe "…\scripts\serve-apk.py" --port 8080`（0.5.41 起脚本从 `app/scripts/` 搬到仓库根的 `scripts/`，任务已用新脚本重新注册过）
- 服务本体是 `scripts/serve-apk.py`，**不是** `python -m http.server`：后者是单线程，
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
- 页面里的二维码用 `scripts/gen-qr.py` 生成、`scripts/verify-qr.mjs --expect <地址>` 校验。
- 下载页正文**只放一行当前版本摘要**，改了什么放进 `<details class="changelog">`（默认折叠），
  历史全部进 `<details class="history">`。踩过：每次都把改动段落往上摞，页面变成一屏读不完的更新日志。
- 静态服务器没有 deny 规则，要挡住某个文件只能让它不在这棵目录树里。

## 历史：0.4.x 的 WebView 壳（已归档，别再照它做）

0.1.x–0.4.x 的客户端是「Capacitor 壳 + vendor 桌面端网页」（`app/vendor/pideck/` + `app/shell/`），
配套还有更早的 ① 代 TypeScript 协议核心（`src/`、`tests/`）。这两套代码在 0.5.41 全部从 master 删除，
完整内容留在分支 **`archive/webview-shell`**（`git checkout archive/webview-shell -- app` 可取回）。

为什么删：它们已经不产出任何交付物（0.4.12 停发），却还在 CI 里跑 job、还在 `AGENTS.md` 里占 147 行
操作手册，导致「照文档做会得到 0.4.12 的包」这种误导。零碎的踩坑记录（安全区、扫码取景、返回键分层、
`fixed` 覆盖层让位等）在 git 历史与归档分支里都还在，不要再往本文件搬回来。

- **浮窗里的「翻译」只有宿主 agent 这一条路**（`native/app/src/main/java/dev/pideck/nativeapp/data/Translation.kt`
  + `ChatViewModel.translate`）：本机没有离线翻译（ML Kit 要额外 AAR 与模型包，构建走 `--offline`），
  桌面端的划词翻译是 `agent-profiles:invoke` 这条 Electron IPC，`/api/*` 里没有，别去找。
  实现就是匿名会话（`noSession`）→ `prompt`（指令里写死「只输出译文、不要使用任何工具」）→
  读回复流 → 收尾 `runtime/stop`，与「并行发送」同一套机器；方向判断在 `defaultTranslateTarget`
  （汉字占非空白字符 ≥15% 当中文）。浮窗的翻译按钮只给 `DetailContent.translatable = true` 的
  （思考卡），工具卡不给 —— 命令输出里翻 JSON 参数没有意义。

### 检查更新：只有一条对外请求（0.5.49 起）

- 唯一来源是 GitHub Releases 的 `/releases/latest`（`data/UpdateCheck.kt` 的 `LATEST_URL`），
  **不要**去宿主 `/api/*` 找 —— 宿主的 Web 服务只管桌面端自己，它不知道 Releases 里有什么。
- 比版本用 `UpdateCheck.isNewer`（点分段逐段比），**不要**用 `versionCode = major*1000+minor*100+patch`
  反推：patch 跨 99 时 `0.5.100` 与 `0.6.0` 都算 600，一比就错。
- 状态只有一处（`data/UpdateCenter.kt` 的 `mutableStateOf`）：设置页读它写状态文案，首页读它出横条。
  同一时刻只放一条请求（`inFlight`），查到新版后不重复查，手点按钮走 `check(force = true)`。
- 「启动时自动检查」是 `Prefs.autoCheckUpdate`（默认开），由 `ui/StartViewModel.kt` 在决定落地页的
  那个协程旁边另起一条去查（`Prefs.awaitLoaded()` 之后读开关）。失败静默：没网的人不该每次启动都看到红字。
- 本 App **不自动装包**：只给「看 Release 页」和「直接下 APK」两个出口（`ui/common/Links.kt` 的 `openUrl`）。
- 发版时想验证这套逻辑：本地包号比 Release 低一档即可（0.5.49 装本地、0.5.50 发 Release），
  设置里应当报「有新版 0.5.50」。

## 原生客户端（0.5.x，2026-11 起接管）

现在的客户端是纯 Kotlin + Compose，源码在 `native/app/src/main/java/dev/pideck/nativeapp/`，
版本号与出包约定见本文件开头两节（改动走 `native/app/build.gradle.kts` 的 `versionCode`/`versionName`）。

出包与验证（本地）：

```bash
cd native && ./gradlew --offline -q :app:testDebugUnitTest   # 300 条 JVM 单测
cd native && ./gradlew --offline -q :app:assembleRelease     # 产物 app/build/outputs/apk/release/app-release.apk
```

`native/app/src/test/` 覆盖的是纯逻辑（解析、分帧、行索引、分组、统计口径等）。没有 Compose 测试、
没有 `ChatViewModel` 行为测试 —— 改界面与 ViewModel 时要靠真机回归。

踩过的坑，改这几处前先看：

- **顶栏那排胶囊的宽度必须有一个「可伸缩」的**（`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/ChatScreen.kt`，
  `TopAppBar` 正下方那行 `Row`（模型 / 思考档位胶囊））：模型胶囊外面套一层 `Box(Modifier.weight(1f))`、内部封顶 188dp，
  右侧（上下文胶囊 + 历史按钮）才永远有位置。全都给固定宽度时，上下文胶囊一出现，
  历史按钮就只量到 0 宽度、直接看不见（踩过一次，用户报「查看上下文后历史按钮被顶掉」）。
- **「它问你一句」也要通知，而且不能报成「回复完成」**（`native/app/src/main/java/dev/pideck/nativeapp/watch/RunWatchService.kt`）：
  `/api/sessions/runtimes` 里**没有** pending 这一项，所以看护每轮顺带用 `If-None-Match` 问一次
  `/api/state`（没变化＝304、不带 body，别怕 55KB），从 `pendingUiRequests` 拿问答卡。判据三条：
  新 `requestId` 才弹（去重键 `deviceId|sessionId|requestId`，回答后从表里清掉，否则同一张卡每次轮询都弹）；
  该会话挂着提问时**抑制**「回复完成」（提问时 agent 常正好 running→idle，那条会被误弹，用户点进去才发现它在等人）；
  渠道单独一个 `run-watch-ask`（`IMPORTANCE_HIGH`），别塞进 `run-watch-done` —— 静音「跑完了」不该把要人回的东西一起静音。
- **点「回复完成」通知要能进那个会话**：`native/app/src/main/java/dev/pideck/nativeapp/watch/Notifications.kt`
  的 `finished()` 必须把 `pideckDeviceId` + `pideckSessionId` 塞进 Intent，并且**每条通知一份
  PendingIntent**（requestCode 用 `NotificationNav.notificationId(deviceId, sessionId)`）；
  共用一个 requestCode + `FLAG_UPDATE_CURRENT` 会让后发的通知覆盖前一条的 extras
  （表现：点 A 进去是 B）。通知 id 也要带 deviceId，否则两台设备的同名会话互相覆盖。
  交接台是 `native/app/src/main/java/dev/pideck/nativeapp/watch/NotificationNav.kt`：
  `MainActivity` 在 `onCreate`/`onNewIntent` 里 `post()` 并摘掉 extras（转屏重建会拿同一份
  Intent 再走一遍 `onCreate`），`AppNav` 冷启动用 `take()` 直接把它当起点、热启动靠 `flow` 就地跳。

- **历史消息里的图片要自己捞**（`native/app/src/main/java/dev/pideck/nativeapp/data/Models.kt` 的
  `ChatMessage.images`）：宿主 `/api/sessions/:id/messages/page` 对 user 消息会带
  `images: [{type, data, mimeType}]`，`data` 是**裸 base64**（不是 data URL）。kotlinx 默认不认识的键
  直接丢，所以模型里不声明就等于「图从来没到过手机」—— 0.5.23 之前就是这样，气泡里只剩一句
  「（N 张图片）」。渲染在 `native/app/src/main/java/dev/pideck/nativeapp/ui/chat/MessageImages.kt`：
  按缩略图尺寸 `inSampleSize` 降采样 + `LruCache(24)`，别整张原图往内存里搬。
- **Markdown 表格是 GFM 解析**（`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/Markdown.kt`
  的 `MdBlock.Table`）：判据必须同时满足「本行含 `|`」+「下一行是同列数的 `:?-+:?` 分隔行」，
  只有竖线不算表格（否则普通正文里的 `|` 会被吃掉）；`\|` 是字面竖线，代码块里的 `|` 一律不算。
  表格渲染用 `Modifier.height(IntrinsicSize.Min)` + 竖线 `fillMaxHeight()`，否则竖线高度是 0。
- **state 要声明在 `init` 之前**（`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/ChatViewModel.kt`）：
  Kotlin 按文本顺序初始化属性，`init` 里给「声明在后面」的 `by mutableStateOf(...)` 赋值
  会命中还没初始化的委托字段 → NPE，整个 ViewModel 构造失败（表现：会话页进不去，编译期看不出来）。
  0.5.22 出过一次，0.5.23 修；守门测试 `native/app/src/test/java/dev/pideck/nativeapp/ui/chat/ChatViewModelInitOrderTest.kt`。
- **新建会话选的后端只认 `dsh` / `acp`**（`native/app/src/main/java/dev/pideck/nativeapp/data/AgentBackend.kt`）：
  宿主 `POST /api/sessions` 的路由只校验 `projectId`，其余 body 整个透传给 `createSessionDraft`，
  而它里面是 `const backend = input.backend === "dsh" ? "dsh" : input.backend === "acp" ? "acp" : void 0` ——
  **pi 是「不写」，写 `imagegen` / `videogen` / 拼错的值都建成 pi**（实测记录 `backend` 为空）。
  ACP 还要带 `acpToolId`（`/api/state` 的 `acpTools[i].id`）。会话页对非 pi 后端要收掉模型与思考胶囊：
  宿主对 acp 会话**不落** `model` / `thinkingLevel`，DSH 用它自己的模型体系。
  `defaultAgentBackend` / `hiddenModules` / `acpEnabled` 都不经 HTTP 暴露 —— 所以**不要**把上次的选择
  记成本地默认（0.5.25 记过，0.5.26 撤了：猜「上次用的」比跟 pi 对齐更容易让人困惑）。
  列表行与聊天页顶栏的后端标记统一走 `AgentBackend.badgeOf(backend, acpToolName)`，别各写一份；
  渲染用共用的 `ui/theme/Controls.kt` 的 `BackendBadge`。
  注意**会话行有四处布局**：`ui/sessions/SessionsScreen.kt` 的 `SessionRow`（全部会话）、
  `ui/home/HomeScreen.kt` 的 `RecentSessionRow`（首页「最近的会话」）、
  `ui/chat/SessionDrawer.kt` 的 `DrawerSessionRow`（聊天页左侧抽屉）、
  `ui/chat/DrawerActivePane.kt` 的 `ActiveSessionRow`（抽屉「活动」段）。0.5.26 改了第一处、
  0.5.27 补第二处，用户又发现抽屉里没有（0.5.28 补齐），到 0.5.41 才发现「活动」段连后端标记都没有。
  **所以 0.5.41 起不许再手抄那段文字**：标题 + 元信息 + 后端标记统一走
  `ui/common/SessionRowText.kt` 的 `SessionRowText(...)`，外壳（缩进、圆角、点击手势、右侧状态）各留各的。
  加行级元素时改 `SessionRowText`，四处同时生效；新增第五处也算「用共用块」，别再复制粘贴。
  `ui/common/SessionRowTextGuardTest.kt` 直接读这四份源码对账（要求出现 `SessionRowText(`、
  不许出现 `BackendBadge(`），谁把这段手抄回去测试就红。
- **相对时间只有一份**：`ui/common/TimeText.kt` 的 `relativeTime()`，口径照桌面端 `formatRelativeTime`
  （刚刚 / N 分钟前 / N 小时前 / N 天前 / 30 天以上 N 个月前）。0.5.41 之前 `SessionsScreen` 给「93 天前」、
  `SessionDrawer` 给「08-08」，同一个会话两处显示不同。别再拿 `SimpleDateFormat` / `toLocaleXxx` 格式化时间
  （CI runner 是 en-US，会与本地分叉）。
- **live 联调测试别拿 idle 运行时当判据**：`RuntimeStateLiveTest` 曾经挑 `runtimes.firstOrNull { !it.isRunning }`，
  而 idle 的 runtime 上下文窗口已经卸载（`contextWindow = 0`）→ 必红。挑正在跑的那个，
  没有在跑的才 `assumeTrue` 跳过。

- **运行中发送有三条通道，别混成一条**（`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/ChatViewModel.kt`
  的 `sendWhileBusy`）：
  - 加入当前回合 → `POST /api/sessions/:id/prompt` + `streamingBehavior:"steer"`，
    **不要**再开一条 `/api/chat` 流：这条消息是注入到跑着的那一轮里，文本会顺着已有的流过来，
    再开一条只会两份 `live` 互相覆盖（`send()` 自己也带 `sending` 守卫）。
  - 排队到下一轮 → 同一条通道 + `"followUp"`。宿主不回传队列内容（桌面端走 Electron IPC），
    所以「已排队 N 条」是客户端自己的账：发出去时记账，等文本出现在历史里再撤销（`reconcileQueue()`）。
  - 并行发送 → `POST /api/sessions/anonymous`（`noSession:true`，不落盘）→ `prompt` →
    `GET /api/sessions/:id/stream`。这条会话的消息查不到（`messages/page` 返回空），
    答案只能从流里取；关面板要 `runtime/stop`，否则匿名运行时一直挂着。
  - `prompt` 的 body 没有 images 字段：运行中的消息一律不带图片，带图就明确提示，别悄悄丢。

- **忙碌时的投递档位要按后端筛**（`native/app/src/main/java/dev/pideck/nativeapp/data/BusySendMode.kt` 的
  `options(backend)` / `coerce(backend, mode)`）：**ACP 协议没有 steer 通道**，桌面端 0.9.x 起就用
  `steerSupported` 把「加入当前回合」藏掉（文案「该会话的 agent 不支持插入当前回合」），0.10.0 起用
  「立即发送」占那一格 —— 先停本轮再把这条当新一轮发。手机上如果照旧给三档，用户点「加入当前回合」
  只会被排到本轮之后而以为插进去了。别把这条判定写进 Composable：界面只读
  `ChatViewModel.busyModes / effectiveBusyMode`，判定留在枚举伴生对象里，单测 `data/BusySendModeTest.kt`。
- **排队消息的对账键是序号，不是文本**（`native/app/src/main/java/dev/pideck/nativeapp/data/QueuedPrompts.kt`）：
  宿主给每条落盘消息写了 `meta._piDeckMsgSeq`（全局递增，`ChatMessage.seq` 读它），
  入队时记下当时的最大序号，只有**序号更大**的同文 user 消息才算这条到达 —— 一条落盘只抵一条排队。
  按文本做 `filterNot { it in landed }`（0.5.22–0.5.33 的做法）在「同一句话发两次」「回答里回显了这句话」
  时会一次消掉好几条。宿主没有序号时退回按出现次数对账，别直接清空。
  另外：**手机做不到「撤回排队」** —— 桌面端那个排队面板是客户端自己攒的账（Electron IPC），
  手机发出去就进了 pi 自己的队列，HTTP 侧没有撤回接口。别照着桌面文案做撤回按钮。
- **取消语义只有一条规则**（0.5.59 起）：可能被取消的调用用 `data/Cancellation.kt` 的 `attempt { }`，
  不用 `runCatching { }`（它连 `CancellationException` 一起吞，表现是「点了停止还在跑」）。
  手写 `catch (e: Exception)` / `catch (e: Throwable)` 时第一行必须是 `if (e is CancellationException) throw e`。
  纯同步解析继续用哪个都行，但统一成 `attempt` 更省心。
- **「断了就重连」只有一份实现**（`data/Reconnect.kt` 的 `reconnectingStream`）：聊天页看护流与会话列表
  `/api/events` 都走它，退避 1.5s → 3s → 翻倍 → 15s 封顶。**每收到一帧要调 `onFrame()`** 把失败计数归零，
  否则一次抖动就直接跳到 15 秒。正常关流算一轮失败（以前归零 → 404 会变成 1.5 秒一次的忙循环）。
- **匿名会话的运行时都要记进 `data/EphemeralRuntimes.kt`**：这类 runtime 不出现在项目列表里，没人替你收。
  卸载必须走它自己的 `stopScope`，**不能**用 `viewModelScope` —— `ViewModel.clear()` 先关 closeables
  再调 `onCleared()`，在那里用 viewModelScope 发的请求一条都发不出去（宿主上留孤儿运行时）。
- **跨设备的键要带 deviceId**：`data/SessionKey.kt` 的 `SessionKey(deviceId, sessionId)` 就是为此。
  会话 id 只在它自己的宿主里有意义，只按 `sessionId` 存（草稿箱以前就是这样）会在第二台设备上串台。
- **`catch (e: Exception)` 不是「不会崩就行」**：错误消息要落到界面（`error` / `loadState`），
  静默吞掉的结果是用户看到空白页、日志里什么都没有。
- **ViewModel 里的 `device` 是异步来的，界面上的 `LaunchedEffect(Unit)` 比它早**
  （`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/ChatViewModel.kt` 的 `deviceReady`）：
  `init` 要先去 `DeviceRepository` 把设备/令牌读出来才知道往哪发请求，而 Composable 的
  `LaunchedEffect(Unit)` 在同一帧就跑了。以前 `loadModels()` 直接 `device ?: return`，
  于是模型表第一次永远是空的，必须手动点刷新（0.5.35 修的就是这个）。
  **凡是「界面自动触发、需要 device/token」的加载函数，都先 `deviceReady.await()` 再取值**，
  别在函数顶部 `device ?: return`；用户在点击后才触发的（发送、停止、翻页）可以不改，
  那时设备早就在了。
- **取数、状态、界面状态、DI 各有唯一入口（0.5.42 起，别在 ViewModel 里再写一份）**：
  - **取设备**：`native/app/src/main/java/dev/pideck/nativeapp/data/Connection.kt` 的
    `ConnectionResolver.resolve(deviceId, timeoutMs)` → `Connection(device, token)`。
    它内部是「带超时的 `first { }` + 读令牌」，超时 / 设备不存在都返回 null（回设备页）。
    以前四个 ViewModel 各写一遍这段，于是「设备被删 → `first {}` 永久挂起 → 页面一直转圈」
    在首页、会话列表、聊天页各犯了一次。**新页面要设备就注入它，不要自己 `repository.devices.first`**。
    它的依赖是 `DeviceDirectory`（只要 `devices` + `tokenOf`），实现就是 `DeviceRepository`。
  - **写状态**：`data/SessionStateStore.kt`。`put(deviceId, state)` = 写 `StateCache` + `SessionActivity.observe`
    两件事一起做；轻量的 `/api/sessions/runtimes` 走 `putRuntimes()`。别再出现「只写了缓存、
    忘了同步运行态」的调用点（那会让会话行的状态灯有时亮有时不亮）。
  - **列表页状态**：`ui/common/LoadState.kt`（`Idle` / `Loading` / `Ready` / `Failed(message)`），
    配 `failureOf(e)`（内部 `describeConnectionError`，401 会顺手写 `AuthNotice`）。
    首页 `HomeViewModel` 与会话列表 `SessionsViewModel` 的 `loading` / `error` 已经只是它派生的只读属性；
    要区分「连不上 / 正在拉 / 真的没有内容」时读 `loadState`，别再靠 `items.isEmpty()` 猜。
    动作类失败（改名、删除、开新会话）走各自的 `actionError`，别和整屏拉取混成一个字段。
  - **Android 手脚**：`data/PlatformActions.kt`（接口）+ `data/AndroidPlatformActions.kt`（实现，
    纯转发、一行判断都不许有）。聊天页与列表页要起看护 / 记落脚点 / 写档位 / 读图 / 存文件，
    一律注入它，**不要**再往 ViewModel 的构造参数里加 `@ApplicationContext Context`
    —— 加了就等于把整页从单测里踢出去（单测的 `android.jar` 每个方法都抛）。
    新加一个副作用时先想：能不能挂在 `PlatformActions` 上；不能再开新的接缝接口，别塞 `Context`。
  - **ViewModel 行为测试**：基底是 `native/app/src/test/java/dev/pideck/nativeapp/data/FakePideckApi.kt`
    （每个方法抛「未打桩」，测试里 `object : FakePideckApi() { override suspend fun state(…) = … }` 只写
    要用的那几个；接口加方法后它编译不过，不会静默漏）。写法照
    `native/app/src/test/java/dev/pideck/nativeapp/ui/home/HomeViewModelTest.kt`：
    `Dispatchers.setMain(StandardTestDispatcher())` + `runTest(dispatcher)` + `advanceUntilIdle()`
    （ViewModel 的 `init` 也在 `viewModelScope` 里跑，不推进调度器就什么都看不到）。
    **注意 fake 默认没打桩就会抛**，`init` 顺手调到的方法（首页是 `models`）也要给个空实现，
    否则异常会冒到测试线程上当失败。
    **别把 ViewModel 依赖 Android 的东西**：`HomeViewModel` 曾经多余地注入 `DeviceRepository`
    （一个方法都没用到），去掉之后整条链路都能在 JVM 里跑；要写新 VM 测试就先看它的构造参数里
    有没有 `Context` / `DeviceRepository` —— 有就说明该先收口（`ConnectionResolver` / `DeviceDirectory`）。
  - **DI**：`di/AppBindings.kt` 用 `@Binds` 把 `PideckApi` 与 `DeviceDirectory` 绑到真实实现
    （真实实现的 `@Provides` 在 `di/AppModule.kt`）。要给 ViewModel 写行为测试，
    用 `@TestInstallIn` 换掉 `AppBindings` 塞假实现即可，不必搭 mock server、也不要改生产装配。
- **新会话的模型偏好只在 pi 上带**（`native/app/src/main/java/dev/pideck/nativeapp/data/Images.kt`
  的 `ChatPayloads.createSessionBody`）：宿主 `createSessionDraft` 的 `model` 是
  `!acp && input.model ? … : void 0`，DSH 则用它自己的模型体系。带 `model` 时形状必须是
  `{provider, modelId, modelName}`；**不带就是「跟随电脑端默认」**，宿主按
  `welcomeModel ?? settings.defaultProvider+defaultModel ?? 第一个启用模型 ?? lastUsedModel` 解析，
  所以「不指定」这一档不要自己猜一个模型填进去。首页那颗胶囊因此只在 `backend == Pi` 时出现。

- **宿主版本与适配基线**（`native/app/src/main/java/dev/pideck/nativeapp/data/HostCompat.kt`）：
  `/api/state.version` 是桌面端 0.10.0 才加的常量字段，老宿主解析出来是 null。
  设置页已显示版本，`BASELINE` 只在本客户端**真的重新对过一遍接口**时才涨；低于基线给提示，
  版本未知不给 —— 拿不到版本不等于宿主旧。
  0.11.0–0.11.3 实测（手机端 0.5.51 起以 0.11.0 为基线）：路由集合 33 条全同、
  `toPublicSession` 字段逐字节相同、`/api/state` 键相同、流帧类型相同；唯一新增是 `role:"tool"`
  的消息会带 `images`（生图类扩展把图放进工具结果，裸 base64），手机端在 `data/Timeline.kt` 的
  `ToolView.images` 上接住、`ToolCard` 折叠态就渲染缩略图。流式那一帧里宿主没给图，
  所以刚跑完要等一次补拉历史。
  对账方法：把桌面端 `resources/app.asar` 用 `npx @electron/asar extract` 解开，
  比路由字符串集合、`getState()` / `toPublicSession` / `/api/chat` 的处理代码、以及 `data-*` 流帧集合。
  0.10.0 实测：路由 28 条全同、七处处理代码逐字节相同、`/api/state` 只多了 `version`、
  消息页多了 `nextBeforeEntryId` / `indexVersion`（客户端忽略即可）。

- **回合统计行（首字 / tok/s）是本端自己打点的**（`native/app/src/main/java/dev/pideck/nativeapp/data/TurnStats.kt`）：
  桌面端那一行数是主进程现测的（`out/main/index.js` 的 perf：起点 `requestedAt` 是宿主**收到** prompt 的时刻，
  `firstDeltaAt`/`firstTextAt` 由 agent 事件流打点），走 `agentsRuntimeState` 边沿推送，
  **不在消息 meta 里**，手机订不到。所以本端自己开秒表：`ChatViewModel.send()` 里 `resetTurnClock()`（记按下发送），
  紧靠 `api.chat(...).collect` 之前 `markTurnRequestSent()`（**首字的起点**：本端请求发出的那一刻，
  与宿主 `startedAt` 通常差 27ms 左右，见实测），流里首个增量
  `markTurnFirstContent()`（正文优先、reasoning 兜底，对应桌面 `firstTextAt → firstDeltaAt`），
  收尾记 `turnEndedAt`，落盘后 `settleTurnStats()` 用 `meta.usage.outputTokens` 结算。
  **起点既不能用「按下发送」，也不能用「SSE 首帧到达」**：按下那一刻算进去，手机到宿主的建连/排队
  能吃掉 20s+（0.5.32 最初的坑：桌面 2s / 手机 28s）；改到首帧又反过来少了宿主收下请求后的排队
  （实测 2.4s，同一轮手机 2.7s / 桌面 5.1s）。请求发出时刻才对得上桌面，也让「首字 + 解码 ≈ 全程」闭合。
  生成速度的分母是「**首个任意增量（含思考）** → 收尾」，与桌面 `firstDeltaAt` 同口径；
  别用正文字（思考先行几百毫秒，同一条回复会比桌面快 5~10%，实测 161 vs 150），也别用
  `total − ttft`（会把排队稀释进分母）。`TurnClock` 因此同时记 `firstContentAt`（正文，给首字）
  与 `firstDeltaAt`（任意，给生成速度），`ChatViewModel.markTurnFirstContent()` 两个都打点。
  桌面那行里的「均速」（`sessionStreamingTps`）是**整段会话**的平均流式速度，不是本轮，
  别拿它跟手机的「全程」对账。
  **实时刷新只跟宿主的新值走**（0.5.36 用户要求，别再加固定节拍重算）：`ChatViewModel.startHostStatsWatch()`
  每秒读一次 `runtime/state`，把 `perf.perfAt`、会话累计 `outputTokens` 的增量、`cacheHitPercent`
  拼成签名，**签名变了才动这一行**（`lastHostStatsKey`），没变就一个字都不刷新。之前那套
  「每 2 秒按字数估 token」已经删掉（`estimateOutputTokens` 也一并删了）——估出来的 `≈` 数
  会在收尾时跳一下，用户明确不要。代价是长文本单步跑到一半这一行不动，宿主本来也是落一步
  才结算一次。
  **这一行的四段**（`formatTurnStats`，缺项一律 `—`）：`首字 X · 流式 Y tok/s · 匀速 Z tok/s · 命中 H`
  - `流式` = `perf.tps`（本轮生成速度，桌面端「流式 TPS」）；
  - `匀速` = `perf.sessionTps`（**整段会话**的平均流式速度，桌面端上下文弹层里「会话平均流式 TPS」那行
    197 tok/s 就是它）。**不是**本轮的 `endToEndTps` —— 别再拿端到端顶上去（用户 0.5.36 明确换掉的）；
  - `命中` = `usage.cacheHitPercent`（宿主按最近一次回复算的）；
  - **不显示 tok 数**：tokens 只是算速度的中间量（`TurnStats.outputTokens`），不往屏幕上放；
  - **外框与输入卡片同宽同圆角**（`padding(horizontal = 12.dp)` + `RoundedCornerShape(16.dp)` + 1dp
    `outlineVariant` 描边，`TurnStatsLine` 里注释写着对应关系）。输入区的内边距一改这里必须跟着改，
    否则这一行会左右各错开几 dp。
  - **「运行中」的指示器就是这圈边框光**（0.5.40 用户要求）：`streaming` 时在 `drawBehind` 里叠一层
    sweep 渐变（主色、两端透明）绕中心转 360°，1.5s 一圈；动画值只在绘制阶段读，文字不重组、尺寸不变。
    别再放浮在列表底部的「生成中」胶囊 —— 它盖住最后一条消息，还跟右下角「回到底部」的 FAB 撞位置。
- **设置页的枚举型选项统一用下拉**（`ui/settings/SettingsScreen.kt` 的 `DropdownSetting`，
  胶囊 + 带勾菜单，与首页的 `SelectChip` 同款），别再摆一排 `FilterChip`：选项一多就换行，
  还会和下面的说明文字挤在一起。
  **收尾的真值优先取宿主自己结算的那份**（0.5.36 起）：`POST /api/sessions/:id/runtime/state`
  里带着 perf 四件套 `{ttftMs, totalMs, tps, endToEndTps, perfAt}`，就是桌面端屏幕上那几个数
  （`data/RuntimeActions.kt` 的 `parseHostTurnPerf`）。`deriveTurnStats(host = …)` 会用它，
  并且**连 tokens 都从它反推**（`tokens = endToEndTps × totalMs`，本来就是这个式子算出来的），
  免得「本端整轮求和 + 宿主单步耗时」拼出一个假的大速度。认「是不是这一轮」靠
  `perfAt` 与消息 `meta.endedAt` 对账（`HOST_PERF_MATCH_MS = 120_000`），没有 `endedAt`
  才退回「2 分钟内」。**别退回「只看年龄」**：切个会话就超过窗口，数字立刻换一套。
  拿不到 perf 时（翻历史 / dsh / 运行时已退出）才用本端秒表 + `meta.usage`，这时 tokens
  **必须整轮求和**（`TurnStats.turnUsageOf()`）：带工具的一轮有多条 assistant 消息，一条只是
  一步的量，只取最后一条会把 12k 的一轮显示成 881。整轮的时间也要整轮算（`TurnUsage.spanMs`
  = 首步 `startedAt` → 末步 `endedAt`），别拿最后一步的 `durationMs` 当整轮。
  **结算结果要存下来**（`data/TurnStatsStore.kt`，key = `sessionId|messageId`，上限 120 条，
  SharedPreferences + 内存镜像，`PiDeckApp.onCreate` 里 `attach`）：切走再切回来时秒表已清、
  宿主 perf 可能过期，重新算会得到另一套数（用户报过「切换会话后数值消失或变了」）。
  所以 `settleTurnStats()` 是「先 recall、没有再算、算了就存」。
  `/api/state` 的 runtimes 仍然只有 `{sessionId, agentId, runtimeGeneration, projectId, cwd, status,
  sessionPath, deckSessionId, createdAt}`（perf 只在 runtime/state 那个 POST 里）。
  跨设备 / 翻历史只能靠 meta 的 `durationMs`，而**只有 pi 后端有**
  （实测：pi = `{usage, durationMs, startedAt, endedAt}`，dsh = 只有 `usage`，acp = `null`）——
  那种情况只报端到端速度，别硬算「生成速度」。渲染在 `ui/chat/ChatScreen.kt` 的 `TurnStatsLine`
  （输入条上方一行 `labelSmall`）——**这一行常驻**，缺项用 `—` 占位（`formatTurnStats` 永远返回
  非空串，`stats` 为 null 也照样给占位）。用户明确要的（0.5.32）：忽隐忽现会把输入条顶来顶去，
  宁可显示一排破折号。
- **缓存命中率放在上下文弹层里，不是统计行**（`native/app/src/main/java/dev/pideck/nativeapp/data/CacheStat.kt`）：
  口径与桌面端 `getSessionCacheHitStats` / pi CLI footer 的 `latestCacheHitRate` 一致 ——
  `cacheRead / (input + cacheRead + cacheWrite) × 100`。**分母三个字段都要算**：`inputTokens` 只是未命中部分，
  只除 input 会算出 >100% 的怪数。数据在每条 assistant 消息已落盘的 `meta.usage` 里
  （`cacheReadTokens` / `cacheWriteTokens` / `inputTokens`），不需要运行时活着、也不需要流式数据。
  两条展示口径：`latestPercent` = **最新一页**里最后一条带 usage 的 assistant（历史接口从新到旧翻页，
  把各页拼成数组后末尾其实是最早那条，所以用 `CacheStatBuilder.addPage()` 逐页喂，见注释）；
  `averagePercent` = 全部 assistant 的**简单平均**（与桌面 `average` 同口径，不按 token 加权）。
  **统计行里的「命中」和这里是两个来源，别搞混**：统计行那个取宿主的 `perf.cacheHitPercent`
  （`runtime/state` 里现成的、最近一次回复的命中率，见 `formatTurnStats`）；弹层里的
  `latestPercent` / `averagePercent` 是本端按 `meta.usage` 自己算的，能翻历史、不要求运行时活着。
  所以两处数字偶尔不同是正常的（口径不同：一个按宿主最近一次结算，一个按本端逐条累加）。
  **不要**把缓存率放进会话列表 —— 不同会话的命中率不可比（新开会话必然低）。
  刷新时机只有「打开上下文弹层 / 点重新读取」，不跟着每轮跑：翻完一个会话最多 10 页请求，
  而命中率一轮才变一次。字段全缺（acp 的 meta 是 null）时显示 `—`，**有输入但没 cacheRead 是真 0%**，
  两者要分开（`cacheHitOf()` 返回 null vs 0.0）。
- **侧栏「活动」段**（`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/DrawerActivePane.kt`，
  0.5.37）：抽屉顶上一排 pill 分「活动 / 聊天 / 项目」三段，对应桌面端左侧栏那个 `Tabs`
  （`active` / `chats` / `projects`；桌面端还有插件分组区，手机拿不到）。`DrawerTabState` 是**单例**不是 `rememberSaveable` ——
  抽屉每次开合都是新组合，会话列表页与聊天页又是两个 ViewModel 实例（同 `GroupCollapse` 的理由）。
  **结构照桌面端 `ActiveSessionsTree`，别自己发明**：上半 `liveDrawerSessions(sessions, loadedSessionIds)`
  = 宿主上**装载着运行时**的会话（`/api/state` 的 `runtimes`，`idle` 也算，按 `updatedAt` 倒序）；
  下半 `recentDrawerSessions(sessions, loadedSessionIds)` = **排除上半**、再按最近活动倒序。
  用户明确要求过「上下两部分没有重复」。上半无小标题、空时一行「暂无运行中的 Agent 会话」；
  下半有「最近会话 · 共 N 条」+「显示前 N 项 / 全部已显示」，先露 10 条（`DRAWER_RECENT_INITIAL`）、
  「加载更多 (N)」每次 +12（`DRAWER_RECENT_STEP`）、「收起」回 10 条。
  **踩过两稿**：① 把上半当成「正在跑」（`Status.Running/Waiting` 过滤）→ 只有真在跑的那条出现，
  其余全掉到下面，用户报「运行中怎么没显示会话列表」；② 改成「全部会话一条平铺列表、运行态只排序
  不过滤」→ 也不对，那是没分组时的旧观感。判据只看一点：桌面端上半来自 `catalog.agents`
  （装载着运行时即列出），不是 `status`。
  `loadedSessionIds` 是 `data/SessionActivity.kt` 里的 `var ... by mutableStateOf<Set<String>>(emptySet())`，
  在 `observeRuntimes` 里 `runtimes.map { it.sessionId }` 赋值（`reset()` 清空）。
  **运行态必须自己轮询**：`SessionActivity.observe()` 只在 `SessionsViewModel.refresh()`（`/api/state`，
  56KB 且带 ETag，只在 ViewModel init 跑一次）与 `ChatViewModel` 切会话/发消息时调 —— 抽屉打开后不会自己重拉。
  现在 `SessionActivity.observeRuntimes(runtimes)` 吃轻量的 `/api/sessions/runtimes`（约 3KB），
  `SessionsViewModel.refreshRuntimes()` 拉它：进抽屉 `LaunchedEffect(Unit)` 打一次、活动段
  `DrawerActivePane` 里每 5 秒（`RUNTIMES_POLL_MS`）再打一次，离开该段随组合注销即停。
  轻量接口没有 `pendingUiRequests`，`waiting` 传 null 时沿用 `lastWaiting`，别当成空集合。
  行是独立组件 `ActiveSessionRow`，**不要**去改 `DrawerSessionRow` / `SessionRow` / `RecentSessionRow`
  —— 那三处各自安静（见上面「会话行有四处布局」，注意标题那一行现在**必须**用共用的 `SessionRowText`）。
  纯函数有单测 `DrawerActivePaneTest`（8 条）。
  桌面端上半是 agent 级（子代理单列），手机只到会话级（子代理要逐会话请求 `/api/sessions/:id/subagents`）；
  桌面端的插件分组区（`desktopApi.sessionGroups.list()`）走 IPC，手机拿不到，这两处不复刻。
  改这个文件别用「截到最后一个 `}`」这类写法 —— `ActiveSessionRow` 曾被一次批量改写误删。
  **那排 pill 是可拖的**（`DrawerSectionBar`，用户 0.5.37 要求「拖动到哪一类就展开哪一类」）：
  实心底块用 `Animatable` 跟手，松手按 `dragTargetSection(连续位置, 每秒段数, 段数)` 吸附，
  速度按 `DRAG_FLING_SECONDS = 0.12f` 预测位移；`detectHorizontalDragGestures` 的 `onDragEnd`
  **不带速度**，要用 `VelocityTracker.addPosition(change.uptimeMillis, change.position)` 自己攒
  （`addPointerInputChange` 在当前 Compose 版本里解析不到）。指示块画在 `drawBehind` 里，
  段宽取 `onSizeChanged` 的实测宽度 —— 别改成布局里的 `offset` 子节点，那样每帧都要重排。
  底块的样式是玻璃片（渐变 + 1dp 高光边 + 投影 + 外发光），**拖动时按离最近一格的距离横向撑开**
  最多 12%、外发光同时变亮；这不是装饰：纯色白片压在浅灰轨道上拖起来看不出在动。深色主题不能只比
  轨道亮一档（底边会和轨道糊在一起）。点按必须 `clickable(indication = null)`，
  用 `Surface(onClick)` / 水波纹会在「准备拖动」时先闪一层灰，用户当误触报过。
  样式最终定在 **KernelSU / M3 expressive 那一路**（用户 0.5.37 给的参照）：图标在上、文字在下，
  选中格是带投影的实心圆角块（圆角 = 高度 × 0.42），条子约 54dp 高 —— 别再退回「一条 38dp 的
  渐变玻璃片 + 图标文字并排」，用户明确说「按钮太扁了」；吸附用 `spring(0.62, 420f)` 带一点回弹。
  最终定稿是**图标在左、文字在右、条高 42dp**（`DRAWER_BAR_HEIGHT`），三段：活动 / 聊天 / 项目；
  三段内容切换用 `AnimatedContent`（`SessionDrawer.kt`，位移取 1/3 屏宽 + 叠化），不要改回 if/else，
  否则切换是硬跳。
  **「聊天」与「项目」是同一套渲染、不同项目集合**（用户 0.5.37 第八稿纠正过一次）：桌面端
  `chatProjects = rootProjects.filter(isChatProject)`、`workspaceProjects = rootProjects.filter(p => !isChatProject(p))`，
  而 `isChatProject(project) === project.kind === "chat"`；两段都渲染 `renderProject`（项目头 + `SessionTree`）。
  所以手机这边只有一份 `private fun DrawerGroupList(...)`（`SessionDrawer.kt`），调用方传
  `viewModel.projectGroups(chat = true / false)`。口径在 `ui/sessions/SessionsViewModel.kt` 的纯函数
  `projectGroupsOf(projects, sessions, chat)` + `isChatProject(project)`：组内只放顶层会话
  （`parentSessionId == null`）、按 `updatedAt` 倒序，项目按置顶 → 最近打开 → 名称排序，
  **没有会话的项目也要留在「项目」段**（桌面端就是这个用意：本机 9 个项目里 8 个是工作区）。
  别再做成「项目行点开才展开会话」那一版（`DrawerProjectsPane` 已删），那是第七稿的错解。
  选择块的坐标是按 `(高度 - DRAWER_BAR_PAD*2)` / `DRAWER_BAR_PAD` 用 `drawBehind` 画的，
  格子行必须 `contentAlignment = Center` + `padding(horizontal = DRAWER_BAR_PAD)`，否则文字
  整体偏上 3dp、外侧两格各偏 1dp（用户报「文本在遮罩里不够居中」）。
  守门测试 `native/app/src/test/java/dev/pideck/nativeapp/ui/sessions/ProjectGroupsTest.kt`（6 条）。

- **技能/扩展、提示词库、提交历史（0.5.39）只有「读 + toggle」**（`native/app/src/main/java/dev/pideck/nativeapp/`）：
  - 技能 `GET /api/skills` + `POST /api/skills/toggle {name, sourceId, enabled}`；扩展 `GET /api/extensions`
    + `POST /api/extensions/toggle {source, enabled, scope}`。**增删技能 / 安装扩展是 Electron IPC**，
    手机上永远做成「只能开关」——别照桌面端把「安装」「删除」按钮也摆上来。
  - 页面在 `ui/settings/SkillsExtensionsScreen.kt` + `ui/settings/SkillsViewModel.kt`，排序在
    `data/SkillsAndPrompts.kt` 的 `SkillsParse.sortedSkills/sortedExtensions`（失效沉底、启用在前），
    单测 `data/SkillsAndPromptsTest.kt`。
  - **提示词库有两个来源，别混成一个**：`GET /api/prompts` 是桌面端那套云端提示词库
    （本机 sqlite 的 `xueprompts` 表，没导入过就是空数组）；桌面端「提示词」面板里那些
    `~/.pi/agent/prompts/*.md` 模板**只有 IPC**（`prompts:list` / `prompts:get`），Web 侧没有路由，
    手机拿不到。所以面板第二段是本机模板（`data/LocalPrompts.kt`，用户自己存的长文本），
    第三块才是宿主库 —— 别把「宿主库为空」当成加载失败，也别去猜别的接口。
    库空时按「哪个库 + 要桌面端导入才有内容」写清楚原因，不要只给一句「没有匹配的提示词」。
  - 提示词库：列表 `GET /api/prompts?search=&category=&page=&pageSize=` 只给摘要（含 `total`）；
    **点一条先展开模板正文**（`GET /api/prompts/:slug?category=` 的 `promptContent`），再「插入到输入框」
    —— 桌面端也是先展开，模板里有 `{{变量}}`，直接塞进去用户不知道自己在发什么。
    插入一律**追加**到草稿后面（没有光标概念），取不到就提示，不要清空草稿。
    面板 `ui/chat/ComposerPanelSheet.kt`，状态在 `ChatViewModel.openPromptLibrary/previewPrompt/searchPrompts/
    previewLocalPrompt/saveLocalPrompt`。
  - 面板两段的顺序是**快捷消息在前、提示词库在后**（用户 0.5.39 明确要求）：一句话的常用，
    长模板偶尔用，别让模板挡住常用入口。`ComposerPanelTab` 的枚举顺序就是界面上从左到右的顺序。
  - 快捷消息是**手机本地**的清单（`data/QuickMessages.kt`，SharedPreferences，上限 30 条 / 每条 200 字，
    无存储时给种子清单）：桌面端那份在 `userData/quick-messages.json`，只有 `quick-messages:get|save`
    两个 IPC 通道，HTTP 侧没有路由，**不要**去 `/api/*` 找一个不存在的东西，也别用 `/api/file-content`
    绕（那会触发桌面端的读取授权弹窗）。面板第二段 `QuickMessageList`，点一条插入、箭头直接发送
    （走 `submitText`，与输入框发送键同一条路径）。
  - 提交历史 `GET /api/git/log?projectId=&limit=` 渲染在工作区「改动」页底部；`authorDate` 是 epoch ms，
    用 `ui/sessions/SessionsScreen.kt` 的 `relativeTime()` 自己算，别用 locale 格式化（CI 是 en-US）。
  - 设置页那句「技能 / 扩展 / 用量回桌面端看」的注释已作废：技能扩展现在是全局 HTTP 接口；
    用量额度（`/api/usage/:provider`）默认关闭，仍未做。

- **过程组有三个不能改的约定**（`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/ChatScreen.kt` 的
  `ProcessGroupBlock`，0.5.45 对齐桌面端）：
  1. **手动展开优先**：用 `manualSet` / `manualOpen` 两个布尔，`open = if (manualSet) manualOpen else autoOpen`。
     别退回 `LaunchedEffect(autoOpen) { open = autoOpen }` —— 那会在一轮跑完的那一帧把用户刚点开的组收回去
     （桌面端是 `manualGroupIds` + `suppressedAutoGroupId`，同一语义）。
  2. **组头要有实时内容**：运行中显示最后一条工具的参数摘要（`data/ProcessActivity.kt` 的
     `toolDetail` / `detailFromArgs`：命令 > 路径 > 检索词 > 描述，多行压一行、超 90 字截断），
     只剩一句「正在执行命令」等于没有信息。
  3. **组内挂载预算 32**（`data/ProcessActivity.kt` 的 `visibleMemberCount` + `PROCESS_MEMBER_BUDGET`，
     对应桌面端 `PROCESS_GROUP_MEMBER_LIMIT = 32`）：`takeLast(shown)` 从末尾往前留，超出给
     「显示更早的 N 条步骤」，每点一次 +32。
  组体样式是桌面端那套「缩进 20dp + 2dp 竖线 + 12dp 内边距」的 `drawBehind`，不是卡片。
  两个开关在 `data/Prefs.kt`（`processGroupDisplay` / `expandInterimStream`，默认都开），
  聊天页通过 `Prefs.processGroupFlow` / `expandInterimFlow` 的 `collectAsState()` 跟着设置页变；
  `buildChatRows(items, live, groupProcess = false)` 就是「全部平铺」（每条内容走 `AssistantBlock`）。
  **组内顺序必须是真实时序**：逐条 step 先放思考、再放这条消息的工具调用（0.5.45 定案，
  跟桌面端 `buildTurnDisplay` 一致）。0.5.43 曾把思考统一挪到组体底部，用户 0.5.45 拿截图纠正：
  39 段思考全堆在工具卡下面，分不清「这条命令是想了什么才跑的」。
  手机与桌面端仍有差异，**别自己去补**：桌面端同一消息的多条工具合成一张 `ToolGroupCard`
  （手机一条一张）、桌面端还有扩展输出 / 重试 / 中间回复的独立节点（手机没有这些数据）。

- **子代理只在一个地方看：工作区面板的「子任务」页**（`ui/chat/WorkspaceSheet.kt` 的 `SubagentTab`，
  入口在会话页右上角菜单 →「工作区」→ 第 4 个 tab）：0.5.58 一度又加了一个独立「子代理」面板，
  纯属重复，已撤。数据来自 `GET /api/sessions/:id/subagents`，那是**现扫会话文件**
  （`subagents:record` + `pi-deck-subagent-start` 锚点 + `acp_delegate` / `subagent` 工具推导），
  运行中的子代理只有锚点、完成才落记录 —— 要看新的就点工作区面板右上角的刷新。
  `toolUses` / `tokens` / `completedAt` 宿主经常不给，缺就整段不写，**别显示 `0 tok` / `0 秒` 占位**
  （`subagentDurationText` 有单测守着）。
- **AI 提问卡可折叠**（`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/AskCard.kt`）：
  标题栏整条就是折叠开关，默认展开、按 `requestId` 记忆；收起时右侧仍留一行题干（`maxLines = 1`），
  不做「有一张卡但不知道在问什么」。展开内容走 `AnimatedVisibility`，选项列仍是 `heightIn(max = 320.dp)` 内部滚动。

- **改偏好要走运行时命令，停止走 abort**（`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/ChatViewModel.kt`
  的 `applyModel` / `applyThinking` / `stop`）：
  - `POST /api/sessions/:id/update` **只改 catalog 记录**，跑着的 agent 还按旧模型回答（表现「胶囊变了回答没变」）。
    运行时活着必须用 `runtime/model`（body `{provider, modelId, modelName}` + target 三元组）与
    `runtime/thinking`（body `{level}`），宿主 `setRuntimeModel` 会顺手 `catalog.update` 落盘；
    运行时没起来（冷会话，`StateCache.runtime` 为空）才退回改记录，下次激活生效。
    宿主可能降档，响应 `{"result":{provider,modelId,modelName,thinkingLevel}}` 里的 `thinkingLevel` 才是真值。
  - 停止按钮 = `runtime/abort`（只断当前这一轮）。`runtime/stop` 是**卸运行时**，下次发消息要重新激活几十秒，
    只在并行/匿名会话收尾时用。重启是 `runtime/restart`：宿主先写 200 头、每 2 秒一个空格心跳、结束才给 JSON，
    所以 UI 要置灰等它跑完（OkHttp readTimeout 已是 0）。
- **两个 OkHttp 客户端，用途不同（0.5.41）**：`native/app/src/main/java/dev/pideck/nativeapp/data/PideckApi.kt`
  里 `client` 是给 SSE 用的（`readTimeout(0)`，一段时间没有帧是正常的），`jsonClient` 是它的副本，
  读超时 `SHORT_READ_TIMEOUT_SECONDS = 45`，**所有一次性请求（`state` / `messagesPage` / `postJson` / `getText` /
  `rawGet` / `createSession`）都必须走它**。以前全部走无限超时的那个：撞上半开连接时一次 GET 就永久挂着，
  表现是「首页一直转圈」「轮询静默停摆」。守门测试 `data/ApiClientTimeoutTest.kt`。
- **一轮发送的三个守卫**（0.5.41，`ui/chat/ChatViewModel.kt` 的 `send()`）：
  ① 空闲看护 —— collect 包在 `coroutineScope { launch(stream) + launch(guard) }` 里，guard 每
  `STREAM_IDLE_CHECK_MS = 15s` 量一次「距上一帧多久」，超过 `STREAM_IDLE_TIMEOUT_MS = 3min` 就
  `stream.cancel()` 并写一句提示（只 cancel 收集协程，不 cancel 外层 try/finally，收尾必须跑完）；
  ② `finally` 里的收尾（补拉历史 + 结算统计）包 `withContext(NonCancellable)` —— 点停止会把整个协程 cancel，
  否则第 ② 次补拉前的 `delay(600)` 立刻抛 CancellationException，「停止后的回答不出现」永远修不好；
  ③ `refreshHistory()` 用 `historyRefreshSeq` 序号守卫（只有最新一次允许落地），别把守卫删成「最后返回的赢」
  —— 旧请求后到会覆盖新结果，严重时 LazyColumn 抛 `Key was already used`。
- **收尾请求不能用 viewModelScope（0.5.55）**：`ViewModel.clear()` 先关 closeables（viewModelScope 在内）
  再调 `onCleared()`，所以在 `onCleared()` / `NonCancellable` 收尾里 `viewModelScope.launch` 发的请求
  一条都发不出去。`ChatViewModel` 为此有两个出口：`cleanupScope`（`SupervisorJob() + Dispatchers.IO`，
  故意不 cancel，专发 `runtime/stop`）与 `refreshHistoryNow(seq)`（suspend 版，收尾直接调）。
  匿名会话要在 `createAnonymousSession` 之后**立刻**登记目标（`RuntimeTarget(anon, "", 0L)`），
  别等宿主回 `agentId` —— 不回就整段跳过，宿主上会留一个 noSession 运行时。
- **翻页与重拉共用一个序号闸门**：`loadOlder()` 自己也要 `val seq = historyRefreshSeq` 并在落地前比对，
  否则翻页结果会拼到已经重拉过的列表上，同一条消息两段 → LazyColumn `Key was already used` 闪退。
- **工作区面板失败必须说出来**：`refreshWorkspace()` 里四个请求用局部 `attempt(label) { … }` 收集失败原因，
  汇总成 `workspaceError = "部分面板没读到（其余照常显示）：…"`。别再退回 `runCatching{}.getOrNull()`：
  断网/401 时界面会显示「没有待办 / 没有改动 / 空文件树」，用户只会以为数据没了。
- **Compose 状态只能回主线程写**：`ui/sessions/GroupCollapse.kt` 的 `attach()` 接的是
  `Dispatchers.IO` 的 scope（`PiDeckApp.onCreate` 里的 `prefsScope`），读盘留在 IO、赋值包
  `withContext(Dispatchers.Main.immediate)`。快照状态线程安全，但主线程不会因 IO 线程的写入被唤醒重绘。
- **授权失效要显式验、显式说**：冷启动 `native/app/src/main/java/dev/pideck/nativeapp/ui/StartViewModel.kt`
  先对存着的设备打一次 `/api/state`（3 秒超时），401/403 就落设备页 + `data/AuthNotice.kt` 的常驻提示
  （「授权已失效，重新配对」），别进首页给一屏红字（用户只会反复重进）。`describeConnectionError` 撞到 401/403
  也会写这条提示。
- **令牌库（EncryptedSharedPreferences）必须自带重建**（`native/app/src/main/java/dev/pideck/nativeapp/data/DeviceRepository.kt`）：
  换签名重装 / 恢复备份 / ROM 清 Keystore 之后旧密文解不开（`AEADBadTagException`），而 AndroidX 的实现在
  **打开时**就建好、每次读写都过 GCM 校验 —— 只要不清 `pideck_tokens` prefs **和** `_androidx_security_master_key_`
  密钥别名，连重新配对写令牌都会失败，用户看到的是「怎么连都连不上」。现在 `tokenOf` / `writeToken` / `removeToken`
  都 `repeat(2)`：第一次失败 `resetStore()`（删 prefs + 删别名 + 置 `tokenReadFailed`）再来一次，写令牌用 `commit()`
  而不是 `apply()`（否则失败无声）。启动页看到 `tokenReadFailed` 就落设备页 + `AuthNotice.TOKEN_LOST`。
  **别把这里改回 `runCatching { tokens.getString }` 就完事** —— 那只挡住了崩溃，没修好「写不进新令牌」。
- **闪退留现场**：`native/app/src/main/java/dev/pideck/nativeapp/data/CrashLog.kt` 在 `PiDeckApp.onCreate`
  最早装未捕获处理器，栈写 `filesDir/last-crash.txt`（含版本 / 系统 / 机型），设置页「诊断」段可看/复制/清除。
  另外读令牌（EncryptedSharedPreferences，Keystore 主密钥可能失效）**全部 runCatching** —— 以前
  `DeviceRepository.tokenOf` 抛一次就把进程带走；`RunWatchService.watchLoop` 整轮兜底（SupervisorJob 不防未捕获异常）。

- **一次性提示走事件流，不要「状态 + 读后清空」（0.5.56）**：`ChatViewModel.events`（`MutableSharedFlow`，
  `extraBufferCapacity = 8`）收 `error` / `prefError` / `actionNote` 的每次赋值；界面只有一条
  `LaunchedEffect(Unit) { viewModel.events.collect { snackbar.showSnackbar(it) } }`。
  旧写法 `LaunchedEffect(error) { showSnackbar(); dismissError() }` 在提示显示的那几秒里状态没变，
  同一句失败再来一次就不会重启 effect —— 表现是「只报第一条」。要常驻的错误留在状态里由界面直接渲染。
- **凡是跨设备的记录，键里必须带 deviceId（0.5.56）**：`SessionActivity` 的 `byDevice / previousRunningByDevice /
  waitingByDevice / loadedByDevice`（`observe(deviceId, …)` / `statusOf(deviceId, sessionId)`），
  `watch/RunWatchService.kt` 的 `titles["${device.id}|${sessionId}"]`。以前只按 sessionId 存，
  两台设备出现同一个 id 时状态灯与通知标题会互相覆盖。界面渲染时读的是「当前那台设备」的视图
  （`publish(deviceId)` 推到 `statuses` / `loadedSessionIds`），所以行组件不用改签名。
- **设备清单只能走 `mutateDevices { }`（0.5.56）**：`data/DeviceRepository.kt` 的读-改-写必须在同一个
  `context.deviceStore.edit { }` 里完成。以前 `readDevices()` → 改 → `writeDevices()` 两段，
  与列表页每轮 `touch()` 交错时后写的覆盖先写的（刚加的设备 / 刚改的名消失）。
  令牌三分语义：`null` 保留 / 空串清掉（对端已是无鉴权）/ 非空覆盖。
- **无限重连循环在测试里会挂死（0.5.56）**：`SessionsViewModel.startStateStream()` 与
  `ChatViewModel.startWatching()` 是「断了就退避重连」的 `while (true)`。`runTest` 收尾会把虚拟时间推到空闲，
  于是空转不结束、整场测试挂到 JVM 被杀（踩过一次，看着像「Gradle daemon 崩了」）。
  所以 `FakePideckApi.stateEvents` 返回的是一条**永不结束**的 `MutableSharedFlow`；
  用 `advanceTimeBy(1_000)` + `runCurrent()` 代替 `advanceUntilIdle()`。
- **看护（RunWatchService）的失败轮次不能算空闲（0.5.56）**：拉 `/api/sessions/runtimes` 失败时置 `pollFailed`，
  `idlePolls` 不动（否则连续 3 轮就 `stopSelf()`，正在跑的那一轮收不到完成通知），
  并且「回复完成」的 `failed` 取自 `lastStateByDevice` 里该会话的 `status == "error"`，别写死 false。
- **`MarkdownCache` 的解析在锁外（0.5.56）**：锁里只做 get/put，容量 8。锁里解析会把同一帧里
  其它消息的 Markdown 一起堵住（先串行再上屏）。

- **本地存清单不要做「转义后可逆」的折衷（0.5.57）**：`data/LocalPrompts.kt` / `data/QuickMessages.kt`
  现在是 JSON（`buildJsonArray` 编码、`PideckJson` 解码）。以前用「`\u001e` 分记录 + 把换行折成字面 `\n`」，
  提示词模板里常出现字面 `\n`（JSON / 正则 / C 转义示例），存一次读回来就变成真换行、用户也改不回去。
  老格式的 `legacyDecode` 只用于升级读一次，别在写入路径上复用。
- **通知权限只问一次（0.5.57）**：`MainActivity.onCreate` 走 `Prefs.shouldAskNotificationPermission()`
  （已是 granted / 已问过都不再请求），问之前先 `markNotificationPermissionAsked`。
  被系统关掉时设置页「通知」段显示红字 + 「去打开」（`ui/common/Links.kt` 的 `openNotificationSettings`）。
- **只在可见时轮询（0.5.57）**：`ui/chat/DrawerActivePane.kt` 的 5 秒运行态轮询包在
  `lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED)` 里；`LaunchedEffect(Unit)` + `while(true)` 在
  App 退到后台、抽屉还开着时会一直打宿主。
- **提问卡的状态一律 `rememberSaveable`（0.5.57）**：`ui/chat/AskCard.kt` 的 `answers`（`mapSaver`）与
  `multi`（`listSaver`）已补上。同一张卡里 `tab` / `draft` / `expanded` 早就是 saveable，
  只留两个 `remember` 的表现是「转屏后草稿还在、勾的选项没了」。

验证：`cd native && ./gradlew --offline -q :app:testDebugUnitTest`，出包
`./gradlew :app:assembleRelease`，产物在 `native/app/build/outputs/apk/release/app-release.apk`，
拷成 `apk/PiDeck-native-<版本>-release.apk` 后更新 `apk/SHA256SUMS.txt` 与 `apk/index.html`
（页面只写当前版本，旧包删掉）。逐版改动记在根目录 `CHANGELOG.md`（0.5.0 起），
每次发版往里加一段，别只靠提交信息。
