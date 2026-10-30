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
| `src/` | 协议与状态核心 + 探针 CLI（**56 条测试**） | `npm test` · `npm run probe -- health --pair "<链接>"` |
| `app/` | 手机客户端（Vite + Capacitor，**429 条测试**） | `cd app && npm run dev` / `npm run apk` |

`app/` 通过 `@core` 别名直接引用本目录的 `src/`，不复制代码。

## 目录结构

```
src/                        协议核心（与 UI 无关，RN / Capacitor / Flutter 都能用）
├─ api/
│  ├─ sse.ts                增量 SSE 解析 + 流式 UTF-8 解码（含 Hermes 无 TextDecoder 的降级）
│  ├─ frames.ts             会话流帧 → ChatBlock[]（文本/思考/工具）
│  ├─ client.ts             PiDeckClient：全部已核实端点 + 错误分类
│  ├─ errors.ts             auth / offline / runtime / http / protocol 五类错误
│  └─ backoff.ts            指数退避 + 抖动
├─ state/store.ts           全局状态仓（SSE 优先，不支持流式时退化 ETag 轮询）
├─ runtime/target.ts        {sessionId, agentId, runtimeGeneration} 三元组
├─ session.ts               SessionChannel：常驻流 + 发送 + 断线重连 + 静默断流看门狗
├─ pairing.ts               二维码/手填链接 → { baseUrl, token }
└─ cli/probe.ts             零依赖探针 CLI

app/                        手机客户端（Capacitor 7 / Android）
├─ src/main.ts              接线：配对 / 主界面 / 发送 / 停止
├─ src/platform/            fetch 分流、主题、安全区、导航栏、配对持久化、扫码/相册解码
├─ src/ui/                  侧栏、对话、Markdown、ask 卡片、@ 引用、/ 命令、工作区、
│                           模型选择、上下文用量、任务清单
├─ android/                 Android 工程（导航栏插件、后台看护服务、图标定制）
├─ scripts/                 APK 构建、图标生成、验证脚本
└─ assets-src/              图标源图（1024 / 512 / 192）

docs/web-api.md             接口契约（可整理后向 PiDeck 提 PR）
docs/issue-*.md             已提交给上游的两个缺陷草稿
```

---

## App 已实现的功能

**配对**
- 扫码连接：相机扫桌面端「扫码访问」的二维码，识别到就直接握手
- 两条扫码路线自动切换：优先 Google 原生扫描界面，无 Google Play Services 的机型
  （华为等）退到 App 内自绘取景框 + 自带 ML Kit 模型
- 从相册/截图选一张二维码图解码（纯 JS，不需要相机权限）
- 扫码结果会清洗：去引号/零宽字符/多行说明/整串 URL 编码，再交给协议层校验
- 也可以直接粘贴链接，或手填地址 + 令牌
- **一机连多台**（最多 5 台同时实时）：每台独立 `PiDeckClient` + `StateStore`，
  各自常驻一条 `/api/events`；顶栏设备 chip 切换，带运行中圆点与「别处有待答」红点
  - 侧栏「活动」把多台的运行中/最近会话混排，行上带设备徽章
  - 「项目」按设备分块，「聊天」合并各端的 Chat 会话
  - 点另一台设备上的会话会自动切过去再打开
  - 每台记住上次打开的会话；`usageCache` / 草稿 / 图片 / 模型偏好等 10 处缓存
    都按 `endpoint::id` 作用域，两台同 id 会话不会串台
  - 后台看护（原生单端点）自动挑「运行中会话最多的那台」当通知源

**侧栏**
- 顶部左侧是品牌，右侧两个按钮：**设置**（齿轮）与**新建会话**（+）
- 新建会话先弹「选择工作区」：Chat 置顶，其余按最近使用时间排序，带路径与会话数
- 顶部分三段（对齐桌面端）：**活动** / **聊天** / **项目**，选择会记住
  - 活动：上半区是还活着的运行时绑定的会话（在跑的带脉冲点），下半区是最近 10 个会话
  - 聊天：只显示内置 Chat 项目的会话（内置 Chat 不再占「项目」的位置）
  - 项目：工作区项目分组 + 组内会话
- 搜索忽略分段：直接给一张跨项目的扁平清单（行上带项目名）
- 项目分组 + 组内会话，按 `updatedAt` 倒序
- 默认只显示最近 6 个，其余折叠；展开/收起是显式意图，优先于自动展开
- 运行中的会话有绿色呼吸条与圆点；分组折叠时项目头也会显示
- DSH 会话带蓝色徽章与淡蓝底
- 长按会话/项目 → 重命名、复制、导出 HTML、删除、移除项目

**对话**
- 流式渲染，工具调用与思考收进「执行过程」折叠块（默认收起）
- **正文按 Markdown 渲染**：粗体/斜体/行内码/围栏代码块（带语言标签、复制、语法高亮）/
  有序无序与任务列表/表格/引用/标题，单换行按 GFM 断行
