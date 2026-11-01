# 桌面端 ACP 功能研究：能否套进 PiDeck 手机端

> 研究对象：`ayuayue/PiDeck` main 分支（`package.json:129` → `"@agentclientprotocol/sdk": "1.4.0"`）。
> 方法：GitHub API 拉源码逐文件读（`src/main/acp/*` 20 个文件、`src/main/web/*`、`src/shared/types/acp.ts`、`src/main/ipc/acpIpc.ts`、`src/main/index.ts` 装配层、渲染层 ACP 元件）。
> 结论先行：**(a) 渲染兼容=可做且代价小；(b) 会话管理=可做，需服务端补 2~3 处；(c) 手机端自跑引擎=当前架构下不可行。**

---

## 1. ACP 到底是什么 / 桌面端怎么接的

- **就是 Zed 的 Agent Client Protocol**，用官方 TS SDK 包 `@agentclientprotocol/sdk@1.4.0`（`package.json:129`），协议版本常量 `export const ACP_PROTOCOL_VERSION = 1`（`src/main/acp/acpProtocol.ts`）。
- **传输 = stdio 上的换行分隔 JSON-RPC 2.0**（NDJSON）。`class AcpConnection extends EventEmitter`（`src/main/acp/AcpConnection.ts:64`）构造参数就是 `(stdin: NodeJS.WritableStream, stdout: NodeJS.ReadableStream)`；行泵复用 pi 的 `createNdjsonLinePump`，护栏 `LARGE_ACP_LINE_PARSE_CHARS`(`AcpConnection.ts:48`) / `MAX_ACP_LINE_BYTES`(`:51`)。**双向**：agent 也能向 client 发请求（`session/request_permission` 通过 `handleRequest` 注册，`AcpConnection.ts:98`）；`respond(id,…)`:149、`notify`:142、`respondError`:154。
  - `request(method, params?, timeoutMs = 60_000, onArm?)`（`AcpConnection.ts:115`）的 `timeoutMs` 语义是「无进展静默上限」而非总时长（`onArm` 给调用方 `refresh()` 句柄）——注释里的实测理由：CodeBuddy 单回合 283 条 update，第 10 分钟被墙钟掐断。
- **进程谁启动**：`spawnAcpProcess(tool: AcpToolConfig, cwd, deps)`（`src/main/acp/AcpProcessHost.ts:37`）
  - `spawn(invocation.command, invocation.args, { cwd, stdio: ["pipe","pipe","pipe"], shell: invocation.shell, env, windowsHide: true })`
  - `env = { ...deps.piLocator.createProcessEnv(deps.proxy), ...(tool.env ?? {}) }`（工具级 env 覆盖）
  - stderr 只进主进程日志；`proc.once("close", …)` 才收口（注释：`exit` 早于 stdout 排空，会丢末批 update/result）
  - 销毁 `killAcpProcessTree(proc)`（`AcpProcessHost.ts:69`）：win32 走 `taskkill /pid <pid> /T /F`，因为 spawn 常经 `.cmd` 垫片，`proc.kill()` 只杀垫片、CLI 孙进程成孤儿（opencode 实测残留）
- **启动哪些 CLI**：`src/shared/acpToolPresets.ts:63-77` 预置 15 个，实际命令如
  `gemini --acp`、`npx -y @agentclientprotocol/claude-agent-acp`、`npx -y @agentclientprotocol/codex-acp`、`kimi acp`、`qwen --acp`、`opencode acp`、`agent acp`(cursor)、`npx -y @tencent-ai/codebuddy-code --acp`、`copilot --acp`、`qodercli --acp --permission-mode bypass_permissions`。预设只是预填表单，落库仍走 `validateAcpTool`(`acpToolConfig.ts:48`) + `sanitizeAcpTools`(`:201`)。
- **开关是 opt-in**：`settings.acpEnabled` 默认 false，`isEnabled: () => settingsStore.get().acpEnabled === true`（`src/main/index.ts:6999`）；网关常驻注册但构造不 spawn（`index.ts:6986-6990`）。`create()` 里 `isEnabled()` 为假直接 throw `ACP backend is disabled (settings off or stable channel)`（`AcpAgentManager.ts:417` 起）。
- **只支持本地项目**：`getProject: (id) => { const p = projectStore.get(id); return p && isLocalProject(p) ? p : undefined; }`（`index.ts:6992-6997`，注释「ACP CLI 是本机进程：远端项目没有本机 cwd，明确不适用」）→ WSL / SSH 项目没有 ACP。
- Client 能力声明得很克制：`clientCapabilities: { fs: { readTextFile:false, writeTextFile:false }, terminal:false, _meta: { "codebuddy.ai": { mainAgentSupport: true } } }`（`AcpHandshake.ts:65`）——**桌面端不替 agent 做文件读写/终端**，全交给 CLI 自己。

