# pideck-mobile-app

PiDeck 手机客户端外壳：Vite + TypeScript + Capacitor，协议层复用上一级 `pideck-mobile/src`（通过 `@core` 别名）。

功能已经对齐桌面端的主要体验：配对 → 会话列表 → 对话（流式 + Markdown + 语法高亮）→
停止 → ask 卡片应答 → 新建会话 → 项目切换 → 工作区（Git / 文件）→ 任务清单 → 上下文用量 →
后台通知；另有抽屉手势、返回键处理与主题三档。

```
app/
├─ index.html
├─ vite.config.ts        开发代理 /api → 127.0.0.1:8765
├─ capacitor.config.ts   原生壳配置（含 CapacitorHttp）
├─ src/
│  ├─ main.ts            接线：配对/主界面/发送/停止/通知/返回键/后台恢复
│  ├─ platform/          fetch 分流、主题、安全区、导航栏、本地通知、后台看护、返回键
│  ├─ ui/                侧栏、对话、Markdown、语法高亮、ask 卡片、工作区、模型选择、
│  │                     上下文用量、任务清单、抽屉手势
│  └─ ...
├─ scripts/              APK 构建、图标生成、smoke-ui（真数据）、verify-send（mock 链路）
└─ tests/                node:test + jsdom，285 条
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
- 出包走 `cap sync` 而不是 `cap copy`：新装的插件只有 sync 才会写进
  `android/capacitor.settings.gradle`，否则 Gradle 根本不编译它

一键出包 + 发布到局域网目录：

```bash
npm run apk          # build web → cap sync → gradlew assembleDebug
npm run publish:apk  # 复制到项目根下的 apk/，写 SHA256SUMS.txt
```

### 包名与共存

| 构建类型 | applicationId | 说明 |
|---|---|---|
| debug | `top.caoayu.pideck.debug` | 带 `-debug` 版本后缀，可与 release 同时安装 |
| release | `top.caoayu.pideck` | 正式包，自己 keystore 签名 |

`namespace` 仍是 `dev.pideck.mobile`（Java 源码包不动），只换了安装身份。
更早的 `dev.pideck.mobile` 老包也能共存，方便平滑迁移后卸载。

### 出签名发布包（给别人装 / 覆盖升级用）

`npm run apk` 出来的是 **debug 签名**包——自己装没问题，但换一台机器或想覆盖升级时，
签名不一致会装不上（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。发布版必须用自己的 keystore。

```bash
npm run gen:keystore    # 只跑一次：生成 android/pideck-release.keystore + keystore.properties
npm run apk:release     # 出签名包
npm run publish:apk -- --release   # 丢到项目根下的 apk/ 供下载
```

- 密码由脚本随机生成，写进 `android/keystore.properties`，**不回显到终端**
- `keystore.properties` 与 `*.keystore` 都在 `.gitignore` 里，不会入库
- **务必立刻备份这两个文件**：丢了 keystore，后续版本就无法覆盖安装升级，
  只能换 applicationId 重新发（老用户得卸载重装，历史数据也会没）

Gradle 侧（`android/app/build.gradle`）读取逻辑：有 `keystore.properties` 就配置
`signingConfigs.release`，没有则 release 包不签名并打印提示，不会让构建直接失败。

想用自己的密码：

```bash
PIDECK_KEYSTORE_PASSWORD='你的强密码' npm run gen:keystore
```

### CI 自动出包

`.github/workflows/android-release.yml`：

| 触发 | 行为 |
|---|---|
| push `v*` tag | 构建 + 校验签名 + 上传 APK 到该 tag 的 Release |
| 手动 run（workflow → Run workflow） | 只构建并产出 artifact，不动 Release |

签名材料通过 repository secrets 注入（脚本里也是落盘后用完立即删除）：

```bash
base64 -w0 android/pideck-release.keystore | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PROPERTIES < android/keystore.properties
```

版本号从 tag 推导（`v0.2.0` → versionName `0.2.0`、versionCode `200`），
通过 `-PpideckVersionName/-PpideckVersionCode` 传给 Gradle，不需要手改 build.gradle。

产物：
```
apk/PiDeck-0.1.1-release.apk    # 约 4 MB，debug 签名
apk/PiDeck-0.1.1-release.apk  # 约 3.4 MB，自己的 keystore 签名
apk/index.html               # 下载页（含二维码）
apk/qr.svg                   # 二维码
apk/SHA256SUMS.txt           # 校验值
```

局域网分发（任意静态服务器都行，示例用 Python）：

```bash
python -m http.server 8080 --bind 0.0.0.0 --directory apk
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

1. **历史消息结构**只做了宽松提取（`parts[].text` / `content`），图片消息暂不渲染。
2. **令牌明文存 localStorage**，上架前应换 `@capacitor/preferences` + 原生加密。
3. **后台提醒靠前台服务**：PiDeck 服务端没有推送通道，App 被冻结后只能由
   `RunWatchService` 守 `/api/events`；深度 Doze 下系统仍可能限流。
4. **子目录多仓库看不到**：Web API 的 `/api/git/status` 只查项目根，桌面端的 `gitListRepos` 未暴露。
5. 会话列表不是实时推送的（`stateRevision` 不含列表），靠 30s 兜底轮询。
6. Android 明文 HTTP 已在 `capacitor.config.ts` 打开（`cleartext`），公网场景请改用 Tailscale HTTPS。
7. **语言只有中文**；代码高亮只覆盖常见语言，未知语言按纯文本渲染（不会乱上色）。
