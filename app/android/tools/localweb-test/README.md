# 本地转发服务的 JVM 自测

`LocalWebServer.java` 是「桌面网页版」的本地外壳（静态资源缓存 + `/api` 转发 + SSE 直通）。
它跑在 Android 上、依赖 `android.content.Context`（只为拿 `getCacheDir()`）和 `android.util.Log`，
所以这里给了两个同名桩，把**同一份源码**拉到 JVM 上跑，验证的正是手机里跑的那份逻辑。

为什么不在 instrumentation 测试里做：这些失败点是「HTTP 分帧」「SSE 会不会被攒住」这类
字节级语义，真机上的断言既慢又难看出是哪一步错了；JVM 上能直接量首块延迟。

## 跑

```powershell
# 断言 14 项：静态回源与落盘、content-type、Bearer 注入、POST 体透传、
# SSE 首块延迟、桌面掉线后走缓存、深链回落到入口页、掉线后 /api 快速报错
powershell -NoProfile -ExecutionPolicy Bypass -File app/android/tools/localweb-test/run.ps1
```

对着**真桌面端**跑（要桌面端开着 Web 服务；token 从 `%APPDATA%\PiDeck\settings.json`
的 `webServiceToken` 取）——启动后打印 `LIVE_ORIGIN`，再用 curl 访问：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File app/android/tools/localweb-test/run.ps1 -Live http://127.0.0.1:8765 -Token <webServiceToken>
```

## 覆盖到的坑（改代码前先看这几条）

- 入口页在桌面端是 `GET /`（它优先回 `web.html`），**不是** `/index.html`；按 `/index.html`
  去回源会 404，然后整页退化成离线提示页（踩过）。
- 上游 404 要**透出 404**，不能拿入口页兜底 —— 否则 `<script src>` 拿到一段 HTML 会执行失败却看不出原因。
- 响应头里的 `Connection` 必须改写成 `close`（上游是 keep-alive，不改客户端会一直等）。
- 每个响应块都要 `flush()`：SSE 攒一次就是「打字机卡住」。
- 写 Java 字符串里的 `\r\n` 时别经过二次转义的工具链（本次就是因为脚本把 `\r\n` 写成了真换行，
  javac 报「未结束的字符串文字」）。