---

## 2. 生命周期（main 进程视角）

### 2.1 建会话（草稿 → acp 工具绑定）
1. `POST /api/sessions` body 原样转 `createSessionDraft(body)`（`WebApiRoutes.ts:355-364`）。
2. `index.ts:6298+`：`backend = input.backend === "acp" ? "acp" : …`、`acpToolId = backend==="acp" && typeof input.acpToolId==="string" ? input.acpToolId : undefined`，ACP 不写 model/thinkingLevel 偏好 → `sessionCatalog.createDraft({…, backend, acpToolId})`。
   - **即：手机端今天就能通过 Web 建 acp 草稿会话，服务端不用改。**
3. 首次发消息（或 `POST /runtime/activate`）→ `SessionRuntimeCoordinator.activateRuntime` → `create({ agentPreset: entry.acpToolId ?? entry.agentPreset, acpSessionId: entry.acpSessionId })`（`SessionRuntimeCoordinator.ts:1711-1714` 注释：工具选择复用 `agentPreset` 字段）。

### 2.2 create（`AcpAgentManager.create`，`AcpAgentManager.ts:417`）顺序
```
tool 查表(isEnabled/未找到→acp.toolMissing)
→ agentId = `acp:${randomUUID()}`
→ tab { backend:"acp", agentPreset: tool.id, status:"starting" }
→ spawnAcpProcess(tool, cwd)            // AcpProcessHost.ts:37
→ restoreArchivedSnapshot(acpSessionId) // :889，必须先于握手（晚一步就播种空数组）
→ performAcpHandshake(...)              // AcpHandshake.ts:38
   ├ initialize                         // :62-77
   ├ 有 resumeSessionId 且 agent 有 loadSession → session/load   // :103
   │   失败则清 acpSessionId、seed 内存快照、回落 session/new
   ├ session/new { cwd, mcpServers: [] } // :127  必传空数组，opencode zod 缺它 -32602
   └ settleAcpTurn(projection, "end_turn")
→ replayToolConfigDefaults(:388) → applyBypassPermissionDefault(:350)
→ wireConnection(:527) → status="idle"
→ updates.flushMessages(runtime, true) → persistArchive(:906)
```

### 2.3 发第一条消息（`sendPrompt`，`AcpAgentManager.ts:562`）
```
resolvePromptDispatch(input, runtime)   // acpTurnSettlement.ts:21，四类前置拒绝
→ acpPromptBlocks(text, images)
→ appendAcpUserPrompt(...)              // 自己先落 user 条目：ACP 不保证 agent 回显
→ status="running"; flushMessages; armStallWatchdog
→ conn.request("session/prompt", { sessionId, prompt }, turnTimeoutMs, refresh→turnDeadlineRefresh)
     turnTimeoutMs = deps.promptTurnTimeoutMs ?? settings ?? PROMPT_TURN_TIMEOUT_MS(15min, :46)
→ await runtime.updateChain → settleAcpTurn(projection, stopReason)
catch AcpTimeoutError → conn.notify("session/cancel", { reason:"turn_timeout" })  // 否则下一轮排队在旧回合后反复超时
```
- 调用链源头：`POST /api/sessions/:id/prompt` → `index.ts:6468 sendSessionPrompt` → `SessionRuntimeCoordinator.send()`(`:393`，10 分钟 requestId 去重) → `sendOnce()`(`:1468`) → `ensureRuntime()`(`:1480`，ACP 这里要等整段握手) → `waitUntilReady`(`:2055`，`AGENT_READY_TIMEOUT_MS = 60_000`，`:274`) → `acquireDispatchLease`(`:1495`) → `agents.sendPrompt`。

### 2.4 update 流（`session/update` 通知）
- `AcpUpdatePipeline.handleSessionUpdate`(`AcpUpdatePipeline.ts:54`)：过滤 `method !== "session/update"` 和 `params.sessionId !== runtime.acpSessionId`；每条 update 刷 `lastUpdateAt` 并 `turnDeadlineRefresh()`（顺延静默上限）。
- `projectAcpSessionUpdate(state, update, agentId)`（`AcpEventProjector.ts:198`）纯函数投影 → `ChatMessage[]`：
  - `agent_message_chunk` / `agent_thought_chunk` → assistant 消息（`thinking` 字段、`stopReason:"pending"`；被工具卡隔断的旧段收口成 `"toolUse"`，`:270-300`）
  - `tool_call` / `tool_call_update` → `role:"tool"` 卡片，`meta = { toolCallId, toolName, status, kind?, args?, result?, locations?, fullText?, truncated?, isError, detailText }`（`patchToolCard`，`:151-180`）；多文件 diff 只第一块出可见卡片，其余落 `ptcNested:true` 隐藏工具消息
  - `plan` → todos；`available_commands_update` → PiCommand[]；`usage_update` → usage；`session_info_update` → title
  - **`current_mode_update` / `config_option_update` 静默忽略**（`:366-370` 注释「首版不消费(无 mode/config UI)」）
