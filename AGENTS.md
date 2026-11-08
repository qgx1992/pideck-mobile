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
> 出包流水线（`.github/workflows/android-release.yml`）跑的是 `npm ci` + `npm test` + `vite build` +
> `cap sync android` + `assembleRelease`，没有 flutter 步骤（0.3.0 起）。流水线的 runner 是 Node 22，
> `node --test shell/*.test.ts` 依赖 Node 22.18+ 的默认 type stripping，别把 node 版本降到 22 以下。
> 流水线里有 typecheck / test，所以**本地先跑绿再打 tag**，否则 tag 白打。
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
- 下载页正文**只放一行当前版本摘要**，改了什么放进 `<details class="changelog">`（默认折叠），
  历史全部进 `<details class="history">`。踩过：每次都把改动段落往上摞，页面变成一屏读不完的更新日志。
- 静态服务器没有 deny 规则，要挡住某个文件只能让它不在这棵目录树里。

## 界面栈：vendor 桌面端的 Web 客户端（0.4.0 起）

四条路都试过了，结论是「别自己再写一份界面」：

| 版本 | 做法 | 为什么否掉 |
|---|---|---|
| 0.1.x–0.2.x | 自研原生 + 自研协议 | 协议要维护两份，桌面端每加字段都得两边对表 |
| 0.3.0 | Flutter add-to-app | 同一套界面维护两遍（`flutter_module/`） |
| 0.3.1–0.3.11 | 借桌面端网页版（本地转发） | 那份页面是给浏览器写的：系统栏、返回键、文件选择、外链全要原生补，十一个版本大半在修安全区 |
| 0.3.12 | 回到自研本地 UI（`app/src/**`） | 界面能用，但仍是**第二份实现**（8,995 行），桌面端升级不会传导过来 |
| **0.4.0** | **vendor 桌面端 Web 客户端**（`app/vendor/pideck/`） | 当前方案 |

现在的分层：

```
app/vendor/pideck/          桌面端 0.9.9 的 src/renderer/src + src/shared（原样，不改）
app/shell/                  Capacitor 壳：设备、令牌注入、fetch/EventSource 桥、看护、外链、下载
app/shell/launcher.html     dist/index.html —— 配对与设备管理页
app/android/                原生插件：SsePlugin（GET/POST 流式）、RunWatchService、FileSaver、NavigationBar
```

- **界面只有一处**：`app/vendor/pideck/src/renderer/src/web/**`。改界面 = 改上游 = 下次同步带回，
  不要在这里打本地补丁（`app/VENDORED.md` 记着 pin 的 tag 与同步步骤）。
- **壳只做 WebView 做不到的事**：原生 SSE（POST 也能流）、后台跑完发通知、外链交给系统浏览器、
  blob 下载转原生保存、令牌注入（`Authorization: Bearer`，不拼在网址上）。
- 桥的接缝在 `app/shell/boot.ts`：写令牌 → `installFetchBridge` → `installEventSource` →
  `installExternalLinks` → `installDownloadBridge` → 禁 SW → 起看护。顺序有意义：上游
  `webApi.ts:19` 在**模块顶层**读令牌，晚一步就会被旧值盖回去。
- `CapacitorHttp.enabled` 必须保持 `false`（`app/capacitor.config.ts`）：开了它 fetch 会被整体缓冲，
  流式对话直接变成「等半天一次性出来」。
- service worker 必须禁用：Capacitor 下 `sw.js` 会缓存旧 assets，表现为「更新了还是老页面」。
- **「要不要走原生 SSE」按路径判定，不要看 `Accept` 头**（`app/shell/fetch-bridge.ts` 的
  `looksLikeStream`）：`ai` 的 `DefaultChatTransport` 发 `POST /api/chat` 时**不带
  `Accept: text/event-stream`**，只按头分流会把整条对话交给 `CapacitorHttp` 缓冲，
  表现是「等半天、答案一次性蹦出来」。现在按 `/api/chat` 与 `*/stream` 路径判定，
  4 条单测在 `app/shell/fetch-bridge.test.ts`。
