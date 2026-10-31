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

## 改动完成后必须跑的验证

```bash
npm test && npm run typecheck                 # 协议核心
cd app && npm test && npm run typecheck       # 客户端
cd app && npm run build && npm run verify:send
```

`verify:send` 是 mock 的发送链路（14 项），能挡住「发送后不回显 / 流式块重复 / 按钮状态错」这类回归。