- 节流：`FLUSH_THROTTLE_MS = 80`（`AcpUpdatePipeline.ts:21`）→ `flushMessages` emit `agents:message`。
- 回合结算：`settleAcpTurn(state, stopReason)`（`AcpEventProjector.ts:377`）：`end_turn|refusal→stop`、`max_tokens|max_turn_requests→length`、`cancelled|aborted→aborted`、其余 `error`。

### 2.5 settle / watchdog / 中止
- `acpTurnWatchdog.ts`：`PROMPT_STALL_TIMEOUT_MS = 180_000`(:21)、`ABORT_ESCALATION_MS = 5_000`(:16)。
  - `armStallWatchdog`(:78)：有 pending 审批 → 不算挂死（刷静默基准）；有 in-flight 工具 → 只刷基准不顺延；真超时 → `cancelling=true; permissions.cancelAll(); notify("session/cancel",{reason:"stall"}); scheduleAbortEscalation(runtime,"stall")`。
  - `scheduleAbortEscalation`(:62)：5s 内没停就 `forceStop`（`AcpAgentManager.ts:948` → `stop()`）。
- `abort`(`:730`)：已经 cancelling → 立刻强杀(`abort_repeat`)；否则 `notify("session/cancel",{reason:"user_stop"})` + 升级计时(`abort_no_response`)。
- `stop`：先 `persistArchive` 再 `status="closed"`、`failPendingPermissions`、`killProcess`。
- 失败描述符（`acpFailureDescriptors.ts`）：`authRequired`(-32000)、`spawnNotInstalled`(ENOENT)、`startupTimeout`、`turnTimeout`。
- **空白回合守卫**：`sendPrompt` 成功但 `hasOutput===false` 且 `cancelReason!=="user_stop"` → 回滚用户条目 + `describeEmptyTurn`（stderrTail 优先）+ 拒绝（OpenCode 1.15.4 `/event` 订阅竞态实测）。

---

## 3. Web API 面（手机端真正能看到的）

### 3.1 `/api/state` 里的 acp 会话
`WebSessionProjection.ts:17-34` `WebPublicSession`：
```ts
{ id, projectId, title, status, projectPath?, model?, thinkingLevel?,
  backend?,            // "pi" | "dsh" | "acp"
  acpToolId?,          // AcpToolConfig.id（哪个 CLI）
  updatedAt?, parentSessionId?, forked?, createdAt?, permissionPreset? }
```
- **没有 `acpSessionId`**（`toPublicSession`:44 也不投影）→ 手机端无法判断「这会话在 CLI 侧有没有历史」。
- **没有 configOptions / 模型列表 / rate**。
- `state.acpTools` = `WebAcpToolInfo[] = { id, name }[]`（`WebServiceManager.ts:66`、`getState`:586、`listAcpTools`:97），来源 `index.ts:6245-6250`：`settings.acpEnabled!==true → []`，否则 `settings.acpTools.filter(enabled).map(t=>({id,name}))`。**它是「本机登记的 CLI 工具清单」，不是工具调用快照**——手机端 `src/types.ts:99-100` 的注释（"0.9.9 起的工具调用快照"）语义写错了。
- `state.pendingUiRequests` 里会出现 ACP 审批卡（见 3.4）。
- `state.runtimes` 里 acp 运行时 `modelName = 工具名`、`provider = "acp"`、`modelId = tool.id`（`acpAgentRuntime.ts:101 buildAgentRuntimeState`）。

### 3.2 ACP 专属端点：**一个都没有**
`WebApiRoutes.ts` 全表已核（`/api/state`、`/api/events`（在 `WebServiceManager.ts:487`）、`/api/ui-response`、`/api/models`、`/api/projects*`、`/api/sessions*`、`/api/sessions/:id/{messages,messages/page,prompt,stream,runtime/*,todo,subagents,search,usage,…}`、`/api/chat`、`/api/dsh/*`、workspace 路由）。ACP 全靠复用：
- 建会话 → `POST /api/sessions` + `{backend:"acp", acpToolId}`（服务端**已经**支持，`index.ts:6298+`）。
- 模型/config 切换 → **没有入口**。`/runtime/model`→`setModel` 对 ACP 是 no-op（`AcpAgentManager.ts:258` `async setModel(agentId, _provider, _modelId) { return {}; }`），`/runtime/thinking`→`setThinking` 也是 no-op(`:410`)；`/runtime/models`→`getAvailableModels` 直接 `return []`(`:859`)。真正的入口是 `applyConfigOption(agentId, optionId, value)`(`:271`) → `conn.request("session/set_config_option",…)`(`:304`)，**只挂在 IPC**：`acpIpc.ts:119 acp:session-config-get` / `:124 acp:session-config-set`，渲染层走 `useAcpSessionConfig.ts:26/56`。
- 历史 → `GET /api/sessions/:id/messages`（`index.ts:6398-6401`：runtime 投影 → 磁盘归档 → 内存快照，`readMessagesWithArchive`）与 `/messages/page`（`acpMessagePage`，`index.ts:6416`）。
- todo → `GET /api/sessions/:id/todo` 对 ACP **恒为 null**（`index.ts:6553-6557` 要 `entry.filePath`）；ACP 的 todos 在 `runtime/state.todos` 里。

