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
> 出包流水线（`.github/workflows/android-release.yml`）跑的是 `npm ci` + `npm test` + `vite build` +
> `cap sync android` + `assembleRelease`，没有 flutter 步骤（0.3.0 起）。流水线的 runner 是 Node 22，
> `node --test shell/*.test.ts` 依赖 Node 22.18+ 的默认 type stripping，别把 node 版本降到 22 以下。
> 流水线里有 typecheck / test，所以**本地先跑绿再打 tag**，否则 tag 白打。
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

