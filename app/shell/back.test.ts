/**
 * 返回键策略的守门测试。
 *
 * 上半部分锁行为（覆盖层优先、连按确认）；下半部分对账上游——`__pideckDismiss` 是
 * 上游 `useDismissOnBack.ts` 里写死的 key，上游一改名，「先关面板」就静默失效，
 * 表现为返回手势直接退出应用。
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";

import { BACK_LAYERS, DISMISS_MARKER, closeTopLayer, hasAnyLayer, resolveBackAction, type BackLayer } from "./back.ts";

const rendererDir = path.join(import.meta.dirname, "..", "vendor", "pideck", "src", "renderer", "src");
const webDir = path.join(rendererDir, "web");
const read = (file: string, from = webDir): string => readFileSync(path.join(from, file), "utf8");

const upstream = read("useDismissOnBack.ts");

/** 造一层假的：`visible` 固定，`close` 记一次调用。 */
const fakeLayer = (id: string, visible: boolean, calls: string[]): BackLayer => ({
	id,
	selector: id,
	where: id,
	visible: () => visible,
	close: () => {
		calls.push(id);
		return true;
	},
});

/** 「上次按键是很久以前」= 已经超出确认窗口 */
const LONG_AGO = Number.POSITIVE_INFINITY;

describe("resolveBackAction", () => {
	it("栈顶是覆盖层：先关层，不看确认窗口", () => {
		assert.equal(resolveBackAction(true, LONG_AGO, 2000), "dismiss");
		assert.equal(resolveBackAction(true, 10, 2000), "dismiss");
	});

	it("没有覆盖层：直接执行动作", () => {
		assert.equal(resolveBackAction(false, LONG_AGO), "act");
		// 很久没按过 → 超出窗口，先提示
		assert.equal(resolveBackAction(false, LONG_AGO, 2000), "warn");
	});

	it("连按确认窗口内才执行，超出窗口只提示", () => {
		assert.equal(resolveBackAction(false, 1999, 2000), "act");
		assert.equal(resolveBackAction(false, 2000, 2000), "act");
		assert.equal(resolveBackAction(false, 2001, 2000), "warn");
	});
});

describe("上游的覆盖层标记没变", () => {
	it(`useDismissOnBack.ts 里仍是 ${DISMISS_MARKER}`, () => {
		assert.ok(
			upstream.includes(`"${DISMISS_MARKER}"`),
			`上游把覆盖层标记改名了，同步 app/shell/back.ts 的 DISMISS_MARKER`,
		);
	});
});

describe("closeTopLayer / hasAnyLayer", () => {
	it("只关最上面一层，顺序即优先级", () => {
		const calls: string[] = [];
		const layers = [fakeLayer("top", true, calls), fakeLayer("below", true, calls)];
		assert.equal(hasAnyLayer(layers), true);
		assert.equal(closeTopLayer(layers), true);
		assert.deepEqual(calls, ["top"]);
	});

	it("上面那层不可见时关下一层", () => {
		const calls: string[] = [];
		const layers = [fakeLayer("top", false, calls), fakeLayer("below", true, calls)];
		assert.equal(closeTopLayer(layers), true);
		assert.deepEqual(calls, ["below"]);
	});

	it("一层都没有时返回 false，交给调用方退出", () => {
		const calls: string[] = [];
		const layers = [fakeLayer("top", false, calls), fakeLayer("below", false, calls)];
		assert.equal(hasAnyLayer(layers), false);
		assert.equal(closeTopLayer(layers), false);
		assert.deepEqual(calls, []);
	});
});

describe("各层选择器仍然命中上游源码", () => {
	it("左侧会话栏：backdrop 的 is-open 与关闭按钮都还在", () => {
		const sidebar = read("WebSidebar.tsx");
		assert.ok(sidebar.includes("mobile-sidebar-backdrop"), "上游改了 backdrop class");
		assert.ok(sidebar.includes("mobile-sidebar-close"), "上游改了关闭按钮 class");
	});

	it("底部弹层：仍是 role=dialog + aria-modal", () => {
		const sheet = read("WebBottomSheet.tsx");
		assert.ok(sheet.includes('role="dialog"'), "上游改了弹层 role");
		assert.ok(sheet.includes('aria-modal="true"'), "上游去掉了 aria-modal");
		assert.ok(sheet.includes('event.key === "Escape"'), "上游不再监听 Escape，壳要改成点关闭按钮");
	});

	it("右侧栏 / 文件预览仍自己订阅返回（压 __pideckDismiss）", () => {
		assert.ok(read("WebWorkspaceDrawer.tsx").includes("useDismissOnBack("), "右侧栏不再自己管返回");
		assert.ok(read("WebFilePreview.tsx").includes("useDismissOnBack("), "文件预览不再自己管返回");
	});

	it("Radix 弹层仍渲染到 data-radix-popper-content-wrapper 里", () => {
		const dropdown = read("dropdown-menu.tsx", path.join(rendererDir, "components", "ui-shadcn"));
		assert.ok(dropdown.includes("DropdownMenuContent"), "ui-shadcn 的 dropdown 结构变了");
		assert.equal(BACK_LAYERS[0]?.id, "radix-popper");
	});
});