### 3.3 流式事件：ACP 被翻译成**手机端已经在消费的 pi 风格事件**
- `AcpChannelToPiEvents`（`src/main/web/AcpChannelToPiEvents.ts:68`），`push(channel, payload): PiEvent[]`(`:80`) 只认两种载荷：
  - `agents:message = { agentId, deckSessionId, messages: ChatMessage[], totalLength }`
  - `agents:runtime-state = { agentId, state: { isStreaming, isTurnActive, … } }`
- 快照差分 → pi 事件（`:180-240`）：`message_start`（每段 assistant 一次）→ `message_update{assistantMessageEvent:{type:"thinking_delta"|"text_delta"}}` → `CLOSE_BLOCKS`(`:40`，`{type:"message_update", assistantMessageEvent:{type:"done"}}`) → `tool_execution_start{toolCallId,toolName,args}`(`:220`) → `tool_execution_end{toolCallId,toolName,isError,result:{toolName,text,status,detailText?,truncated?}}`(`:228`) → `agent_settled`（只认 runtime-state 的 isStreaming/isTurnActive 翻 false，`:118-124`，工具-only 回合也走这条收口）。
  - 三条硬语义（文件头注释）：正文/思考按 `message.id`+已发长度取差集；一回合可有多条 assistant 段；回合边界只认 runtime-state。
  - `TOOL_TEXT_MAX_CHARS = 32 * 1024`(`:37`) 给 SSE 帧设上界。
- 接入点：`WebEventStream.ts:472 bindAcpChannelSource` → `:526 onAcpChannelEvent` → 与 pi 共用同一个 `PiEventToUiMessageStream`(`:64`) → 同一个 `x-vercel-ai-ui-message-stream: v1` 帧（`:322`）、同一个 `data: [DONE]`(`:330`)。文件头注释：「因此 Web 线协议与前端零分支」。
- **手机端兼容性判定：天然兼容。** `src/api/frames.ts:22 ChatReducer.apply()` 认识的帧（start / start-step / text-* / reasoning-* / tool-input-* / tool-output-available|error / error / finish）正是翻译器产出的集合；`app/src/ui/chat-view.ts:1129-1142` 渲染历史消息时读 `message.meta.toolName` 出「工具 · name · phase」详情，ACP 的 `role:"tool"` 卡片直接能画。ACP 会话的 `/api/sessions/:id/stream` 与 pi 完全同一条（`WebApiRoutes.ts:513-520`，`keepAlive: true`）。

### 3.4 权限请求：落在手机端已实现的 `pendingUiRequests` / `method:"select"`
- agent 侧 `session/request_permission` → `AcpAgentManager.wireConnection`(:527) 注册的 handler → `AcpPermissionRequests.request(...)`（`AcpPermissionRequests.ts:49`）：
  - 非法/外部/无选项/重复 optionId → 立即 `{outcome:"cancelled"}`
  - 合法 → `requestId = "acp-ask-<n>"`、label→optionId 映射（重名加 `(optionId)` 后缀）、挂 `PERMISSION_TIMEOUT_MS = 10 * 60_000`(`:6`) 定时器、**emit `{ requestId, method:"select", title: params.toolCall.title || toolCallId, options: [labels…], allowOther: false }`**、返回 `ACP_DEFER_RESPONSE`（连接层不代答）
  - `respond(requestId, {value})`(`:62`)：`value` 必须是 label 原文，映射回 optionId；未知值 fail closed 成 cancelled。`cancelAll`(:71)、`hasPending`(:76)
