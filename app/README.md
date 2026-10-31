# pideck-mobile-app

PiDeck 手机客户端外壳：Vite + TypeScript + Capacitor，协议层复用上一级 `pideck-mobile/src`（通过 `@core` 别名）。

功能已经对齐桌面端的主要体验：扫码配对 → 会话列表 → 对话（流式 + Markdown + 语法高亮）→
停止 → ask 卡片应答 → 新建会话 → 项目切换 → 工作区（Git / 文件）→ 任务清单 → 上下文用量 →
后台通知；另有抽屉手势、返回键处理与主题三档。

```
app/
├─ index.html
├─ vite.config.ts        开发代理 /api → 127.0.0.1:8765
├─ capacitor.config.ts   原生壳配置（含 CapacitorHttp）
├─ src/
│  ├─ main.ts            接线：配对/主界面/发送/停止/通知/返回键/后台恢复
│  ├─ platform/          fetch 分流、主题、安全区、导航栏、本地通知、后台看护、返回键、扫码
│  ├─ ui/                侧栏（活动/聊天/项目、多设备聚合）、对话、Markdown、语法高亮、
│  │                     ask 卡片、工作区、模型选择、上下文用量、任务清单、抽屉手势
│  └─ ...
├─ scripts/              APK 构建、图标生成、smoke-ui（真数据）、verify-send（mock 链路）
└─ tests/                node:test + jsdom，457 条
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
apk/PiDeck-0.1.7-debug.apk    # 约 27 MB，debug 签名（含扫码插件后变大）
apk/PiDeck-0.1.7-release.apk  # 约 13.5 MB，自己的 keystore 签名
apk/index.html               # 下载页（二维码内联）
apk/qr.svg                   # 同一张二维码的独立文件
apk/SHA256SUMS.txt           # 校验值（多项目合并，不会覆盖别人的条目）
```

下载页里的二维码指向**页面地址本身**，不是某个版本的 APK 文件，所以换版本不用重建二维码；
但换机器/换端口必须重建，否则手机扫出来还是旧地址：

```bash
python app/scripts/gen-qr.py                                  # 自动取本机 IP + 8080
python app/scripts/gen-qr.py --url http://100.x.x.x:8080/     # Tailscale 等场景
node app/scripts/verify-qr.mjs --expect http://10.10.0.172:8080/   # 解出来核对
```

`verify-qr.mjs` 把内联 SVG 的模块方块还原成位图再用 jsQR 解——二维码没法肉眼核对，
这一步能拦住「指向旧 IP / 旧文件名」这种只有扫了才知道的问题。

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

## 配对页扫码

相机扫码走 `@capacitor-mlkit/barcode-scanning`（ML Kit），两条路线运行时自动选：

| 顺序 | 调用 | 需要的环境 | 说明 |
|---|---|---|---|
| 1 | `BarcodeScanner.scan()` | Google Play Services 的 code scanner 模块 | 原生全屏扫描器，自带手电筒；华为等无 GMS 机型不可用 |
| 2 | `BarcodeScanner.startScan()` | 无要求（模型打包在 APK 里） | 相机画面铺在 WebView 下方，取景框由 `ui/scan-overlay.ts` 自绘 |

路线 2 能生效的前提是这几处（改动时容易漏）：

- `MainActivity.onCreate` 把 WebView 背景设为 `Color.TRANSPARENT`，否则取景框后面是一片纯色
- `styles.css` 的 `body.barcode-scanner-active { visibility: hidden }` 把整页藏起来，只留 `.scan-overlay`
- `AndroidManifest.xml` 声明 `CAMERA` 与 `com.google.mlkit.vision.DEPENDENCIES=barcode_ui`
- 新增插件后必须 `cap sync`（不是 `cap copy`），否则 `capacitor.settings.gradle` 里没有它，Gradle 根本不编译

