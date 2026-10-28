## 环境

- PiDeck 0.9.7（Windows 11）
- 通过内置「Web 服务」+ 自建移动端客户端操作（Token 鉴权开启）

## 现象

通过 Web API 删除会话，**服务端确实删掉了**，但桌面端列表仍显示该会话，需要手动切换项目或重启才消失。

## 复现

```bash
# 1) 建一个临时会话
sid=$(curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"projectId":"<project-id>","title":"__test__"}' \
  http://127.0.0.1:8765/api/sessions | jq -r .session.id)

# 2) 删除
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8765/api/sessions/$sid/delete
# → {"deleted":true}

# 3) 服务端状态里已经没有了
curl -s "http://127.0.0.1:8765/api/state?token=$TOKEN" \
  | jq '.sessions[] | select(.id=="'"$sid"'")'
# → 空（确认已删除）
```

此时**桌面端界面上该会话仍然存在**，点进去内容已不存在。

## 原因

`out/main/index.js` 里 `deleteSessionRecord` 只做删除，没有广播刷新事件：

```js
deleteSessionRecord: async (sessionId) => {
  const entry = sessionCatalog.get(sessionId);
  if (!entry) return false;
  await sessionRuntimeCoordinator.releaseRuntimeForDelete(sessionId);
  wechatServiceRef.current?.removeBindingsForSession(sessionId);
  if (entry.filePath) await sessionScanner.delete(entry.filePath);
  if (entry.backend === "dsh" && entry.dshSessionId) {
    const project = projectStore.get(entry.projectId);
    await dshHost.deleteSession(entry.dshSessionId, project?.path ?? "");
    await sessionCatalog.rememberDismissedDshSession(entry.dshSessionId);
  }
  await sessionCatalog.removeWithDescendants(sessionId);
  return true;          // ← 没有 webContents.send(sessionsCatalogRefreshed)
}
```

对比：其它会改变会话列表的路径（例如历史元数据回填 `backfillHistoricalSessionMetadata`）会显式发送 `ipcChannels.sessionsCatalogRefreshed`。

## 影响

- 通过 Web API / 第三方客户端删除会话后，桌面端出现「幽灵会话」
- 桌面端自己的删除入口走 IPC、删完由渲染进程自行刷新，所以平时不容易发现这个缺口
- 同理可推：任何**非渲染进程发起**的会话增删改（未来若有自动化、定时任务清理等）都不会同步到 UI

## 建议修复

在成功返回前广播一次（`copy` / `rename` 等同理最好也带上）：

```js
const win = getMainWindow();
if (win && !win.isDestroyed()) {
  win.webContents.send(ipcChannels.sessionsCatalogRefreshed, {
    projectId: entry.projectId,
  });
}
```

## 备注

服务端删除本身是正确且彻底的（会话文件、catalog 记录、DSH 侧会话都清理了），只是缺「通知 UI 刷新」这一步。