- main 侧 → `index.ts:7227` 把 `agents:ui-request` 转发到绑定的 deck session（未绑定即 `cancelUnboundUiRequest`）→ `SessionRuntimeCoordinator.observeRuntimeEvent`(`:1208-1250`)：校验 `sourceChannel==="agents:ui-request"` + binding 三重身份，`isInteractiveUiMethod()` 通过则写 `pendingUiRequests`（`{sessionId, agentId, runtimeGeneration, requestId, method, title, options, allowOther, …}`），`completed===true` 则删除。
- Web 面：`/api/state.pendingUiRequests`；回答 `POST /api/ui-response`（`WebApiRoutes.ts:125-148`，必填 `sessionId/requestId/agentId/runtimeGeneration`）→ `respondToUi`(`:1264`) → `agents.sendUIResponse` → `AcpAgentManager.sendUIResponse`(`:817`) → `permissions.respond`。
- **手机端现状：已经能完整答 ACP 审批，无需改协议层。** `src/api/client.ts:691 respondUi` 发的 body 就是这五个字段；`app/src/ui/chat-view.ts:637-760 pendingCard()` 对 `method:"select"` 单选回 `{value: option.value}`、confirm 回 `{confirmed:true|false}`、多选/allowOther 都覆盖。唯一小瑕疵是卡片抬头固定「AI 提问」。
- 超时语义：10 分钟无应答自动 cancelled，卡片从 `/api/state` 消失（没有 `completed:true` 的 UI 事件外发，只能靠 state 刷新）。

---

## 4. 哪些能力 main 进程独有、手机端复制不了

| 能力 | 桌面端实现 | 手机端可否复制 |
|---|---|---|
| spawn CLI 子进程（含 `.cmd` 垫片、env 注入、taskkill /T 杀进程树） | `AcpProcessHost.ts:37/69` | **不能**（WebView 无 posix spawn，Android 无 taskkill） |
| stdio JSON-RPC 长连接、NDJSON 行泵 | `AcpConnection.ts:64` + `ndjsonLinePump` | 不能（无子进程） |
| 握手 initialize / session/new / session/load | `AcpHandshake.ts:38/62/103/127` | 不能 |
| 项目 cwd / 文件系统 / git 状态 | `getProject` 用 `isLocalProject`（`index.ts:6992`）+ `AcpToolConfig.cwd` | 不能（项目在电脑上） |
| 归档落盘（`userData/acp-sessions`，≤2000 条/8MB，原子写） | `AcpSessionArchive.ts:32-39` | 不能（Web 侧已由服务端代劳，手机端读 `/messages` 即可） |
| 进程守护：stall 180s / abort 升级 5s / 15min 回合上限 | `acpTurnWatchdog.ts:16/21`、`AcpAgentManager.ts:46` | 不能（但**不需要**） |
| 工具安装/探测（npm 安装、`--version` 探测） | `acpIpc.ts:79/93/108`、`acpToolLifecycle.ts` | 不能 |
| 模型倍率注解（读本机 magpie 缓存） | `acpModelRates.ts:76/109` | 不能（要服务端注解后下发） |
| Markdown/工具卡/审批卡渲染、底栏 chip、侧栏徽标、设置页 | 渲染层 12 个 ACP 文件（见 §5） | **可以照搬**（纯展示） |

一句话：**ACP 引擎 100% 在桌面 main 进程；手机端能拿到的只有「已投影的 ChatMessage + pi 风格 SSE + pendingUiRequests + 少量 session 字段」。**

---

## 5. 「套进手机端」的三种粒度

### (a) 只做渲染兼容 —— **推荐先做，服务端几乎零改动**
做什么：
1. 认 `backend: "acp"`：侧栏徽标（`app/src/ui/sidebar-view.ts:120-123` `BACKEND_BADGE` 加 `acp: "ACP"`）、`BackendKind` 联合（`src/types.ts:24` 现在是 `"pi"|"dsh"|"imagegen"`）。
2. 认 `acpToolId`（已在 `/api/state` 投影里，靠 `SessionSummary` 的 index signature 已能拿到，`src/types.ts:41`）。
3. ACP 模型 / config option 切换：**需要服务端新增端点**（这是 (a) 唯一的服务端成本）：
   - `GET /api/sessions/:id/acp/config` → `acpAgentManager.getSessionConfigOptions(agentId)`
   - `POST /api/sessions/:id/acp/config` `{optionId, value}` → `applyConfigOption`（含 target 校验、400 语义）
   - 落点：`src/main/web/WebApiRoutes.ts`（+~40 行）、`WebServiceManager.ts` deps（+2 个可选函数，与 `listAcpTools` 同款可选注入）、`src/main/index.ts:6245` 附近装配。再加 `WebSessionProjection.ts:17` 补 `acpSessionId?: string`（1 行，用于「未激活会话发消息即恢复」提示条）。
   - 手机端 UI 映射：`flattenAcpConfigOptions` / `isRenderableAcpConfigEntry` / `splitAcpConfigEntries` / `resolveAcpConfigSelect`（`renderer/src/components/session/acpConfigSelect.ts:21/50/69/54`）是**纯函数**，可 1:1 搬到手机端 `app/src/ui/acp-config-model.ts`（约 120 行，含单测）。
