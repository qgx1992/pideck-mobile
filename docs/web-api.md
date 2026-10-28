# PiDeck Web 服务 API（非官方契约整理）

> **状态**：本文由客户端实现方根据 PiDeck **0.9.7** 的实际行为反向整理，不是上游文档。
> 目的是给第三方客户端一个可对齐的基线，并作为向 https://github.com/ayuayue/PiDeck 提交 PR 的初稿。
> 上游 `/api/*` 无版本前缀，**任何字段都可能变**；升级 PiDeck 后请重跑 `npm run probe -- health` 与 `npm test`。

## 1. 传输与鉴权

- 协议：HTTP/1.1，SSE；默认监听 `0.0.0.0:8765`，无 TLS（HTTPS 靠 Tailscale `serve` 或反向代理）
- 鉴权范围：`/api/*` 全部校验，**除了 `/api/health`**
- 凭证传递：
  - `Authorization: Bearer <token>`（所有方法）
  - `?token=<token>`（**仅 GET**，供 SSE / EventSource 使用）
- 令牌规则（桌面端设置页）：自动生成 UUID，或自定义 8–128 个可打印字符（不含空格）
- 有效期：永不过期 / 1h / 24h / 7d / 30d；轮换后旧令牌立即失效
- 失败响应：`401 { "code": "webError.unauthorized", "error": "Missing or invalid web service token" }`

### CORS

- JSON / SSE 响应带 `access-control-allow-origin: *`
- 预检 `OPTIONS` 返回 `204`，但 `access-control-allow-headers` **只有 `content-type`**
- 结论：**浏览器跨域客户端无法用 `Authorization` 头**（预检会失败）。原生 App 不受影响；
  Web 端自己走同源，不触发 CORS。

## 2. 端点清单

### 探活与状态

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 免鉴权。`{ ok, service:"PiDeck", host, port }`。**不含版本号** |
| GET | `/api/state` | 全量快照，支持 `If-None-Match` → `304`，响应带 `etag` |
| GET | `/api/events` | SSE：`event: state` + 完整快照，15s 一次 `: ping` |

`/api/state` 形状：

```json
{
  "projects": [{ "id": "…", "name": "…", "path": "…", "kind": "chat|project" }],
  "sessions": [{
    "id": "…", "projectId": "…", "title": "…", "status": "active|draft|closed|error",
    "projectPath": "…", "model": { "provider": "…", "modelId": "…", "modelName": "…" },
    "thinkingLevel": "…", "backend": "pi|dsh|imagegen", "updatedAt": 0,
    "parentSessionId": null, "forked": false, "createdAt": 0, "permissionPreset": "…"
  }],
  "runtimes": [{
    "sessionId": "…", "agentId": "…", "runtimeGeneration": 1,
    "projectId": "…", "cwd": "…", "status": "…", "sessionPath": "…",
    "createdAt": 0, "compactionCount": 0, "noSession": false, "deckSessionId": "…"
  }],
  "pendingUiRequests": [{
    "sessionId": "…", "agentId": "…", "runtimeGeneration": 1, "requestId": "…",
    "method": "select|confirm|input|editor|batch_ask", "title": "…",
    "options": ["…"], "placeholder": "…", "prefill": "…", "allowOther": false,
    "batchQuestions": [], "batchReview": false
  }]
}
```

### 项目

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/projects` | `{ path }` → `{ project }` |
| POST | `/api/projects/:id/delete` | 移除项目记录（内置 chat 项目拒绝） |
| GET | `/api/projects/:id/sessions` | 该项目会话列表 |
| GET | `/api/projects/:id/sessions/catalog` | 目录侧会话（含 catalog 元数据） |

### 会话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/sessions` | `{ projectId, title?, model?, thinkingLevel? }` → 草稿会话 |
| POST | `/api/sessions/anonymous` | 匿名/临时会话（Web 端新建流程使用） |
| POST | `/api/sessions/:id/update` | 改标题等 |
| POST | `/api/sessions/:id/delete` | 删除会话（含会话文件，不可恢复） |
| POST | `/api/sessions/:id/copy` | 复制会话 |
| POST | `/api/sessions/:id/export-html` | 导出 HTML，返回结果路径 |
| GET | `/api/sessions/:id/export-html` | 直接下载 HTML（附件） |
| GET | `/api/sessions/:id/messages` | `{ messages, total, windowStart, truncated }` |
| GET | `/api/sessions/:id/messages/page?before&pageSize` | `{ messages, total, nextBefore }`（按轮分页） |
| GET | `/api/sessions/:id/search?q=` | 会话内全文检索；索引未就绪返回 `{ result: null }` |
| GET | `/api/sessions/:id/todo` | 待办条 |
| GET | `/api/sessions/:id/subagents` | 子代理 |
| GET | `/api/sessions/runtimes` | 活跃运行时列表 |

消息体结构与 renderer 的历史读取器同源（`parts` / `content` / `role` / `id`），
本文不逐字段固化，客户端应按「可渲染载荷」宽泛解析。