- `POST_NOTIFICATIONS` 是**运行时**权限（Android 13+）：清单里声明不等于拿到，
  `RunWatchService` 的 `notify()` 会被静默丢弃，表现为「跑完了没提醒」。启动页
  `app/shell/launcher.ts` 调 `ensureNotificationPermission()`（`app/shell/permissions.ts`），
  被拒过两次后系统不再弹框，「去设置」走 `NavigationBar.openNotificationSettings`。
- 扫码取景是「相机预览在 WebView 下面」：`BarcodeScanner.startScan()` 只负责起相机，
  页面必须自己把背景变透明（`body.barcode-scanner-active`），否则看到的是纯色块。
  `app/shell/launcher.html` 里的遮罩只能靠取景框自身的 `box-shadow`，整屏底色必须 transparent。
- **点通知要能直接进那个会话**：原生那端一直是通的（`RunWatchService` 两条通知的 Intent 都带
  `pideckSessionId` + `pideckDeviceId`），缺的一直是 JS 不去取 —— 结果点通知只把 App 唤醒，停在
  启动页。现在 `app/shell/launch-session.ts` 统一收口：冷启动由 `boot.ts` 把地址栏指到 `/s/<id>`
  （上游 `WebChatApp.tsx:214` 会用 `sessionFromPath()` 恢复选中），热启动由 `appStateChange`/`resume`
  取走 `RunWatchPlugin.consumeLaunchSession()` 后就地 `pushState` + `popstate`。启动页还兼一层：
  Capacitor 的 `html5mode` 会把无扩展名路径回落到 `index.html`，所以「停在 `/s/<id>` 上被系统重建」
  加载的是启动页，它转写成待跳转记录再送进 `web.html`。守门测试
  `app/shell/notification-tap.test.ts`（8 条）盯着两头，删任一端都会红。
- **看护是多设备的**：`RunWatchService` 按 `deviceId` 维护多份 `Target`（每台一条
  `/api/events` SSE、一个线程、各自的 `lastStatus` / `knownRequests` / 标题表），JS 侧
  `startRunWatch(loadDevices(), { page })` 每 15s 轮询每台设备的 `/api/sessions/runtimes`，
  压成 id 签名，**只有集合变化才重新下发** —— 否则每次轮询都会掐断 SSE，服务端重连要重推
  57KB state。新增单目标路径已删，`RunWatch.start` 收 `{ targets, page }`。通知 id 必须是
  `(deviceId + ":" + sessionId).hashCode()`，只用 `sessionId` 会让两台设备的同名会话互相覆盖，
  点回来还会开错机器。
- **通知的静音范围只算聊天页**：前台服务记一个 `page`（`chat` / `launcher`，由
  `app/shell/run-watch.ts` 的 `startRunWatch(device, { page })` 透传），原生判定是
  `!appVisible() || !"chat".equals(page)`。理由：停在聊天页时用户正盯着对话，弹通知是噪音；
  而启动页没有任何会话视图，跑完了不提醒就真错过。别再退回「JS 报 `visibilityState`」那套
  （WebView 的 visible 与 App 是否在前台不是一回事，混用过一次，结果是别的页面停留时照静音）。
  另外别在 `pagehide` 上停服务：从聊天页退回启动页也是一次换页，停掉之后「在启动页等结果」
  就彻底没有通知（0.4.9 修的就是这条）。服务跟着进程走，闲着时 `startRunWatch` 自己会停。
- **启动页的页面画布必须保持透明**：相机预览（`BarcodeScanner.startScan`）铺在 WebView **下面**，
  `html` / `body` / WebView / 窗口任何一层有底色，取景框里就是一块纯色。踩过两次：一次是
  `.scan-overlay` 自己带底色，一次是 `safe-area-css.ts` 无条件给 `html` 铺画布色
  （`--color-bg-app` 在启动页没定义 → 兜底白）。现在画布底色改成显式传参（只有聊天页传），
  扫码期间 `enterScannerMode()` 再对四层各断言一次透明（原生侧是
  `NavigationBar.setScannerTransparency`）。守门测试 `app/shell/scanner-transparency.test.ts`。
