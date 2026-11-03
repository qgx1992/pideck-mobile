/**
 * 上游同步守门：注入的安全区样式是按 class 字符串命中的，上游改一次 class 就会静默失效
 * （表现为「右侧栏的关闭按钮又跑到状态栏里去了」）。这里直接读 vendor 源码对账。
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";

import { OVERLAY_RULES, PATCHED_CLASSES } from "./safe-area-css.ts";

const webDir = path.join(import.meta.dirname, "..", "vendor", "pideck", "src", "renderer", "src", "web");
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