### 发消息与流式

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/sessions/:id/prompt` | `{ requestId, message, images? }` → `{ result }`；只确认投递 |
| GET | `/api/sessions/:id/stream` | SSE 会话流（keepAlive），连接即回放当前轮 |
| POST | `/api/chat` | AI SDK 风格：`{ id, messageId?, messages:[{role,parts}] }`，直接返回流 |

会话流响应头含 `x-vercel-ai-ui-message-stream: v1`，帧格式：

```
data: {"type":"start","messageId":"…"}
data: {"type":"start-step"}
data: {"type":"text-start","id":"t1"}
data: {"type":"text-delta","id":"t1","delta":"你"}
data: {"type":"text-end","id":"t1"}
data: {"type":"reasoning-start","id":"r1"}
data: {"type":"reasoning-delta","id":"r1","delta":"…"}
data: {"type":"reasoning-end","id":"r1"}
data: {"type":"tool-input-start","toolCallId":"c1","toolName":"bash"}
data: {"type":"tool-input-available","toolCallId":"c1","toolName":"bash","input":{…}}
data: {"type":"tool-output-available","toolCallId":"c1","output":{…}}
data: {"type":"tool-output-error","toolCallId":"c1","errorText":"…"}
data: {"type":"error","errorText":"…"}
data: {"type":"finish"}

data: [DONE]
```

行为要点：

1. `/prompt` 只是投递，正文必须从流里读。
2. 打开 `/stream` 会**回放当前轮**已产出的帧（文本/思考增量已合并），随后继续推增量。
3. 一轮结束推 `finish` + `[DONE]`，但 `keepAlive` 流不关闭。
4. **不支持 `Last-Event-ID`**：断线重连必须重新拉历史 + 重开流，不要自己拼断线期间的文本。
5. 前端心跳 15s（`: ping`）。

### 运行时控制

统一入口：

```
POST /api/sessions/:id/runtime/{action}
body: { target: { sessionId, agentId, runtimeGeneration }, ...payload }
```

`action` 取值与载荷：

| action | payload | 说明 |
|---|---|---|
| `activate` | 无 target | 启动会话运行时 |
| `stop` | target | 停止运行实例 |
| `abort` | target | 中止当前轮 |
| `restart` | target | 重启（流式进度） |
| `compact` | `prompt?` | 压缩上下文 |
| `state` | — | 运行时状态 |
| `commands` | — | 可用斜线命令 |
| `models` | — | 可用模型 |
| `model` | `{ provider, modelId, modelName? }` | 切模型 |
| `thinking` | `{ level }` | 思考档位 |
| `permission` | `{ preset }` | 权限预设 |
| `clone` | — | 克隆运行时 |
| `fork` | `{ entryId, mutationFork?, branchMode? }` | 从某条消息分叉 |
| `get-fork-messages` | — | fork 源消息 |
| `edit-message` | `{ messageId, newText }` | 编辑消息 |
| `delete-message` | `{ messageId }` | 删除消息 |
| `prepare-resend` | `{ messageId }` | 准备重发 |
| `export-html` | — | 导出 |
| `rewind-list` | `{ limit?, beforeTimestamp? }` | 检查点列表 |
| `rewind-diff` | `{ checkpointId }` | 检查点差异 |
| `rewind-restore` | `{ checkpointId, scope }` | 回滚 |

**target 语义**：`runtimeGeneration` 是运行实例的版本号，实例重启后自增。
客户端必须每次从最新 `/api/state.runtimes` 取 target，**不得缓存复用**；
不匹配时服务端返回 `409`，此时应重新拉状态。

### 交互应答

```
POST /api/ui-response
{ sessionId, agentId, runtimeGeneration, requestId, response: { … } }
```

`response` 的具体形状由 `method` 决定（`select` → 选项值；`confirm` → 布尔；`input`/`editor` → 文本）。  
待答卡片数据来自 `/api/state.pendingUiRequests`。

### 其它

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/models?force=1` | 模型能力列表 |
| GET | `/api/usage/:provider?backend=pi\|dsh` | 供应商用量/余额 |
| GET | `/api/files` `/api/file-content` | 文件树 / 文件内容 |
| GET | `/api/git/status` `/api/git/diff` `/api/git/log` | Git 只读 |
| GET | `/api/prompts` `/api/prompts/:id` | 提示词库 |
| GET | `/api/skills` · POST `/api/skills/toggle` | 技能列表 / 开关 |
| GET | `/api/extensions` · POST `/api/extensions/toggle` | 扩展列表 / 开关 |
| GET | `/api/dsh/plugins` · POST `/api/dsh/plugins/install` | DSH 插件 |
| GET | `/api/sessions/:id/dsh/{subagents,skills,goal}` | DSH 专属 |

文件/Git/Prompts 的 query 参数与返回形状尚未逐字段固化，客户端应透传处理。

## 3. 错误码

服务端错误体统一为 `{ code, error, params? }`：

| HTTP | code 示例 | 客户端动作 |
|---|---|---|
| 401 | `webError.unauthorized` | 停止重连，引导重新配对 |
| 400 | `webError.projectIdRequired` / `webError.messageRequired` / `webError.requestIdRequired` | 修参数，不重试 |
| 409 | `webError.runtimeTargetRequired` | 重拉 `/api/state` 换 target |
| 413 | `webError.bodyTooLarge` | 缩小图片/文本 |
| 500 | `webError.internal` | 可重试一次，再失败提示 |

## 4. 建议上游补的两件事

1. `/api/health` 增加 `version` 与 `protocolVersion`，让客户端能判断兼容性。
2. 预检响应补 `access-control-allow-headers: content-type, authorization`，让浏览器端也能用 Bearer。

（其余字段在本文已足以实现客户端；如上游愿意，可直接把本文整理进 `docs/`。）