- **别信 `env(safe-area-inset-*)`**：Android WebView 在 edge-to-edge 布局下会把它报成 0
  （页面明明被手势条压住），上游 `WebChatApp.tsx:1299` 的 `pb-[env(safe-area-inset-bottom)]`
  于是等于没写、输入框被小横条盖住。壳的解法是 `NavigationBar.getInsets()`（原生量 WebView
  与系统栏的**实际重叠**，dp）→ `app/shell/safe-area.ts` 只把 env() 没算到的差值写进那几条
  utility 的 padding（`app/shell/safe-area-css.ts` 生成样式表，有单测）。退出 e2e 成功的机型
  重叠为 0，不会被推走；键盘收起/旋转后 150ms 重算。
- **`fixed`/`absolute` 覆盖层要单独让位**：右侧栏（`WebWorkspaceDrawer.tsx:28` 的 `aside`）、
  全屏文件预览（`WebFilePreview.tsx:305`）、底部弹层（`WebBottomSheet.tsx:80`）的坐标原点在
  整个视口上，主壳的 `pt/pb-[env(...)]` 管不到它们 —— 右侧栏的标题栏（含关闭 X）会整个顶进
  状态栏里看不见。`app/shell/safe-area-css.ts` 的 `OVERLAY_RULES` 按 class 字符串精确命中这三种
  面板补内边距；上游改 class 就会静默失效，所以 `app/shell/overlay-selectors.test.ts` 直接读
  vendor 源码对账，同步上游后测试红一次就能发现。
- **靠 CSS 变成覆盖层的也要让位**：左侧会话栏 `.chat-list-pane` 在 ≤760px 时被 `web.css:422`
  改成 `position: fixed; inset: 0 auto 0 0`，同样绕开主壳内边距。`MOBILE_OVERLAY_CLASSES`
  在 `@media (max-width: 760px)` 里补上下内边距（桌面宽度它只是 flex 子项，补了会双份）。
- 状态栏图标不会自己跟主题：主题只是 `documentElement.dataset.theme`，图标明暗在系统手里。
  `app/shell/system-bars.ts` 用 `MutationObserver` 盯 `data-theme`，同步 `StatusBar.setStyle`
  （`Style.Dark` = 浅色图标）与 `NavigationBar.setColor({ lightIcons })`；启动页固定深色。
  真机排查：`__pideckDebug.insets()` / `__pideckDebug.safeAreaCss()`。

- 返回键**不能**用 `canGoBack ? history.back() : …`：WebView 里它几乎永远是 true —— 上游给每个覆盖层压一条
  `__pideckDismiss` 记录（`web/useDismissOnBack.ts:20`），选中会话还会 `pushState("/s/<id>")`
  （`web/WebChatApp.tsx:228`），于是返回手势变成「浏览器后退」：关掉面板后还在会话之间一格一格退。
  现在走 `app/shell/back.ts` 的 `BACK_LAYERS`：按「最上面一层」逐层关，关无可关才回启动页；
  启动页连按两次才退出。层级顺序 = Radix 弹层（安全等级/模型/提示词 popover，合成 pointerdown
  点外部，失败再补 Escape）→ `role=dialog`（`WebBottomSheet` 家族、搜索/技能面板，合成 Escape）
  → 上游压了 `__pideckDismiss` 的右侧栏/文件预览（`history.back()`）→ 左侧会话栏
  （`.mobile-sidebar-backdrop.is-open` 存在时点 `.mobile-sidebar-close`）。
  只有右侧栏和文件预览会自己订阅返回，**其余层不压历史记录**，所以必须由壳这边按选择器命中；
  `app/shell/back.test.ts` 对这些选择器逐个读 vendor 源码对账，上游改 class 就红。

