# PiDeck 手机版（app/）

Capacitor 7 壳 + **桌面端自带的 Web 客户端**。界面不是重写的第二份：`vendor/pideck/` 里是
桌面端 0.9.9 的 `src/renderer/src` 与 `src/shared` 原样快照，`npm run build` 只构建它的
`web.html` 入口（pin 的 tag、同步步骤见 `VENDORED.md`）。

## 两个页面

| 产物 | 来源 | 干什么 |
|---|---|---|
| `dist/index.html` | `shell/launcher.html` + `shell/launcher.ts` | 配对与设备管理：扫码 / 粘贴链接 / 多设备切换 / 连通测试 |
| `dist/web.html` | `vendor/pideck/src/renderer/web.html` + `boot.js` | 聊天界面（桌面端同一份代码） |

`boot.js` 由 vite 注入 `web.html` 的 `<head>` 最前面，在所有 deferred 模块之前执行：
写令牌 → 装 `fetch` / `EventSource` 桥 → 拦外链与 blob 下载 → 禁 service worker → 起后台看护。

## 壳负责什么（WebView 做不到的部分）

- `shell/native-sse.ts` + `SsePlugin.java`：原生 SSE。发送是 `POST /api/chat` 的流式响应，
  插件原来只支持 GET，0.4.0 补了 `method` / `body`。
- `shell/run-watch.ts` + `RunWatchService.java`：会话在跑就拉起前台服务，跑完发通知；
  切后台回来立刻续上。
- `shell/downloads.ts`：`download` 属性的 blob 链接 → 原生保存到「下载/PiDeck」。
- `shell/external-links.ts`：外链走 `NavigationBar.openUrl`，交给系统浏览器。
- `shell/device.ts`：设备注册表（`pideck.devices`）与令牌键 `pideck-web-token`
  —— 后者必须和上游 `vendor/pideck/src/renderer/src/web/webApi.ts:19` 一致。

## 命令

```bash
npm install                 # 需要代理：export npm_config_proxy=http://127.0.0.1:7897
npm run dev                 # 浏览器里调界面（连 dist 里的 web.html 会缺原生生能力，仅看样式）
npm run verify              # typecheck + node --test shell/*.test.ts + vite build
npm run smoke               # jsdom 跑 dist/web.html，连本机 8765 的真桌面端
npm run apk:release         # build → cap sync → gradlew assembleRelease
npm run publish:apk -- --release   # 复制到 ../apk/ 并刷新 SHA256SUMS.txt
```

`npm run smoke` 没给 `--token` 时从 `%APPDATA%/PiDeck/settings.json` 的 `webServiceToken` 读，
所以本机开着桌面端 Web 服务就能直接跑。

## 版本号

`package.json` 的 `version` 与 `android/app/build.gradle` 的 `pideckVersionName` /
`pideckVersionCode` 必须同步（0.4.0 → 400）。规则见根 `AGENTS.md`。

## 打包环境（本机）

- JDK 21 + Android SDK（`android/local.properties` 里的 `sdk.dir`）
- 签名：`android/keystore.properties`（不进 git）
- 静态下载页由 Windows 计划任务 `PiDeck Download Server` 提供（8080），
  用 `scripts/install-download-server-task.ps1` 注册，日志在
  `%LOCALAPPDATA%\PiDeck-DownloadServer\server.log`

## 验证清单（改完必跑）

```bash
npm run verify && npm run smoke
npm run apk:release
npm run publish:apk -- --release
# 然后按根 AGENTS.md 的四步核对 8080：netstat / 127.0.0.1 / 局域网 IP / sha256
```
