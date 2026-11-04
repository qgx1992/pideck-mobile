/**
 * 上游同步守门：动效层的选择器是照着 vendor 里的 class 与 keyframes 名写的，
 * 上游改一次就会静默失效（表现为「抽屉又变成硬切」「消息又弹一下」）。这里直接读源码对账。
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";

import { MOTION_RULES, MOTION_VARS, buildMotionCss } from "./motion.ts";

const rendererDir = path.join(import.meta.dirname, "..", "vendor", "pideck", "src", "renderer", "src");
const read = (...parts: string[]): string => readFileSync(path.join(rendererDir, ...parts), "utf8");

describe("动效层的锚点仍在上游源码里", () => {
	const css = read("web", "web.css");
	it("左侧会话栏仍是 ≤760px 的 fixed 抽屉，且开关 class 叫 mobile-open", () => {
		assert.ok(css.includes(".app.wechat-shell > .chat-list-pane"), "上游改了选择器");
		assert.ok(css.includes(".chat-list-pane.mobile-open"), "打开态的 class 变了");
		assert.ok(css.includes(".mobile-sidebar-backdrop"), "遮罩的 class 变了");
	});

	const sheet = read("web", "WebBottomSheet.tsx");
	it("底部弹层仍带 max-h-[85dvh] + rounded-t-2xl", () => {
		assert.ok(sheet.includes("max-h-[85dvh]"), "面板高度 class 变了");
		assert.ok(sheet.includes("rounded-t-2xl"), "圆角 class 变了");
	});

	const timeline = read("styles", "timeline.css");
	it("message-enter / top-enter 两个 keyframes 还在", () => {
		assert.ok(timeline.includes("@keyframes message-enter"), "上游改了 keyframes 名");
		assert.ok(timeline.includes("@keyframes top-enter"), "上游改了 keyframes 名");
	});

	const surface = read("components", "session", "SurfaceComponents.tsx");
	it("新消息入场仍挂在 user-turn--fresh（时长靠它覆盖）", () => {
		assert.ok(surface.includes("user-turn--fresh"), "上游改了 fresh 的 class");
		assert.ok(surface.includes("animate-[message-enter_"), "上游不再用 message-enter 做入场");
	});
});

describe("生成的样式表", () => {
	const css = buildMotionCss();

	it("每条规则都进了样式表", () => {
		for (const rule of MOTION_RULES) assert.ok(css.includes(rule.css), `漏了：${rule.where}`);
	});

	it("缓动与时长来自 MOTION_VARS（一处可调）", () => {
		assert.ok(css.includes(`--pd-ease-enter: ${MOTION_VARS.enter}`));
		assert.ok(css.includes(`--pd-dur-slow: ${MOTION_VARS.slow}`));
	});

	it("两侧抽屉方向时长不同：开 280ms、关 180ms", () => {
		assert.ok(/chat-list-pane \{[^}]*transform 280ms/.test(css), "开抽屉的时长丢了");
		assert.ok(/:not\(\.mobile-open\)[^}]*transform 180ms/.test(css), "关抽屉要更快");
	});
});