4. 渲染 `acpTools` 工具面板：**手机端不需要「工具面板」**——ACP 工具调用已由 `AcpChannelToPiEvents` 翻成 `tool_execution_start/end` + 历史 `role:"tool"` 卡片，`chat-view.ts` 现有渲染即够。要做的是把 `src/types.ts:99-100` 注释改成正确语义（`{id,name}[]` 工具清单），别按「快照」渲染。
5. 权限卡：**零改动**（3.4 已论证）。
6. 顺手要修的「假入口」：ACP 会话隐藏/禁用 模型面板（`/runtime/models` 恒 `[]`）、思考档（no-op）、计划模式（`agentMessage` 必被 `acp.hostInstructionsUnsupported` 拒，`acpTurnSettlement.ts:21`）、security 级别（已有 `securityWritable`，`app/src/ui/security-level-model.ts:109`）。
7. todo：ACP 走 `runtime/state.todos` 而不是 `/todo`（后者恒 null）。

工作量：手机端 **6~8 个文件 / 约 400~600 行**（`src/types.ts`、`src/api/client.ts`、新增 `app/src/ui/acp-config-model.ts` + `app/src/ui/acp-config-sheet.ts`、`app/src/main.ts`(send/chips)、`app/src/ui/sidebar-view.ts`、`app/src/styles.css`、`docs/web-api.md`）；服务端 **3 个文件 / 约 60~120 行**。
阻塞点：无。**服务端不改也能做「只读兼容 + 权限卡 + 工具卡」，只是不能切 ACP 模型。**

### (b) ACP 会话管理（新建选 CLI / 恢复 / 归档列表）—— **可做，但要处理激活窗口**
- 新建：`client.createSession` 加 `backend?: "acp"; acpToolId?: string`（`src/api/client.ts:470-481`），服务端已支持（`index.ts:6298+`）。UI 需要一个「选 CLI」的 sheet（数据源 `state.acpTools`）。**+约 50 行**。
- 恢复：**不需要新端点**——`POST /runtime/activate` 就是恢复（`session/load` 重放），历史从 `/messages` 拿。手机端要加：`acpSessionId` 存在性判断（需服务端投影，见 (a)）+ 一行「正在恢复/发消息即恢复/工具已删除」提示条（照搬 `acpSessionRestore.ts:23 resolveAcpRestoreHint` 逻辑，纯函数 ~40 行）。
- 归档列表：ACP 归档只是每 `acpSessionId` 的 JSON（`AcpSessionArchive.ts`），会话列表本来就是同一份 catalog，**无需新端点**。
- 阻塞点（重要）：
  1. **激活最长 60s**（`HANDSHAKE_TIMEOUT_MS=60_000`、`AGENT_READY_TIMEOUT_MS=60_000`）。手机端 `PiDeckClient` 默认 `timeoutMs = 20_000`（`src/api/client.ts:167`）→ 首次 npx 冷启动时 `POST /prompt` 会在 20s 抛「无法连接 PiDeck」，而服务端握手继续，用户看到假离线。必须：activate 用更长超时（`client.ts:322` 已有 `timeoutMs: Math.max(this.timeoutMs, 120_000)` 的先例）或先 activate 再 send，并展示 `status:"starting"` 进度（`/api/events` 会推）。
  2. **无「回合中插话」**：`acp.turnBusy` 直接拒（`acpTurnSettlement.ts:21`），手机端 `streamingBehaviorFor`（`app/src/ui/send-mode.ts:37`）的 steer 语义在 ACP 上无效，要按 backend 降级成「排队/提示等待」。
- 工作量：手机端 **+4~6 文件 / 约 500 行**；服务端 0~1 文件。

### (c) 手机端自己跑 ACP 引擎 —— **当前架构下不可行**
理由（按硬阻塞排序）：
1. **没有 Node/子进程**。Capacitor + WebView（`app/` 是纯 Web 壳 + 一个 HTTP 客户端）连 `child_process` 都没有；ACP agent 全部是外部 CLI（`npx -y @agentclientprotocol/codex-acp`、`gemini --acp`…，见 §1 清单）。
2. **cwd 不存在**。ACP `session/new` 必须给 `cwd`（`AcpHandshake.ts:127`），桌面端只对**本机本地项目**开放（`index.ts:6992-6997`，远端/WSL 明确不支持）。手机上没有这些项目目录，也没有 git 工作区。
3. **能力面已主动关掉**：`clientCapabilities.fs/terminal` 全 false（`AcpHandshake.ts:65`），桌面端连文件读写都不代做——手机端要 `fs.readTextFile/writeTextFile/terminal` 全实现，等于重写一个 IDE 后端。
4. **工程栈不匹配**：要么 `nodejs-mobile` 打包 Node + 每个 agent 的 arm64 产物 + 自建 `$HOME`/npm 缓存/登录态/代理，要么 Termux 侧进程 + 自定义桥。这是**另起一个产品**，与「纯 HTTP/SSE 客户端」定位冲突；且桌面端仍在演进（15 个预设、Windows 垫片、taskkill 树杀、归档、倍率注解），双端会永久分叉。
5. 唯一「勉强」的替代是让桌面端把 ACP 变远端会话（WebSocket/SSH 转发），但桌面端现在 stdio-only（`AcpProcessHost.ts:37`），没有这条线，属服务端大改且与 ACP 的本地进程模型相悖。

