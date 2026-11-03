/**
 * 只构建上游 PiDeck 的 Web 客户端（`vendor/pideck/src/renderer/web.html`）。
 *
 * 上游本身用 electron-vite 一次构建 5 个入口（主窗口 / 宠物 / 浮窗 / 划词 / web），
 * 这里把 renderer 那份配置原样搬过来、砍掉 electron 相关部分：插件、alias、worker
 * 格式都必须与上游一致，否则 shiki 精细 bundle 与 KaTeX 字体精简会失效。
 *
 * alias 里的 `@` 指向上游 renderer/src：上游源码用 `@/i18n`、`@/lib/utils` 这类写法，
 * 只靠相对路径是解析不到的。
 */
import { resolve } from "node:path";
import { readFile, readFileSync, copyFileSync, writeFileSync } from "node:fs";
import { readFile as readFileAsync } from "node:fs/promises";
import { defineConfig, transformWithEsbuild } from "vite";
import type { Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const appRoot = import.meta.dirname;
const vendorRoot = resolve(appRoot, "vendor/pideck");
const rendererRoot = resolve(vendorRoot, "src/renderer");
const rendererSrc = resolve(rendererRoot, "src");
const sharedRoot = resolve(vendorRoot, "src/shared");

/**
 * AudioWorklet 语音分段器内联插件（上游同名插件，路径换成 vendor）。
 * 上游把 `voicePcmSegmenter.ts?raw` 当字符串注入 AudioWorklet，但 worklet 没有
 * 模块解析器，必须先转译成 JS，否则 `addModule` 直接 SyntaxError。
 */
function audioWorkletSegmenterPlugin(): Plugin {
	const SEGMENTER_SUFFIX = "voicePcmSegmenter.ts?raw";
	return {
		name: "pideck-audio-worklet-segmenter",
		enforce: "pre",
		async transform(_code, id) {
			if (!id.replace(/\\/g, "/").endsWith(SEGMENTER_SUFFIX)) return;
			const source = await readFileAsync(id.replace(/\?.*$/, ""), "utf8");
			const transpiled = await transformWithEsbuild(source, `${SEGMENTER_SUFFIX}.js`, {
				loader: "ts",
				target: "es2022",
				format: "esm",
			});
			return { code: `export default ${JSON.stringify(transpiled.code)};`, map: null };
		},
	};
}

/**
 * 裸导入 `shiki` → 精细 bundle（上游 `src/renderer/src/shiki/bundle.ts`）。
 * worker 不继承 resolve.alias，所以 plugins 与 worker.plugins 都要挂。
 * find 必须精确匹配：字符串前缀会把 `shiki/core` / `shiki/wasm` 一并改写。
 */
function shikiBundleAliasPlugin(): Plugin {
	return {
		name: "shiki-bundle-alias",
		enforce: "pre",
		config() {
			return {
				resolve: {
					alias: [{ find: /^shiki$/, replacement: resolve(rendererSrc, "shiki/bundle.ts") }],
				},
			};
		},
	};
}

/** KaTeX 只保留 woff2（Chromium 用不到 woff/ttf），59 个字体文件 → 19 个。 */
function katexWoff2OnlyPlugin(): Plugin {
	const KATEX_CSS = /katex[\\/]dist[\\/]katex\.min\.css/;
	const FONT_FACE_RE = /@font-face\s*\{([^}]*?src\s*:\s*)([^}]*?)\};?/gi;
	return {
		name: "katex-woff2-only",
		enforce: "pre",
		transform(code, id) {
			if (!KATEX_CSS.test(id)) return;
			const replaced = code.replace(FONT_FACE_RE, (_match, prefix, srcValue) => {
				const woff2Match = srcValue.match(/url\([^)]+\)\s*format\(['"]?woff2['"]?\)/i);
				if (!woff2Match) {
					const firstUrl = srcValue.match(/url\([^)]+\)/i);
					return firstUrl ? `@font-face{${prefix}${firstUrl[0]};}` : _match;
				}
				return `@font-face{${prefix}${woff2Match[0]};}`;
			});
			return { code: replaced, map: null };
		},
	};
}

