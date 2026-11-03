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

import { DISMISS_MARKER, resolveBackAction } from "./back.ts";

const upstream = readFileSync(
	path.join(import.meta.dirname, "..", "vendor", "pideck", "src", "renderer", "src", "web", "useDismissOnBack.ts"),
	"utf8",
);

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