- 自动剥离正文里的 `<thinking>` 标签
- **消息里的图片**：从历史消息的 `parts` / `images` 里挖出来渲染成缩略图，点开看原图
- **长按单条消息**：复制 / 编辑 / 改完重发 / 从这里分叉 / 删除（删除需二次确认）
- **分支家族条**：会话被 fork 过后，对话上方显示家族链，点一下切到那一支
- 发送即本地回显，结束后重拉历史并清空流式区
- 会话切换保留各自草稿
- 输入框右下角「历史」按钮：列出可跳转的消息，点一条滚动定位并高亮
- 运行中三处动画标识：对话底部旋转圆环、顶部栏呼吸绿点、侧栏绿条脉冲

**输入框**
- `@` 文件引用（逐层浏览项目目录）、`/` 斜线命令（运行时命令 + 提示词库 + 内置），
  两者内嵌在输入框左下角
- 模型 / 思考档位切换（无运行时先自动激活），模型面板支持搜索（名称 / ID / 供应商）
- 上下文用量：环形百分比 + 明细面板（输入/输出/缓存/花费）+ 一键压缩上下文
- **供应商额度**：面板里手动查询余额/套餐用量（`GET /api/usage/:provider`），未开启时给引导
- **发图片**：相册/拍照选图，发送前自动压到长边 1600 / JPEG 0.82，最多 4 张，缩略图可单张移除
- **运行中排队 / 插话**：运行中且有内容时，输入框右下角「历史」左侧出现「排队」/
  「插话」按钮：点一下按当前模式发送（主按钮此时是「停止」），长按切换模式并弹提示。
  两种模式分别对应 `followUp`（等这轮结束）和 `steer`（立刻插进当前轮改方向）
- 任务清单：读 `GET /api/sessions/:id/todo`，显示进度与「进行中」，可折叠
- **子代理活动条**：读 `GET /api/sessions/:id/subagents`，显示类型/描述/状态与结果，5s 轮询
- 发送与停止合并为一个按钮，按会话状态切换

**右侧工作区**
- Git：分支、变更分组、提交历史
- 文件：逐层浏览、内容预览（>512 KB 不预览）、行尾 `@` 直接引用

**设置页**
- 外观（主题三档）、通知开关
- 连接与设备：列出所有已连接设备，点一下切换、长按重命名/停用/删除，可添加设备或断开全部
- pi 概况：可用模型列表（只读）、供应商额度入口、技能开关、扩展开关、DSH 插件列表
- 关于：客户端版本、服务地址、令牌脱敏展示、连接状态
- **写死的边界**：桌面端的「pi 配置管理」走 Electron IPC，Web 服务没有开放写接口，
  所以模型供应商密钥 / 模型目录 / Git / 终端 / 代理等只能回桌面端改（设置页里有说明）

**AI 提问卡片**
- `select`（选项带描述、可多选）、`confirm`、`input`、`editor`、`batch_ask`（逐题作答 → 审阅 → 统一提交）
- 允许留空：批量每题可「跳过」，单题按钮变「留空提交」
- 审阅页三态：未回答（红）/（留空）（灰）/ 实际内容

**通知**
- 任务完成 / 出错 / 中断、AI 新提问都会弹系统通知，点击跳回对应会话
- 同一会话多个提问合并成一条；正在看的会话不打扰
- **切到后台也生效**：原生前台服务守 `/api/events`，不受 WebView 冻结影响

**手势与系统**
- 左右抽屉滑动手势互斥；横向可滚动区域（宽表格、长代码行）不抢手势
- 返回键一次只退一步：面板 → 工作区 → 侧边栏 → 再按退出
- 主题三档（浅色 / 深色 / 跟随系统），状态栏与导航栏同步
- 图标以桌面端为基础重绘（深色渐变底 + 白色 Pi + 品牌蓝 i 点）

---

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
npm test && npm run typecheck                 # 核心：56 条
cd app && npm test && npm run typecheck       # 客户端：429 条
cd app && npm run verify:send                 # mock 发送链路（14 项）
```

### 5. 打包 APK

```bash
cd app
npm run apk           # build web → cap copy → gradlew assembleDebug
npm run publish:apk   # 复制到项目根下的 apk/ 并刷新 SHA256SUMS.txt
```

需要 JDK 21 + Android SDK。`app/README.md` 里记了本机环境路径、镜像配置与踩过的坑。

---

## 在 App 里怎么接

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

## React Native 注意点

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

分流规则：**SSE 留在 WebView**（要流式响应体），其余全部走 `CapacitorHttp`（没有 CORS 概念）。

另外两处只有在原生侧才能做的事：

- **返回键**：Capacitor 的 `BridgeActivity` 不拦返回键，按下会直接 `finish()` 回桌面。
  装 `@capacitor/app` 并注册 `backButton` 监听后才会交给 JS。
- **后台提醒**：`RunWatchService`（`dataSync` 类型前台服务）守 `/api/events`，
  因为 cached 进程会被冻结。服务必须在 App 仍可见时启动（Android 12+ 禁止后台启动前台服务），
  所以「发送消息」的那一刻就乐观拉起。

---

## 已知限制

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
