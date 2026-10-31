# 项目约定

给协作的 agent / 开发者看的硬性规则。改了流程请同步更新本文件。

## 版本号：只在用户明确要求时改

**用户没说「发版 / 出包 / 上新版本」，就不要动版本号。**

需要同时改的三处（漏一处就会出现「包里是 0.1.8、页面写 0.1.9」这类错位）：

| 文件 | 字段 |
|---|---|
| `app/package.json` | `version` |
| `app/android/app/build.gradle` | `pideckVersionName` / `pideckVersionCode` |
| `app/README.md`、根 `README.md` | 产物文件名里的版本号 |

规则：

- 改代码、修 bug、加功能 —— **不动**上面任何一处。
- 用户明确说要发版时才动，并且 `versionCode` 必须**单调递增**（0.1.9 → 110，不能复用或回退）。
- 不要在同一个提交里混「功能改动」和「版本号变更」：分成 `fix(...)` / `feat(...)` 与单独的 `chore(release): x.y.z`。
- 擅自升版本号的代价不只是流程混乱：本地包已经装到手机上之后，回滚版本号会撞上 **Android 不允许降级安装**，只能卸载重装。

## 发布的两条路径

两者独立，用户会明确指定走哪条：

1. **本地包（局域网测试）**：`cd app && npm run apk:release` → `npm run publish:apk -- --release`
   → 更新 `apk/index.html` 的版本、链接与说明 → 校验 8080 上的下载与 sha256。
   `apk/` 在 `.gitignore` 里，目录里只保留最新一个 PiDeck 包（旧包发布后删掉并同步 `SHA256SUMS.txt`）。
2. **GitHub Release**：`git push origin master` → 打 `vX.Y.Z` tag 并推送 → CI 构建签名包并挂到 Release
   → 用 `gh release edit` 补标题与说明（工作流本身不写 body）。

> 本机 `git push` 需要显式走系统代理：`git -c http.proxy=http://127.0.0.1:7897 push ...`
> （代理端口会随 Clash 重启变化，先 `netstat` 确认）。

## 局域网下载页（8080）：必须用 detached 方式启动

`apk/` 目录用 Python 的静态服务器对局域网开放（手机扫码下载 APK 走这条）。
**不要把它挂在 agent 的 shell 子进程里** —— 任何一条工具命令超时被 kill 时，整个进程组
会一起死，下载页就无声消失了。踩过：`gh release download` 超时、60s guardrail 砍掉进程组，
8080 上的 PID 356 跟着消失，用户端看到的就是「下载页怎么挂了」。

启动（Windows，PowerShell `Start-Process` 脱离父进程组；注意脚本路径别用 `python`
裸名，PowerShell 里会命中 `WindowsApps` 的空壳别名而静默失败）：

```powershell
powershell -NoProfile -Command "$p = Start-Process -FilePath 'C:\Users\Administrator\AppData\Local\Programs\Python\Python312\python.exe' -ArgumentList '-m http.server 8080 --bind 0.0.0.0 --directory \"D:\A-my project\PiDeck-Mobile\apk\"' -PassThru -WindowStyle Hidden; 'pid=' + $p.Id + ' exited=' + $p.HasExited"
```

起完必须核对四件事：

```bash
netstat -ano | grep LISTENING | grep :8080          # 有 0.0.0.0:8080 且 PID 是刚起的那只
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/
curl -s -o /dev/null -w "%{http_code}\n" http://<本机局域网IP>:8080/   # 页面里的二维码指向这个地址
curl -s http://<本机局域网IP>:8080/<包名>.apk | sha256sum             # 与 apk/SHA256SUMS.txt 一致
```

其它约定：

- `apk/` 下**只放能公开的东西**。它是对局域网无鉴权明文开放的（还带目录列表），
  任何凭据 / 客户端配置 / 未脱敏的笔记都不能放在这里（踩过：`apk/proxy/` 里三份代理配置曾对外 200）。
- 页面里的二维码用 `app/scripts/gen-qr.py` 生成、`app/scripts/verify-qr.mjs --expect <地址>` 校验。
- `python -m http.server` 没有 deny 规则，要挡住某个文件只能让它不在这棵目录树里。

## 改动完成后必须跑的验证

```bash
npm test && npm run typecheck                 # 协议核心
cd app && npm test && npm run typecheck       # 客户端
cd app && npm run build && npm run verify:send
```

`verify:send` 是 mock 的发送链路（14 项），能挡住「发送后不回显 / 流式块重复 / 按钮状态错」这类回归。