/**
 * 壳的入口：`web.html` 注入 boot.js，`shell/launcher.html` 复制成 `dist/index.html`。
 *
 * 为什么不在 vite 的 input 里直接放两个 html：`root` 是上游 renderer 目录，
 * 根目录之外的 html 会被输出到 `dist/shell/launcher.html`，而且 html 里的
 * module 标签会被改写成带 hash 的路径——启动页里写死的 `/launcher.js` 就对不上了。
 * 两个入口都钉死文件名（`boot.js` / `launcher.js`），HTML 用 closeBundle 里补。
 */
function shellEntryPlugin(): Plugin {
	return {
		name: "pideck-shell-entry",
		apply: "build",
		closeBundle() {
			const outDir = resolve(appRoot, "dist");
			const webHtml = resolve(outDir, "web.html");
			const html = readFileSync(webHtml, "utf8");
			if (!html.includes('src="/boot.js"')) {
				// boot 必须在所有模块之前：放在 <head> 最前面，deferred module 按文档顺序执行
				writeFileSync(webHtml, html.replace(/<head>/i, '<head>\n    <script type="module" src="/boot.js"></script>'));
			}
			copyFileSync(resolve(appRoot, "shell/launcher.html"), resolve(outDir, "index.html"));
		},
	};
}

export default defineConfig({
	root: rendererRoot,
	base: "/",
	resolve: {
		alias: [
			{ find: "@", replacement: rendererSrc },
			{ find: "@renderer", replacement: rendererSrc },
			{ find: "@shared", replacement: sharedRoot },
			{ find: /^shiki$/, replacement: resolve(rendererSrc, "shiki/bundle.ts") },
		],
	},
	plugins: [
		audioWorkletSegmenterPlugin(),
		react(),
		tailwindcss(),
		katexWoff2OnlyPlugin(),
		shikiBundleAliasPlugin(),
		shellEntryPlugin(),
	],
	worker: {
		format: "es",
		plugins: () => [shikiBundleAliasPlugin()],
	},
	build: {
		outDir: resolve(appRoot, "dist"),
		emptyOutDir: true,
		reportCompressedSize: false,
		cssMinify: "esbuild",
		rollupOptions: {
			input: {
				web: resolve(rendererRoot, "web.html"),
				boot: resolve(appRoot, "shell/boot.ts"),
				launcher: resolve(appRoot, "shell/launcher.ts"),
			},
			cache: true,
			output: {
				entryFileNames: (chunk) =>
					chunk.name === "boot" || chunk.name === "launcher" ? "[name].js" : "assets/[name]-[hash].js",
				manualChunks(id) {
					if (id.includes("/node_modules/react/") || id.includes("/node_modules/react-dom/") || id.includes("/node_modules/scheduler/")) {
						return "vendor-react";
					}
					if (id.includes("/node_modules/lucide-react/")) return "vendor-icons";
					if (
						id.includes("/node_modules/react-markdown/") ||
						id.includes("/node_modules/remark-") ||
						id.includes("/node_modules/rehype-") ||
						id.includes("/node_modules/unified/") ||
						id.includes("/node_modules/katex/") ||
						id.includes("/node_modules/mdast-") ||
						id.includes("/node_modules/hast-") ||
						id.includes("/node_modules/micromark-") ||
						id.includes("/node_modules/vfile") ||
						id.includes("/node_modules/unist-") ||
						id.includes("/node_modules/trough/") ||
						id.includes("/node_modules/bail/") ||
						id.includes("/node_modules/dequal/") ||
						id.includes("/node_modules/devlop/") ||
						id.includes("/node_modules/html-to-image/")
					) {
						return "vendor-markdown";
					}
				},
			},
		},
	},
});
