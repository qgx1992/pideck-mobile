# pideck-mobile

把 [PiDeck](https://pideck.caoayu.top/) 桌面端的「Web 服务」变成一个**可独立发 App 的客户端**：配对、状态同步、发消息、流式渲染、运行时控制、ask 卡片应答。

> **桌面端**：<https://pideck.caoayu.top/> · 源码 <https://github.com/ayuayue/PiDeck>
> 这个仓库只是手机客户端，Agent 行为、模型调用、会话读写都由 pi 原生负责。

仓库里有两个产物：`src/` 是框架无关的协议核心，`app/` 是可直接打 APK 的 Capacitor 客户端。全部只依赖 `fetch` + `AbortController`，无运行时依赖。

已对照 **PiDeck 0.9.7（Windows）** 的实机行为核验，`docs/web-api.md` 是整理出的接口契约。

---

## 两个可运行产物

| 目录 | 是什么 | 怎么跑 |
|---|---|---|
| `src/` | 协议与状态核心 + 探针 CLI（**69 条测试**，独立于客户端） | `npm test` · `npm run probe -- health --pair "<链接>"` |
| `app/` | 手机客户端（**vendor 桌面端 Web 客户端** + Capacitor 7 壳，**16 条测试**） | `cd app && npm run dev` / `npm run apk:release` |

0.4.0 起 `app/` **不再引用** `src/`：界面与协议都用桌面端自己的那一份（vendor 进来的
`src/renderer/src/web/` + `src/shared/`），`src/` 只作为独立探针 / 契约参考保留。

## 目录结构

```
src/                        协议核心 + 探针 CLI（与 UI 无关；现在只有探针和测试在用）
├─ api/                     SSE 解析、帧 → ChatBlock[]、PiDeckClient、错误分类、退避
├─ state/store.ts           全局状态仓（SSE 优先，退化 ETag 轮询）
├─ session.ts               SessionChannel：常驻流 + 发送 + 断线重连 + 静默断流看门狗
└─ cli/probe.ts             零依赖探针 CLI

app/                        手机客户端：桌面端界面 + 原生壳
├─ vendor/pideck/           桌面端 0.9.9 的 src/renderer/src + src/shared（原样 vendor，不改）
│  └─ src/renderer/src/web/ 这就是手机上的界面本身（8,603 行，桌面端同一份代码）
├─ shell/                   Capacitor 壳
│  ├─ device.ts             设备注册表 + 配对链接解析 + 令牌键（`pideck-web-token`）
│  ├─ boot.ts               进聊天页前：写令牌 → 装 fetch/EventSource 桥 → 禁 SW → 起看护
│  ├─ fetch-bridge.ts       相对 `/api/*` → 设备地址；SSE 走原生插件，其余走 CapacitorHttp
│  ├─ native-sse.ts         SsePlugin 的 fetch 适配（POST 也能流式）+ EventSource 适配
│  ├─ launcher.html/.ts     配对与设备管理页（= `dist/index.html`）
│  ├─ run-watch.ts          有会话在跑就拉起前台服务，跑完发通知
│  └─ downloads.ts / external-links.ts   blob 下载转原生保存 / 外链交给系统浏览器
├─ android/                 SsePlugin（GET/POST 流式）、RunWatchService、FileSaver、NavigationBar
├─ scripts/                 构建、发布、二维码、jsdom 冒烟
└─ assets-src/              图标源图（1024 / 512 / 192）

docs/web-api.md             接口契约（0.9.7 时整理，端点仍一致）
```

---

## 0.4.0：为什么又走回「用桌面端的界面」

前三次都是「在手机上再实现一份」：自研 UI + 自研协议（0.1–0.2）、Flutter add-to-app（0.3.0）、
借桌面端网页版做本地转发（0.3.1–0.3.11）。代价很清楚：界面的每一个细节都要在两边各写一遍，
桌面端修好的问题手机端不知道；而借网页版那条路要补的东西（系统栏、返回键、文件选择、外链）
全在页面边上，补丁越叠越脆。

这次换了个方向：桌面端本身带一个给浏览器用的 Web 客户端（`src/renderer/src/web/`），它只通过
HTTP 接口工作（`Authorization: Bearer`），根本不依赖 Electron。于是把它整套 vendor 进来直接跑，
壳只补 WebView 做不到的四件事。

- **界面 100% 复用**：会话流、工具卡、Todo、模型 / 思考 / 计划切换、导出会话都是同一份代码。
- **协议 0 重复**：不存在「手机端实现的协议与桌面端不一致」这类问题。
- **壳只做四件事**：原生 SSE（`POST /api/chat` 的流式响应）、后台跑完发通知、外链走系统浏览器、
  blob 下载转原生保存。再加一个配对页（多设备切换、连通测试、扫码）。
- **代价**：仓库里多了 17 MB vendor 源码；同步上游要手动做一次 3-way diff（`app/VENDORED.md`）。

## App 现在能做什么

- **配对**：启动页扫码（Google code scanner，无 GMS 机型退到 App 内取景框）或粘贴链接；
  多台设备存本地注册表，列表里能看在线 / 鉴权失败、能改名、能一键切换。
- **聊天**：桌面端那一套界面 —— 会话流（文本 / 思考 / 工具卡 / Todo / 提问卡）、模型与思考档位、
  计划模式、上下文用量与压缩、斜杠命令、@ 文件引用、图片、导出会话 HTML。
- **后台**：会话在跑就拉起前台服务，跑完弹通知；切回前台立刻续上。
- **落盘 / 外链**：导出与下载走原生保存（「下载/PiDeck」）；回答里的链接交给系统浏览器。
- 不做 iOS 原生壳（前台服务在 iOS 上没有等价物，得另做一套设计）。

## 快速开始

### 1. 装依赖

```bash
npm install          # 协议核心
cd app && npm install # 客户端
```

### 2. 电脑上准备好入口

桌面端 → 设置 → Web 服务 → 打开「局域网 Web 服务」，建议保持「启用访问令牌」开启。
然后用「扫码访问」的二维码/链接（里面已带 token），或走 Tailscale 拿到 `https://xxx.ts.net` 再自己拼 `?token=`。

### 3. 用探针验证（不写代码就能确认链路通）

```bash
node src/cli/probe.ts health --pair "http://192.168.1.20:8765/?token=<令牌>"
node src/cli/probe.ts state --pair "..."
node src/cli/probe.ts send --pair "..." --session <sessionId> "帮我看下 CI 为什么挂"
node src/cli/probe.ts tail --pair "..." --session <sessionId>
node src/cli/probe.ts stop --pair "..." --session <sessionId>
```

也支持 `--base`/`--token` 或环境变量 `PIDECK_BASE` / `PIDECK_TOKEN`。

### 4. 测试与类型检查

```bash
npm test && npm run typecheck                 # 核心：69 条
cd app && npm run verify                      # 客户端：16 条 + typecheck + vite build
cd app && npm run smoke                       # jsdom 里跑 dist，连真桌面端，断言界面起得来
```

### 5. 打包 APK

```bash
cd app
npm run apk:release            # build web → cap sync → gradlew assembleRelease
npm run publish:apk -- --release   # 复制到项目根下的 apk/ 并刷新 SHA256SUMS.txt
```

需要 JDK 21 + Android SDK。`app/README.md` 里记了本机环境路径、镜像配置与踩过的坑。

---

## 在 App 里怎么接（`src/` 协议层，0.4.0 起客户端不再用它）

> 下面这些是 `src/` 的用法，探针 CLI 与自建客户端都靠它。手机端 0.4.0 起直接跑桌面端自己的
> Web 客户端，不再经过这一层——留在这里是因为它仍是「接口契约的可执行版本」。

```ts
import { PiDeckClient, StateStore, SessionChannel, parsePairingInput } from "pideck-mobile";

// 1) 扫码拿到链接 → 解析出 baseUrl + token（token 存 Keychain/Keystore，别写日志）
const { baseUrl, token } = parsePairingInput(qrText);

// 2) 握手：/api/health 免鉴权，用来确认对面是 PiDeck
const client = new PiDeckClient({ baseUrl, token });
const health = await client.health();
if (health.service !== "PiDeck") throw new Error("配对地址不对");

// 3) 全局状态：项目 / 会话 / 运行时 / 待应答卡片
const store = new StateStore({ client, onStatus: (s) => setConnState(s) });
store.subscribe((state) => render(state));
store.start();

// 4) 会话通道：连流 → 发消息 → 边收边渲染
const channel = new SessionChannel({
  client,
  sessionId,
  onUpdate: (c) => renderBlocks(c.reducer.blocks),
  onRecovered: () => reloadHistory(), // 重连后补拉一次历史，避免漏帧
});
channel.start();
await channel.send("帮我看下 CI 为什么挂");

// 5) 停止本轮（target 必须从最新状态取）
const target = store.targetOf(sessionId);
if (target) await channel.stopRun(target);
```

### 回答 ask 卡片（两种格式，别弄混）

```ts
// 单题：value 就是原始值
await client.respondUi(pending, { value: "允许" });      // select / input / editor
await client.respondUi(pending, { confirmed: true });     // confirm
await client.respondUi(pending, { cancelled: true });     // 取消

// batch_ask：必须提交 JSON 字符串，否则扩展 JSON.parse 失败会判为「已取消」
await client.respondUi(pending, {
  value: JSON.stringify({
    answers: [
      { id: "q1", type: "select", value: "test", label: "测试", wasCustom: false },
    ],
  }),
});
```

`batch_ask` 的格式来自桌面端 `serializeBatchAnswers`：`value` 是机器值、`label` 是显示名、`wasCustom` 标记用户是否自己输入。

### 只想一次性拿完整输出

```ts
import { collectRun } from "pideck-mobile";
const reducer = await collectRun(client, sessionId); // 等到 finish
console.log(reducer.blocks);
```

---

## 设计决策（为什么不是「照着接口写个 fetch 就行」）

1. **两套 SSE 别混用**：`/api/events` 推整份状态快照；`/api/sessions/:id/stream` 推 UI 消息帧。
   两者的重连语义完全不同，混在一起会让「状态刷新」和「正文增量」互相打架。
2. **重连不自己拼文本**：服务端重连会回放当前轮，客户端再拼断线期间的 delta 必然重复。
   `SessionChannel` 只在 `onRecovered` 提示 UI 重新拉历史。
3. **target 不缓存**：`runtimeGeneration` 会因会话重启自增，旧 target 一律 409。
   `StateStore.targetOf()` 每次从最新快照取。
4. **未知帧不抛错**：PiDeck 有新帧类型时，客户端应该降级而不是白屏。
5. **错误分五类**：`auth`（重新配对）/ `offline`（重连）/ `runtime`（刷新状态）/ `http`（提示）/ `protocol`（代码 bug），
   UI 分支才有依据。
6. **没有 Last-Event-ID**：这是服务端现状，不是省事；所以重连流程里一定有「重新对账」这一步。
7. **batch_ask 走 JSON**：`ask_question` 工具把整包问题塞进 `ui.input()` 的 title，
   扩展拿到字符串后直接 `JSON.parse`——提交人类可读文本会被当成「取消」。

---

## React Native 注意点（历史记录：0.1 时评估过，没走这条路）

- **默认 `fetch` 不支持流式响应体**（`response.body` 为空）。二选一：
  - 用 `expo/fetch`（SDK 52+）替换 `fetchImpl`
  - 或改用 `react-native-sse`，把事件喂给 `SseParser`
- 判断方式：`streamSse` 会抛 `kind: "protocol"` 的错误，`StateStore` 收到后自动退化为轮询。
- Hermes 早期版本无 `TextDecoder`：`Utf8StreamDecoder` 已内置纯 JS 回退。
- 后台（尤其 iOS）会挂起 SSE：回到前台时用 `onRecovered` → 重拉历史 + `store.refresh()`。

## Capacitor 注意点

WebView 是跨域环境，而 PiDeck 的 CORS 有两个限制（已在 `app/src/platform/fetch.ts` 处理）：

1. 预检只放行 `content-type`，不放行 `authorization` → GET 用 `?token=`，POST 走原生栈。
2. `workspace` 路由（files / prompts / skills / git）**实际响应不带 CORS 头** → 这些请求必须走 `CapacitorHttp`。

分流规则：**SSE 走原生 `SsePlugin`**，其余全部走 `CapacitorHttp`（没有 CORS 概念）。

- `SsePlugin`（`app/android/app/src/main/java/dev/pideck/mobile/SsePlugin.java`）用 `HttpURLConnection` 读流，按 16ms 窗口合并成 `sseOpen / sseData / sseEnd / sseError` 四个桥事件；
  JS 侧（`app/src/platform/sse-stream.ts`）把事件拼成 `ReadableStream`，交给 `Response`。
- 上游零改动：`client.streamSse` 只认 `response.ok` / `response.body.getReader()`，字节流还是原来的 `Utf8StreamDecoder`。
- 拿不到插件（浏览器开发 / 手机装的是老包）时 `isNativeSseAvailable()` 为假，自动退回 WebView 的 `fetch`，不会断流。
- 原生侧不设读超时（`readTimeoutMs = 0`）：假死交给 `SessionChannel` 的 50s 看门狗，服务端每 15s 一次 `: ping` 喂活它。

另外两处只有在原生侧才能做的事：

- **返回键**：Capacitor 的 `BridgeActivity` 不拦返回键，按下会直接 `finish()` 回桌面。
  装 `@capacitor/app` 并注册 `backButton` 监听后才会交给 JS。
- **后台提醒**：`RunWatchService`（`dataSync` 类型前台服务）守 `/api/events`，
  因为 cached 进程会被冻结。服务必须在 App 仍可见时启动（Android 12+ 禁止后台启动前台服务），
  所以「发送消息」的那一刻就乐观拉起。

---

## 已知限制

- 多设备是「切换」而不是「合并时间线」：启动页里按设备进入，通知按当前设备聚合。
- 上游 Web 客户端改版后需要手动同步 vendor（见 `app/VENDORED.md`）。

- `/api/*` 是 PiDeck 的内部接口，**没有版本号也没有兼容承诺**。升级桌面端后请重跑测试与探针。
- `/api/health` 不返回版本，客户端无法判断对面版本；已向上游提 issue 建议增加 `version`。
- 服务端没有推送通道。App 切后台会被系统冻结（WebView 里的 SSE 与定时器全停），
  因此后台提醒由**原生前台服务**守 `/api/events` 实现，代价是运行期间通知栏有一条常驻的
  「正在等待任务完成…」。深度 Doze 下前台服务也可能被限流。
- 通过 Web API 删除会话后桌面端列表不会立即刷新（上游缺 IPC 广播，见 `docs/issue-delete-sync.md`）。
- DSH 会话的历史读取依赖 DSH runtime；runtime 未就绪时该接口返回 500。

## 许可证

**PolyForm Noncommercial License 1.0.0** —— 见 [LICENSE.md](./LICENSE.md)。

一句话版：**可以自由用于个人学习、研究、非营利场景，可以修改和分发；不可商用。**
商业使用（包括公司内部生产环境、付费产品、代客户部署等）需要单独授权。

选这个而不是 MIT：这个项目是和 PiDeck 配套的个人工具，希望代码公开可读可用，
但不希望被直接包装成商业产品售卖。

## 升级 PiDeck 后的回归清单

1. `node src/cli/probe.ts health` → `service` 仍是 `PiDeck`
2. `state` → `projects/sessions/runtimes/pendingUiRequests` 四个字段都在
3. `tail` 一个历史会话 → 帧仍是 `data: {...}` + `[DONE]`
4. `send` → 文本 delta 正常、`finish` 后状态正确
5. 两套测试全绿：`npm test` + `cd app && npm test`
