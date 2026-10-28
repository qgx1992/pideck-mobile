## 环境

- PiDeck 0.9.7（Windows 11）
- 通过内置「Web 服务」访问（默认 `0.0.0.0:8765`，Token 鉴权开启）
- 客户端场景：自建移动端 App，在 WebView 内跨域调用 `/api/*`

## 现象

`workspace` 路由（文件 / Git / 提示词 / 技能 / 扩展）的**实际响应不带** `Access-Control-Allow-Origin`，而主路由（`/api/state`、`/api/models`）带。

同一端点，预检和实际响应的头不一致：

```bash
# 预检：有 CORS 头
$ curl -s -D - -o /dev/null -X OPTIONS \
    -H "Origin: https://example.com" \
    "http://127.0.0.1:8765/api/files?projectId=<id>"
HTTP/1.1 204 No Content
access-control-allow-origin: *
access-control-allow-methods: GET,POST,OPTIONS
access-control-allow-headers: content-type

# 实际 GET：没有 CORS 头
$ curl -s -D - -o /dev/null \
    -H "Origin: https://example.com" \
    "http://127.0.0.1:8765/api/files?projectId=<id>&token=<token>"
HTTP/1.1 200 OK
content-type: application/json; charset=utf-8
cache-control: no-store
（没有 access-control-allow-origin）
```

逐个端点验证结果（均为 200，但响应头不同）：

| 端点 | HTTP | `Access-Control-Allow-Origin` |
|---|---|---|
| `/api/state` | 200 | ✅ `*` |
| `/api/files` | 200 | ❌ 缺失 |
| `/api/prompts` | 200 | ❌ 缺失 |
| `/api/skills` | 200 | ❌ 缺失 |
| `/api/extensions` | 200 | ❌ 缺失 |
| `/api/git/status` | 200 | ❌ 缺失 |

## 影响

浏览器 / WebView 的规则是：**预检通过、但实际响应缺少 `Access-Control-Allow-Origin`，响应会被丢弃并抛 `TypeError: Failed to fetch`**。

所以任何跨域客户端（自建 App、第三方 Web 面板）调用这些接口 100% 失败——状态码 200、数据完整，但 JS 拿不到。实际表现是「读取文件目录失败：Failed to fetch」，文件引用、提示词、Git 历史等功能全部不可用。

顺带说明：换成 Tailscale 的 HTTPS 地址也不能绕过，因为 origin 仍然不同。

## 建议修复

给 workspace 路由（注册 `/api/files`、`/api/prompts`、`/api/skills`、`/api/extensions`、`/api/git/*` 的那套 handler）补上和主路由一致的响应头：

```js
"access-control-allow-origin": "*",
"cache-control": "no-store",
```

## 相关：预检不放行 authorization

同一套 CORS 配置里，预检只允许 `content-type`：

```
access-control-allow-headers: content-type
```

这导致跨域客户端**无法使用 `Authorization: Bearer`**，只能退化成 GET + `?token=`（服务端确实支持，但 POST 没法退）。建议一并改成：

```
access-control-allow-headers: content-type, authorization
```

对原生客户端无影响，只影响浏览器 / WebView 场景，属于低风险改动。

## 备注

以上都是对着实际响应头测的，不是推测。需要的话我可以补一份完整的复现脚本。
