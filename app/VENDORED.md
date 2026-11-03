# 上游 vendored 源码

| 项 | 值 |
|---|---|
| 上游 | https://github.com/ayuayue/PiDeck |
| 版本 | 0.9.9 |
| tag commit | `ba8aa6a0f9ffda663199f5beb86ae57969a9d417` |
| 快照时间 | 2026-11-03 |
| 许可 | MIT（见 `vendor/pideck/LICENSE`），保留原始版权声明 |

## 只 vendored 了这三棵子树

```
vendor/pideck/src/renderer/web.html      # 入口
vendor/pideck/src/renderer/public/       # 图标 / manifest / sw
vendor/pideck/src/renderer/src/          # 渲染层源码（web 客户端及其依赖的组件树）
vendor/pideck/src/shared/                # 主进程与渲染层共享的类型与常量
```

`src/renderer/src` 是整个目录原样搬过来的（16MB）：web 客户端通过
`@/components/session/*`、`@/i18n`、`@/utils/*` 深链到桌面端组件树，按文件挑会漏依赖。
目录布局保持不变（`src/renderer` + `src/shared` 同级），上游的 `../../shared/*`
相对导入因此不需要改。

`../preload` 只有 5 处 `import type`（`browserApi.ts:1`、`ConfigModal.tsx:15`、
`desktopApi.ts:1`、`previewApi.ts:1`、`types.d.ts:1`），构建时被 esbuild 擦除，
不需要 vendored preload。

## 同步方式

```bash
# 1. 拉新版（tag 或 commit 都行，记下来更新上表）
curl -L https://github.com/ayuayue/PiDeck/archive/<sha>.tar.gz -o /tmp/pideck.tar.gz
tar -xzf /tmp/pideck.tar.gz -C /tmp
# 2. 覆盖这四棵子树（不要 rsync --delete 到 vendor 根，LICENSE 是单独拷的）
cp -r /tmp/PiDeck-*/src/renderer/src     vendor/pideck/src/renderer/
cp -r /tmp/PiDeck-*/src/renderer/public  vendor/pideck/src/renderer/
cp    /tmp/PiDeck-*/src/renderer/web.html vendor/pideck/src/renderer/
cp -r /tmp/PiDeck-*/src/shared           vendor/pideck/src/
# 3. 对一遍依赖：上游 package.json 的 dependencies 与本仓库 app/package.json 的差集
#    上游用了 pnpm catalog（如 "catalog:" / "workspace:*"），这些版本必须换成实际发布号
# 4. npm run build —— 报 "Rollup failed to resolve import" 就是第 3 步漏了包
# 5. npm run smoke 连真桌面端，确认界面还能起来
```

已知坑：`@pierre/diffs` 1.5.0+ 的发布包把依赖写成 `catalog:`，npm 直接
`EUNSUPPORTEDPROTOCOL`，所以钉在 `1.4.3`；换新版本前先 `npm view @pierre/diffs@<v> dependencies`。

不要改 `vendor/` 里的文件（改动会在下次同步时丢失）；移动端自己的适配全部写在
`app/shell/`（构建时往 `web.html` 注入 `boot.js`，在上游模块执行前接管 fetch / EventSource / SW）。