**判定：(c) 否。手机端只可能是 ACP 的「远端 GUI」。**

---

## 6. 风险与坑（哪些会直接砸到手机端的体验）

1. **首次消息最长 60s 无输出**（npx 冷启动 + 握手 60s 上限）。手机端 20s 超时 → 假「无法连接」；`/runtime/activate` 期间 state 里只有 `status:"starting"`。→ 必须做进度态 + 长超时。
2. **`accepted:false` 被手机端丢弃**：`app/src/main.ts:2932` 先 `appendLocalUser` 乐观上屏，`channel.send` 的返回值（`SendPromptResult` 的 `accepted/delivery/i18nKey/params`）在 `main.ts:2935` 完全不看 → ACP 的四类拒绝（`acp.processNotRunning` / `acp.hostInstructionsUnsupported` / `acp.imagesNotSupported` / `acp.turnBusy`）都会留下**幽灵用户气泡**，且没有任何提示。
3. **i18nKey 是裸 key**：服务端只回 `i18nKey`（`acpFailureDescriptors.ts:18/26/36/54`），桌面端自己本地化。手机端必须自带映射表：`acp.authRequired`、`acp.toolNotInstalled`(+`command`)、`acp.startupTimeout`、`acp.turnTimeout`(+minutes)、`acp.turnBusy`、`acp.imagesNotSupported`、`acp.hostInstructionsUnsupported`、`acp.processNotRunning`、`acp.toolMissing`。
4. **空白回合 rollback**：`sendPrompt` 成功但零输出会回滚用户条目（`AcpAgentManager.ts:562` 内），而手机端本地气泡已经上屏 → 时间线与本地不一致（需在一次 state/刷新后校正）。
5. **模型 chip 是假的**：`/runtime/model`、`/runtime/thinking` 对 ACP 都是 no-op，`/runtime/models` 恒 `[]`（`AcpAgentManager.ts:258/410/859`）。手机端现有「模型切换」面板在 ACP 会话上会「点成功、没变化」。
6. **`config_option_update` 不进消息流**（`AcpEventProjector.ts:366-370` 静默忽略）→ 若桌面端在别处改了模型，手机端不重拉 config 就一直显示旧值。
7. **权限卡 10 分钟超时**（`AcpPermissionRequests.ts:6`）：超时静默 cancelled，卡片靠 state 刷新消失；且 `value` 必须是 label 原文（`optionsByLabel`），任何「自定义输入」都会 fail closed 成 cancelled。
8. **回合时长**：stall 180s、turn 15min、abort 升级 5s → 手机端必须保留 stop/abort 入口（现有 `channel.stopRun` 可用），否则长回合只能干等 15 分钟。
9. **历史分页口径不同**：ACP 走归档（≤2000 条）→ `/messages` 返回 `windowStart:0, truncated:false`（`index.ts:6398-6401`），`/messages/page` 按轮次切片（`acpMessagePage.ts:5`）。手机端「有更早历史」的判定不能只看 `truncated`。
10. **`state.acpTools` 语义误读风险**：`src/types.ts:99-100` 注释错（不是工具调用快照）。按快照渲染会全错。
11. **todo 面板对 ACP 恒空**：`/api/sessions/:id/todo` 依赖 `entry.filePath`（`index.ts:6553-6557`），ACP 没有 → ACP 的 todo 只能从 `runtime/state.todos` 取。
12. **`acpEnabled=false` 时 `acpTools` 是 `[]`**（`index.ts:6247`）→ 手机端不能把「工具列表空」等同于「工具被删」，否则历史会话全被标成「工具缺失」。
13. **远端/WSL 项目不支持 ACP**（`index.ts:6992-6997`）→ 手机端若连的是桌面端的远端项目，ACP 会话会直接激活失败（没有本机 cwd）。
14. **Windows 垫片 / taskkill / 归档写盘失败**（`AcpProcessHost.ts:69`、`AcpSessionArchive.ts`）：不直接砸手机端，但表现是「手机上点发送没反应」——排查必须看电脑端日志（stderr 只进 main 日志，`AcpProcessHost.ts:37` 之后）。
15. **wire tolerance**（`acpWireTolerance.ts`：`commands|availableCommands` 双键、usage 两种形状）→ 手机端不要自己解析 `session/update` 原文（Web 上根本看不到原文，只有翻译后的事件），照抄桌面端宽容逻辑没意义。