## 动效：`app/shell/motion.ts`（0.4.11 起）

上游的动效按鼠标/大屏调，手机上偏「硬」。移动端的手感统一放在 `app/shell/motion.ts`：
运行向往 `<head>` 末尾追加一张样式表（靠「后出现」压过上游同优先级规则，同名 `@keyframes` 也是后者覆盖）。

- **不要改 `vendor/`** 里的动画：下次同步上游会丢。要调就加在 `MOTION_RULES`。
- 缓动与三档时长在 `MOTION_VARS`（`enter` / `exit` / `fast 130ms` / `base 200ms` / `slow 280ms`）。
- 每条规则的 `where` 写明上游对应位置；`shell/motion.test.ts` 会读 vendor 源码对账，
  上游换 class 或 keyframes 名就失败，不会静默失效。
- 约束：手机上是长列表 + 贴底追滚，**新消息入场别用大位移**（10px 就会跟追滚叠加成弹一下）；
  keyframes 只动 `opacity` / `transform`，别碰会触发重排的属性。

## 回合统计行：`app/shell/turn-stats.ts`（0.4.12 起）

上游 Web 端**没有**桌面端那条统计带子（`components/session/ComposerStatsLine.tsx` 只挂在桌面 `ComposerArea`），
`/api/state` 的 `runtimes` 也只有 `status`。手机上的「耗时 / tok / tok/s」由壳自己算：

- 数据：`GET /api/sessions/:id/messages/page?pageSize=1`，取最后一条 assistant 的
  `meta = { usage: { outputTokens, inputTokens, cacheReadTokens, cost }, durationMs, startedAt, endedAt }`
  —— 与桌面端结算同一条回复用的是同一份原始数。
- 时机：上游 composer 在 `streaming` 时渲染停止按钮（`data-testid="web-composer-stop[-compact]"`），
  这一跳消失就是本轮结束；**不要**去轮询 `/api/state`（56KB）来判定。
- 口径：`meta` 里没有首字时刻，所以只报**端到端** `outputTokens ÷ durationMs`（桌面 `endToEndTps` 同口径），
  不冒充桌面那个扣掉首字等待的流式 tps。文案里写「端到端」。
- 渲染：作为 `form.composer` 的最后一个子节点注入（不碰 `vendor/`），样式与桌面一样是 11px 三级文字色。
- 验证：`npm run smoke` 现在会挑一个会话把地址栏指到 `/s/<id>`，并断言这一行出现了真实数字。

## 改动完成后必须跑的验证

```bash
cd app && npm run verify          # typecheck + node --test shell/*.test.ts + vite build
cd app && npm run smoke           # jsdom 里跑 dist，连本机 8765 的真桌面端（需要桌面端开着 Web 服务）
```

`npm run smoke`（`app/scripts/smoke-ui.mjs`）是唯一能在没有手机时验证「vendored 界面能不能起来」的
手段：它用 jsdom 执行 `dist/web.html` 的产物、注入设备与令牌、把相对 `/api/*` 补成绝对地址，
断言出现输入框。没给 `--token` 时从 `%APPDATA%/PiDeck/settings.json` 的 `webServiceToken` 读。

出包前还要确认 Android 侧能编过：`cd app && npm run apk:release`（含 `cap sync` 与
`assembleRelease`），然后 `npm run publish:apk -- --release`。

`app/shell/*.test.ts` 覆盖的是壳自己的纯逻辑（设备存储、配对输入、SSE 分帧）。界面行为不再有
客户端侧测试——它属于上游，上游自己有 1,862 个测试文件。


## 原生客户端（0.5.x，2026-11 起接管）

