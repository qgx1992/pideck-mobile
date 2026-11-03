/**
 * 上游同步守门：注入的安全区样式是按 class 字符串命中的，上游改一次 class 就会静默失效
 * （表现为「右侧栏的关闭按钮又跑到状态栏里去了」）。这里直接读 vendor 源码对账。
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";

import { MOBILE_OVERLAY_CLASSES, OVERLAY_RULES, PATCHED_CLASSES } from "./safe-area-css.ts";

const rendererDir = path.join(import.meta.dirname, "..", "vendor", "pideck", "src", "renderer", "src");
const webDir = path.join(rendererDir, "web");
const read = (file: string): string => readFileSync(path.join(webDir, file), "utf8");
/** `where` 记的是 `路径/文件.tsx:行号（说明）`，对账只需要文件名。 */
const fileOf = (where: string): string => /([\w.-]+\.tsx):\d+/.exec(where)?.[1] ?? "";

describe("覆盖层选择器仍然命中上游源码", () => {
	for (const rule of OVERLAY_RULES) {
		it(`${rule.where} 里还有 \`${rule.token}\``, () => {
			assert.ok(read(fileOf(rule.where)).includes(rule.token), `上游改了 class：${rule.token}`);
		});
	}
});

describe("主壳的 env() utility 仍然在上游源码里", () => {
	for (const rule of PATCHED_CLASSES) {
		// `pb-[max(env(...)` 这种 token 是前缀，源码里后面还接着 `),0.5rem)]`
		const needle = rule.token.replace(/\[$/, "");
		it(`${rule.where} 里还有 \`${needle}\``, () => {
			assert.ok(read(fileOf(rule.where)).includes(needle), `上游改了 class：${needle}`);
		});
	}
});

describe("移动端 CSS 覆盖层仍然存在", () => {
	const css = readFileSync(path.join(webDir, "web.css"), "utf8");

	for (const rule of MOBILE_OVERLAY_CLASSES) {
		it(`web.css 里还有 \`${rule.selector}\` 的 fixed 规则（${rule.where}）`, () => {
			// 同一个选择器在桌面上也有一次（flex 子项），移动端那份在媒体查询里，要取最后一处
			const at = css.lastIndexOf(rule.selector);
			assert.ok(at >= 0, `上游改了选择器：${rule.selector}`);
			assert.ok(/position:\s*fixed/.test(css.slice(at, at + 400)), `上游不再把它改成 fixed：${rule.selector}`);
		});
	}
});
