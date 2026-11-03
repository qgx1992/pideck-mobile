import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { buildSafeAreaCss, round } from "./safe-area-css.ts";

describe("buildSafeAreaCss", () => {
	it("把实测差值写成 CSS 变量", () => {
		const css = buildSafeAreaCss({ top: 24, bottom: 21.5 });
		assert.match(css, /--pideck-safe-top: 24px/);
		assert.match(css, /--pideck-safe-bottom: 21\.5px/);
	});

	it("三条 env() utility 都被补上（属性包含匹配，不用转义方括号）", () => {
		const css = buildSafeAreaCss({ top: 0, bottom: 21 });
		assert.match(css, /\[class\*="pt-\[env\(safe-area-inset-top\)\]"\] \{ padding-top: calc\(env\(safe-area-inset-top, 0px\) \+ var\(--pideck-safe-top, 0px\)\) !important; \}/);
		assert.match(css, /\[class\*="pb-\[env\(safe-area-inset-bottom\)\]"\] \{ padding-bottom: calc\(env\(safe-area-inset-bottom, 0px\) \+ var\(--pideck-safe-bottom, 0px\)\) !important; \}/);
		// 弹层/侧栏里的 pb-[max(env(...),0.5rem)] 不能丢（丢了下拉菜单底部压住手势条）
		assert.match(css, /pb-\[max\(env\(safe-area-inset-bottom\)/);
		assert.match(css, /padding-bottom: calc\(max\(env\(safe-area-inset-bottom, 0px\), 0\.5rem\) \+ var\(--pideck-safe-bottom, 0px\)\) !important/);
	});

	it("给画布铺主题底色，否则手势条下面露出窗口黑底", () => {
		assert.match(buildSafeAreaCss({ top: 0, bottom: 0 }), /html \{ background-color: var\(--color-bg-app, #ffffff\); \}/);
	});

	it("差值为 0 时变量就是 0px（不重叠的设备不该被推走）", () => {
		const css = buildSafeAreaCss({ top: 0, bottom: 0 });
		assert.match(css, /--pideck-safe-top: 0px/);
		assert.match(css, /--pideck-safe-bottom: 0px/);
	});

	it("round 保留两位小数，避免 calc 里出现长尾浮点", () => {
		assert.equal(round(21.333333), 21.33);
		assert.equal(round(-0.0001), 0);
	});
});