上面「界面栈 / 动效 / 回合统计行」几节描述的是 0.4.x 的 WebView 壳（`app/vendor/` + `app/shell/`），
**已经不是当前实现**。现在的客户端是纯 Kotlin + Compose，源码在
`native/app/src/main/java/dev/pideck/nativeapp/`，版本号与出包约定见本文件开头两节（改动仍走
`native/app/build.gradle.kts` 的 `versionCode`/`versionName`，规则见开头「版本号」一节）。

两个踩过的坑，改这两处前先看：

- **顶栏那排胶囊的宽度必须有一个「可伸缩」的**（`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/ChatScreen.kt`，
  `TopAppBar` 正下方那行 `Row`（模型 / 思考档位胶囊））：模型胶囊外面套一层 `Box(Modifier.weight(1f))`、内部封顶 188dp，
  右侧（上下文胶囊 + 历史按钮）才永远有位置。全都给固定宽度时，上下文胶囊一出现，
  历史按钮就只量到 0 宽度、直接看不见（踩过一次，用户报「查看上下文后历史按钮被顶掉」）。
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
  注意**会话行有三份布局**：`ui/sessions/SessionsScreen.kt` 的 `SessionRow`（全部会话）、
  `ui/home/HomeScreen.kt` 的 `RecentSessionRow`（首页「最近的会话」）、
  `ui/chat/SessionDrawer.kt` 的 `DrawerSessionRow`（聊天页左侧抽屉）—— 0.5.26 改了第一处，
  0.5.27 补了第二处，用户又发现抽屉里没有（0.5.28 补齐）。加任何行级元素都要三处一起改，
  改完在三个入口各看一眼，别只测「全部会话」。
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
- **ViewModel 里的 `device` 是异步来的，界面上的 `LaunchedEffect(Unit)` 比它早**
  （`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/ChatViewModel.kt` 的 `deviceReady`）：
  `init` 要先去 `DeviceRepository` 把设备/令牌读出来才知道往哪发请求，而 Composable 的
  `LaunchedEffect(Unit)` 在同一帧就跑了。以前 `loadModels()` 直接 `device ?: return`，
  于是模型表第一次永远是空的，必须手动点刷新（0.5.35 修的就是这个）。
  **凡是「界面自动触发、需要 device/token」的加载函数，都先 `deviceReady.await()` 再取值**，
  别在函数顶部 `device ?: return`；用户在点击后才触发的（发送、停止、翻页）可以不改，
  那时设备早就在了。
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
  **不要**放进统计行：那一行是速度（首字 / tok/s），缓存率是成本；也不要放进会话列表 ——
  不同会话的命中率不可比（新开会话必然低）。
  刷新时机只有「打开上下文弹层 / 点重新读取」，不跟着每轮跑：翻完一个会话最多 10 页请求，
  而命中率一轮才变一次。字段全缺（acp 的 meta 是 null）时显示 `—`，**有输入但没 cacheRead 是真 0%**，
  两者要分开（`cacheHitOf()` 返回 null vs 0.0）。
- **侧栏「活动」段**（`native/app/src/main/java/dev/pideck/nativeapp/ui/chat/DrawerActivePane.kt`，
  0.5.37）：抽屉顶上一排 pill 分「活动 / 聊天」两段，对应桌面端左侧栏那个 `Tabs`
  （`active` / `chats`，`projects` 还没做）。`DrawerTabState` 是**单例**不是 `rememberSaveable` ——
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
  —— 那三处各自安静（见上面「会话行有三份布局」）。纯函数有单测 `DrawerActivePaneTest`（8 条）。
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

验证：`cd native && ./gradlew --offline -q :app:testDebugUnitTest`，出包
`./gradlew :app:assembleRelease`，产物在 `native/app/build/outputs/apk/release/app-release.apk`，
拷成 `apk/PiDeck-native-<版本>-release.apk` 后更新 `apk/SHA256SUMS.txt` 与 `apk/index.html`
（页面只写当前版本，旧包删掉）。逐版改动记在根目录 `CHANGELOG.md`（0.5.0 起），
每次发版往里加一段，别只靠提交信息。
