# pideck-mobile-app

PiDeck 手机客户端外壳：Vite + TypeScript + Capacitor，协议层复用上一级 `pideck-mobile/src`（通过 `@core` 别名）。

目前是**可用的最小版本**：配对 → 会话列表 → 对话（流式）→ 停止 → ask 卡片应答 → 新建会话 → 项目切换。

```
app/
├─ index.html
├─ vite.config.ts        开发代理 /api → 127.0.0.1:8765
├─ capacitor.config.ts   原生壳配置（含 CapacitorHttp）
├─ src/
│  ├─ main.ts            接线：配对/主界面/发送/停止
│  ├─ platform/fetch.ts  GET 走 WebView（保 SSE）、POST 走 CapacitorHttp（绕 CORS）
│  ├─ platform/storage.ts 配对持久化
│  └─ ui/                dom helper + 对话渲染
└─ tests/ui.test.ts      jsdom 冒烟测试（8 条）
```

## 本地开发（浏览器里就能用）

```bash
cd app
npm install
npm run dev            # http://localhost:5173
```

- 地址留空即走 Vite 代理到 `http://127.0.0.1:8765`（同源，绕开 CORS 预检）。
- 令牌：桌面端 设置 → Web 服务 里的访问令牌，或直接粘贴扫码链接。
- 想连别的机器：`PIDECK_TARGET=http://100.x.x.x:8765 npm run dev`。

验证：

```bash
npm run typecheck
npm test
npm run build
```

## 打包成 App（本机已跑通）

本机环境（已配好，重装系统才需要重做）：

| 组件 | 位置 / 版本 |
|---|---|
| JDK | `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot`（winget 安装，机器级 `JAVA_HOME` 已设） |
| Android SDK | `C:/dsh-build/sdk`（已有 platforms;android-35 / build-tools;35.0.0 / platform-tools） |
| Gradle | wrapper 8.11.1，distribution 走腾讯镜像（官方源太慢） |
| Maven | `build.gradle` 里优先 Aliyun 镜像，回落 google()/mavenCentral() |

固定项：
- `android/local.properties` → `sdk.dir=C:/dsh-build/sdk`（用正斜杠，反斜杠转义在 Windows 上会炸）
- `android/gradle.properties` → `org.gradle.java.home=...`，避免依赖父进程环境变量
- `scripts/build-apk.mjs` 会自己找 JDK 再注入子进程（gradlew.bat 启动前就要求 JAVA_HOME）

一键出包 + 发布到局域网目录：

```bash
npm run apk          # build web → cap copy → gradlew assembleDebug
npm run publish:apk  # 复制到 ../apk/，写 SHA256SUMS.txt
```

产物：
```
../apk/PiDeck-0.1.0-debug.apk   # 约 4 MB，debug 签名
../apk/index.html               # 下载页（含二维码）
../apk/qr.svg                   # 二维码
../apk/SHA256SUMS.txt           # 校验值
```

局域网分发（任意静态服务器都行，示例用 Python）：

```bash
cd .. && python -m http.server 8080 --bind 0.0.0.0 --directory apk
```

然后手机访问 `http://<电脑IP>:8080/`。装 APK 需要在系统里允许「未知来源」。

### 出正式版（自己签）

```bash
keytool -genkey -v -keystore pideck.keystore -alias pideck -keyalg RSA -keysize 2048 -validity 10000
# android/keystore.properties 里配 storeFile/storePassword/keyAlias/keyPassword
# 然后 gradlew assembleRelease（模板未启用签名配置，需要按 Android 文档加 signingConfigs）
```

iOS 需要 macOS + Xcode：`npx cap add ios`。

### 为什么 POST 要走 CapacitorHttp

PiDeck 的 CORS 预检只放行 `content-type`，不放行 `authorization`。
WebView 里跨域 POST 带 Bearer 会被浏览器拦掉；而带 `?token=` 的 GET 属于「简单请求」，不触发预检。
所以 `src/platform/fetch.ts` 做分流：

| 场景 | 走法 | 原因 |
|---|---|---|
| GET / HEAD | WebView `fetch` | SSE 需要流式响应体，CapacitorHttp 会缓冲 |
| POST 等 | `CapacitorHttp.request` | 原生 HTTP 栈，无 CORS 约束 |
| 浏览器 dev | 普通 `fetch` + Vite 代理 | 同源，不存在跨域 |

## 已知缺口（下一步）

1. **历史消息结构**只做了宽松提取（`parts[].text` / `content`），复杂工具卡片、图片消息暂不渲染。
2. **`batch_ask`** 类型卡片未实现（提示回桌面端处理），其余 select/confirm/input/editor 已支持。
3. **令牌明文存 localStorage**，上架前应换 `@capacitor/preferences` + 原生加密。
4. **后台推送**没有：PiDeck 服务端无推送通道，App 在后台收不到「任务完成」。
5. 缺图标/启动屏/PWA manifest；模型与思考档位切换还没做 UI（`client.setModel/setThinking` 已就绪）。
6. Android 明文 HTTP 已在 `capacitor.config.ts` 打开（`cleartext`），公网场景请改用 Tailscale HTTPS。