相册解码不用插件，直接用 `jsqr` 在 canvas 上解（`readBarcodesFromImage` 只吃本地文件路径，
而 `<input type="file">` 拿到的是 File 对象）。`jsqr` 是动态 import 的：
它的 dist 里带一份 demo，整包 ~130 KB minified，首屏不应该为它买单。

### 体积代价

ML Kit 自带原生库 `libbarhopper_v3.so`（arm64 4.7 MB + armv7 3.1 MB）和三个 `.tflite` 模型：

| 包 | 加扫码前 | 加扫码后 |
|---|---|---|
| release | ~3.4 MB | **~14 MB** |
| debug | ~4 MB | ~27 MB |

已经做的控制：release 的 `ndk.abiFilters` 只留 `arm64-v8a` / `armeabi-v7a`（省下 x86 系列 ~11 MB）。
还有两条可选的：

- 开 R8（`minifyEnabled true` + `shrinkResources true`）能把 8.9 MB 的 `classes.dex` 砍掉一半以上，
  但插件是按类名反射注册的，开了必须每台机型回归一遍才敢发（所以没默认打开）
- 改成 ABI split（每个 ABI 一个 APK，arm64 约 9 MB），代价是下载页要给两个包

Vite 的 `build.sourcemap: true` 会把 `.map` 一起打进 APK（约 1 MB），排查线上问题时有用，暂不删。

## 已知缺口（下一步）

1. **历史消息结构**只做了宽松提取（`parts[].text` / `content` / `images`）。
   图片已能渲染（含点开看原图），但**流式帧里不推图片**，所以正在生成时看不到图，
   要等这一轮结束、历史重拉后才出现。
2. **令牌明文存 localStorage**，上架前应换 `@capacitor/preferences` + 原生加密。
3. **后台提醒靠前台服务**：PiDeck 服务端没有推送通道，App 被冻结后只能由
   `RunWatchService` 守 `/api/events`；深度 Doze 下系统仍可能限流。
4. **子目录多仓库看不到**：Web API 的 `/api/git/status` 只查项目根，桌面端的 `gitListRepos` 未暴露。
5. 会话列表不是实时推送的（`stateRevision` 不含列表），靠 30s 兜底轮询。
6. Android 明文 HTTP 已在 `capacitor.config.ts` 打开（`cleartext`），公网场景请改用 Tailscale HTTPS。
7. **语言只有中文**；代码高亮只覆盖常见语言，未知语言按纯文本渲染（不会乱上色）。
8. **扫码没做真机回归**：只验证到 Gradle 编译通过 + APK 产出（构建机上没有接设备）。
   首次在真机上请重点确认两件事：无 GMS 机型能回退到自绘取景框、取景框后面真的能看到相机画面
   （后者依赖 `MainActivity` 里把 WebView 背景设成透明）。
9. **编辑/删除/重发/分叉只在本机接口形状上验证过**：`edit-message` / `delete-message` /
   `prepare-resend` / `fork` 的返回体在文档里没固定，客户端做了宽松取值（`text`/`prefill`/
   `sessionId` 等），拿不到就退到本地行为。真机上请先拿一个不重要的话试一遍。
10. **多设备的后台通知只覆盖一台**：原生 `RunWatchService` 是单端点，所以多台同时连时
    只给「运行中会话最多的那台」发通知，其余设备在后台跑完不会弹。彻底解决要把原生服务
    改成多路订阅（P4，未做）。前台时所有设备的通知都正常。
11. **设置页只能读不能写**：桌面端的 pi 配置管理走 Electron IPC，`WebApiRoutes` /
    `WebWorkspaceRoutes` 里没有任何写配置的路由。设置页只做「本地设置 + 只读概况 +
    有接口的开关（技能/扩展/额度）」，改密钥、模型目录、Git/终端/代理仍需回桌面端。
12. **多设备的身份靠 origin 判定**：`/api/health` 不返回设备 id，同一台电脑用局域网 IP
    和 Tailscale 域名访问会被当成两台设备。需要上游补 `deviceId` 才能真正合并。
