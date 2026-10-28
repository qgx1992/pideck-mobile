## 环境
- PiDeck 0.9.7（Windows 11）
- 客户端：基于 `/api/events` SSE 的自建移动端

## 现象

通过 Web API 新建会话后，SSE 客户端 **18 秒内一次 `event: state` 都收不到**（只有连接时的首帧快照），
桌面端新建/删除/改名会话也不会推送给 Web 客户端。只有「会话运行状态」和「待答卡片」的变化会实时推送。

## 原因一：revision 门控挡死了 5s tick 兜底

`WebStateEventHub.push()`：

```js
async push(targetClients = [...], force = false) {
  const revision = this.deps.stateRevision?.();
  if (!force && revision !== undefined && this.lastStateRevision !== null
      && revision === this.lastStateRevision && this.lastStatePushJson !== null) {
    return;                                    // ← 直接返回，不重建快照
  }
  let json = serializePublicWebPayload(await this.deps.loadState());
  if (!force && json === this.lastStatePushJson) { ... return; }
  ...
}
```

而 `stateRevision()` 只包含运行时状态与待答卡片：

```js
stateRevision() {
  const runtimes = this.deps.listSessionRuntimes();
  const pending = this.deps.listPendingUiRequests();
  return `${runtimes.map(e => `${e.sessionId}:${e.agentId}:${e.status}:${e.runtimeGeneration ?? ""}`).join(";")}`
       + `#${pending.length}:${pending.map(e => `${e.sessionId}:${e.requestId}`).join(",")}`;
}
```

**会话列表（新建 / 删除 / 改名）不在 revision 里**。所以：

- revision 没变 → `push()` 在第一道门就 return
- 后面的「全量 JSON 比对」和 5s tick 根本执行不到 → 注释里写的兜底实际不生效

## 原因二：/api/state 的 ETag 从未生效

客户端想用条件请求省流量，但响应里没有 `ETag` 头：

```bash
$ curl -s -D - -o /dev/null "http://127.0.0.1:8765/api/state?token=$TOKEN"
HTTP/1.1 200 OK
content-type: application/json; charset=utf-8
cache-control: no-store
access-control-allow-origin: *
（没有 etag）
```

`WebApiRoutes` 算出了 etag：

```js
if (url.pathname === "/api/state") {
  const state = await this.getState();
  const etag = stateEtag(state);
  if (ifNoneMatchHits(request.headers["if-none-match"], etag)) { ...304... }
  this.sendJson(response, state, { etag });      // ← 第三个参数
}
```

但注入时只转发了两个参数：

```js
sendJson: (response, body) => this.sendJson(response, body),   // ← { etag } 被丢弃
```

所以 `stateEtag` / `ifNoneMatchHits` 是死代码，客户端永远拿不到 304，
每次轮询都是全量（当前约 58 KB）。

## 影响

- 自建客户端必须自己定时轮询才能看到会话列表变化（我这边只能退化成「前台可见时 30s 拉一次」）
- 移动端流量与电量被无谓消耗（58 KB / 次，且拿不到 304）
- 第三方客户端很难判断「到底是不支持推送，还是服务端卡了」

## 建议修复

1. `stateRevision()` 纳入会话列表的稳定摘要（例如 `sessions.length` + 每条 `id:updatedAt` + project 数量），
   这样新建/删除/改名都能触发推送；
   或者把 `push()` 的 revision 快速门控改成「始终构建快照再比 JSON」——反正已有 400ms 去抖。
2. `sendJson` 转发第三个参数，让 `/api/state` 真正返回 `ETag` 并支持 304。
3. （可选）`/api/state` 已经带 `cache-control: no-store`，与 ETag 语义冲突，建议改成 `no-cache`。

## 备注

以上都是对着实际响应头和构建产物核实的。如果需要我可以补一份复现脚本。