---

## 7. 如果只做「最小可用版本」：落地清单

### 桌面端（PiDeck，服务端）
| 文件 | 改动 |
|---|---|
| `src/main/web/WebSessionProjection.ts:17,44` | `WebPublicSession` 加 `acpSessionId?: string`（1 行 + 投影 1 行） |
| `src/main/web/WebServiceManager.ts:97` | deps 加 `getAcpSessionConfig?` / `setAcpSessionConfig?`（照 `listAcpTools` 的可选注入写法） |
| `src/main/web/WebApiRoutes.ts` | `GET/POST /api/sessions/:id/acp/config`（target 校验 + 400 映射，约 50 行） |
| `src/main/index.ts:6245` 附近 | 装配两个 deps → `acpAgentManager.getSessionConfigOptions` / `applyConfigOption` |
| （可选）`runtime/state` | 把 `configOptions` 塞进 `AgentRuntimeState`，省掉一次往返 |

> 只做「只读兼容」时**上面全部可以不做**。

### 手机端（本仓库）
| 文件 | 改动 |
|---|---|
| `src/types.ts:24,99-100` | `BackendKind` 加 `"acp"`；`acpTools?: AcpToolInfo[]`（并修掉错误注释）；`SessionSummary.acpToolId?: string`；新增 `AcpConfigOption`/`AcpModelRate` 类型（照抄 `src/shared/types/acp.ts` 的 `AcpSessionConfigOption`） |
| `src/api/client.ts` | `createSession` 加 `backend/acpToolId`；新增 `getAcpConfig(sessionId)` / `setAcpConfigOption(sessionId, optionId, value)`；`sendPrompt` 返回类型收紧为 `SendPromptResult`（`accepted/delivery/i18nKey/params`）；`activate` 用长超时 |
| `app/src/ui/acp-config-model.ts`（新） | 照搬 `acpConfigSelect.ts` 四个纯函数：`flattenAcpConfigOptions` / `isRenderableAcpConfigEntry` / `splitAcpConfigEntries` / `resolveAcpConfigSelect` |
| `app/src/ui/acp-config-sheet.ts`（新） | 底栏 chip → 半屏抽屉：模型（可搜索长列表）/ mode / boolean 开关 |
| `app/src/ui/acp-notice.ts`（新） | `i18nKey → 中文文案` 映射（§6 第 3 条的九个 key） |
| `app/src/main.ts` | `send()`：ACP 解析 `accepted:false` → toast + 移除本地气泡；ACP 禁计划模式 `agentMessage`、禁 steer；chips 加 `acp` 分支（打开 config 抽屉）；`learnRuntimePrefs` 对 `provider==="acp"` 不学模型 |
| `app/src/ui/sidebar-view.ts:120-123` | `BACKEND_BADGE.acp = "ACP"`；`app/src/styles.css` 加 `.sbadge--acp` |
| `app/src/ui/security-level-model.ts:127-137` | `composerToolChips` 增 `acp` 位（隐藏模型/思考/计划模式入口） |
| `app/src/ui/todo-panel`（现有渲染位置） | ACP 从 `runtime/state.todos` 取（不再打 `/todo`） |
| `tests/` | `acp-config-model.test.ts`（纯函数）、`client.test.ts` 补 `acpTools`/`acpSessionId` 透传；`frames` 无需改 |
| `docs/web-api.md`、`docs/acp-desktop-research.md` | ACP 章节：`backend:"acp"`、`state.acpTools` 真语义、`/acp/config` 两个端点、`accepted:false` 语义 |

### 明确**不做**的
- 手机端 spawn CLI / 直连 ACP agent（§5c）。
- 手机端自解析 `session/update`（Web 上拿不到原文）。
- 手机端 ACP 工具安装/探测（`acpIpc.ts:79/93/108` 是桌面设置页专属）。
- 手机端 magpie 模型倍率（`acpModelRates.ts` 读本机文件；要显示得服务端注解后下发）。

---

## 附：与手机端既有代码的三处「认知修正」
1. `src/types.ts:99-100` 说 `acpTools` 是「工具调用快照」——错，是 `{id,name}[]` 的**CLI 工具清单**（`WebServiceManager.ts:66/586` + `index.ts:6245-6250`）。
2. 「手机端只是没做 ACP UI」——更准确：**协议层零缺口**（SSE 帧、工具卡、审批卡全部已兼容），缺口只有 3 个：`backend` 认知、config option 端点、`accepted:false` 处理。
3. `app/src/ui/security-level-model.ts:6-7` 注释「ACP 由 agent CLI 自管」——对，但要补一句：**ACP 的模型/思考/权限档由 `session/set_config_option` 管，和 pi 的 `/runtime/model` 是两套机制**，不能互相顶替。
