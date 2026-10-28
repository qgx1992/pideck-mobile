# pideck-mobile

把 PiDeck 桌面端的「Web 服务」变成一个**可独立发 App 的客户端核心**：配对、状态同步、发消息、流式渲染、运行时控制、ask 卡片应答，全部只依赖 `fetch` + `AbortController`。

> 适用对象：想做一个自己的手机 App 连电脑端 PiDeck 的人。
> 不包含：UI 组件、导航、原生壳。这些由你在 React Native / Capacitor / Flutter 里选型。

已对照 **PiDeck 0.9.7（Windows）** 的实机行为核验，`docs/web-api.md` 是整理出的接口契约初稿。

---

## 仓库里的两个可运行产物

| 目录 | 是什么 | 怎么跑 |
|---|---|---|
| `src/`（本目录） | 协议与状态核心 + 探针 CLI | `npm test` · `npm run probe -- health --pair "<链接>"` |
| `app/` | 手机客户端外壳（Vite + Capacitor） | `cd app && npm run dev` |

`app/` 通过 `@core` 别名直接引用本目录的 `src/`，不复制代码。

## 目录结构

```
src/
├─ api/
│  ├─ sse.ts       增量 SSE 解析 + 流式 UTF-8 解码（含 Hermes 无 TextDecoder 的降级）
│  ├─ frames.ts    会话流帧 → ChatBlock[]（文本/思考/工具）
│  ├─ client.ts    PiDeckClient：全部已核实端点 + 错误分类
│  ├─ errors.ts    auth / offline / runtime / http / protocol 五类错误
│  └─ backoff.ts   指数退避 + 抖动
├─ state/store.ts  全局状态仓（SSE 优先，不支持流式时退化 ETag 轮询）
├─ runtime/target.ts  {sessionId, agentId, runtimeGeneration} 三元组
├─ session.ts      SessionChannel：常驻流 + 发送 + 断线重连
├─ pairing.ts      二维码/手填链接 → { baseUrl, token }
└─ cli/probe.ts    零依赖探针 CLI（验证协议用）
docs/web-api.md    接口契约初稿（可整理后向 PiDeck 提 PR）
tests/             node --test 单测（50 条）
```

---

## 快速开始

### 1. 装依赖

```bash
npm install
```

### 2. 先在电脑上准备好入口

桌面端 → 设置 → Web 服务 → 打开「局域网 Web 服务」，建议保持「启用访问令牌」开启。
然后用「扫码访问」的二维码/链接（里面已带 token），或走 Tailscale 拿到 `https://xxx.ts.net` 再自己拼 `?token=`。

### 3. 用探针验证（不写代码就能确认链路通）

```bash
# 探活 + 确认对面确实是 PiDeck
node src/cli/probe.ts health --pair "http://192.168.1.20:8765/?token=<令牌>"

# 看项目/会话/运行时概览
node src/cli/probe.ts state --pair "..."

# 发一条消息并实时打印流式输出
node src/cli/probe.ts send --pair "..." --session <sessionId> "帮我看下 CI 为什么挂"

# 只看某个会话的实时流
node src/cli/probe.ts tail --pair "..." --session <sessionId>

# 中止当前轮
node src/cli/probe.ts stop --pair "..." --session <sessionId>
```

也支持 `--base`/`--token` 或环境变量 `PIDECK_BASE` / `PIDECK_TOKEN`。

### 4. 测试与类型检查

```bash
npm test        # node --test（Node ≥ 22.6 直接跑 .ts）
npm run typecheck
```

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

### 回答 ask_question / 权限确认卡片

```ts
for (const pending of store.pendingFor(sessionId)) {
  // pending.method: select | confirm | input | editor | batch_ask
  await client.respondUi(pending, { value: "允许" });
}
```

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

---

## React Native 注意点

- **默认 `fetch` 不支持流式响应体**（`response.body` 为空）。二选一：
  - 用 `expo/fetch`（SDK 52+）替换 `fetchImpl`
  - 或改用 `react-native-sse`，把事件喂给 `SseParser`
- 判断方式：`streamSse` 会抛 `kind: "protocol"` 的错误，`StateStore` 收到后自动退化为轮询。
- Hermes 早期版本无 `TextDecoder`：`Utf8StreamDecoder` 已内置纯 JS 回退。
- 后台（尤其 iOS）会挂起 SSE：回到前台时用 `onRecovered` → 重拉历史 + `store.refresh()`。

## Capacitor 注意点

- 直接把 PiDeck 官方 Web 端当作 `webDir` 打包时，同源请求不触发 CORS，最简单。
- 若要自研 UI，WebView 属于**跨域**：预检只允许 `content-type`，用 `Authorization` 会失败。
  → 用 `?token=`（仅 GET 有效）或加原生 HTTP 插件走原生栈。

---

## 已知限制

- `/api/*` 是 PiDeck 的内部接口，**没有版本号也没有兼容承诺**。升级桌面端后请重跑 `npm test` 与探针。
- `/api/health` 不返回版本，客户端无法判断对面版本；建议向上游提 PR 增加 `version`。
- 服务端没有推送通道：App 在后台无法收到「任务完成」通知，只能前台 SSE 或自己做轮询。
- 文件 / Git / 提示词等端点只做了透传，返回形状尚未逐字段固化。

## 升级 PiDeck 后的回归清单

1. `node src/cli/probe.ts health` → `service` 仍是 `PiDeck`
2. `state` → `projects/sessions/runtimes/pendingUiRequests` 四个字段都在
3. `tail` 一个历史会话 → 帧仍是 `data: {...}` + `[DONE]`
4. `send` → 文本 delta 正常、`finish` 后状态正确
5. `npm test` 全绿
