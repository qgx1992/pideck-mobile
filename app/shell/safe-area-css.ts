/**
 * 安全区样式表的纯函数部分（不碰 DOM / Capacitor，便于单测）。
 */

/** 上游会带 `env()` 的 class 片段 → 补哪一侧的 padding。`where` 给守门单测对账用。 */
export const PATCHED_CLASSES: Array<{
	token: string;
	side: "top" | "bottom";
	base: string;
	where: string;
}> = [
	{
		token: "pt-[env(safe-area-inset-top)]",
		side: "top",
		base: "env(safe-area-inset-top, 0px)",
		where: "vendor/pideck/src/renderer/src/web/WebChatApp.tsx:1299（聊天主壳）",
	},
	{
		token: "pb-[env(safe-area-inset-bottom)]",
		side: "bottom",
		base: "env(safe-area-inset-bottom, 0px)",
		where: "vendor/pideck/src/renderer/src/web/WebChatApp.tsx:1299（聊天主壳）",
	},
	{
		token: "pb-[max(env(safe-area-inset-bottom)",
		side: "bottom",
		base: "max(env(safe-area-inset-bottom, 0px), 0.5rem)",
		where: "vendor/pideck/src/renderer/src/web/WebSidebar.tsx:184（侧栏列表底）",
	},
];

/**
 * 全屏覆盖层的 class 片段 → 该让哪几侧。
 *
 * 这些面板是 `fixed`/`absolute` 定位，绕开了页面主壳的内边距：右侧栏的头（含关闭按钮）
 * 会顶进状态栏，底部弹层 / 文件预览的底边会被手势条盖住。`token` 必须与 vendor 里的
 * class 字符串逐字一致，`app/shell/overlay-selectors.test.ts` 会在上游同步后检查它们还在。
 */
export const OVERLAY_RULES: Array<{ token: string; sides: Array<"top" | "bottom">; where: string }> = [
	{
		token: "absolute top-0 right-0 bottom-0",
		sides: ["top", "bottom"],
		where: "vendor/pideck/src/renderer/src/web/WebWorkspaceDrawer.tsx:28（右侧 Git/文件栏）",
	},
	{
		token: "fixed inset-0 z-50 flex flex-col",
		sides: ["top", "bottom"],
		where: "vendor/pideck/src/renderer/src/web/WebFilePreview.tsx:305（全屏文件预览）",
	},
	{
		token: "fixed inset-0 z-50 flex items-end",
		sides: ["bottom"],
		where: "vendor/pideck/src/renderer/src/web/WebBottomSheet.tsx:80（底部弹层）",
	},
];

/**
 * 靠 CSS 类（不是 utility 字符串）变成覆盖层的元素。
 *
 * 左侧会话栏在 ≤760px 时由 `web.css` 改成 `position: fixed; inset: 0 auto 0 0`
 * （`web.css:422-435` 的媒体查询），于是它和一个独立的 fixed 抽屉一样从视口顶边起画，
 * 主壳的 padding 不再管用——标题栏和右上角关闭按钮又会被状态栏盖住。
 * 只在移动宽度下补，桌面宽度它仍是普通 flex 子项（补了会双份）。
 */
export const MOBILE_OVERLAY_CLASSES: Array<{
	selector: string;
	sides: Array<"top" | "bottom">;
	where: string;
	media: string;
}> = [
	{
		selector: ".app.wechat-shell > .chat-list-pane",
		sides: ["top", "bottom"],
		where: "vendor/pideck/src/renderer/src/web/web.css:422（≤760px 的左侧会话栏）",
		media: "(max-width: 760px)",
	},
];

export const round = (value: number): number => {
	const rounded = Math.round(value * 100) / 100;
	// 负零会写进 CSS 变成 `-0px`，归一掉
	return rounded === 0 ? 0 : rounded;
};

const inset = (side: "top" | "bottom"): string =>
	`max(env(safe-area-inset-${side}, 0px), var(--pideck-inset-${side}, 0px))`;

/**
 * 生成注入用样式表。
 *
 * `extra` 是「原生实测重叠 - env() 已经在算的那部分」，两边相加正好等于真实重叠；
 * `!important` 是必需的：Tailwind 的 utility 层比这层更靠后。
 * 覆盖层用 `--pideck-inset-*`（原生实测的原值），因为它们根本没写过 env()。
 */
export function buildSafeAreaCss(extra: { top: number; bottom: number }): string {
	const vars = `:root { --pideck-safe-top: ${round(extra.top)}px; --pideck-safe-bottom: ${round(extra.bottom)}px; }`;
	const rules = PATCHED_CLASSES.map(
		({ token, side, base }) =>
			`[class*="${token}"] { padding-${side}: calc(${base} + var(--pideck-safe-${side}, 0px)) !important; }`,
	);
	// 手势条下面那一条是页面画布（WebView 背景是透明的），给它铺主题底色，
	// 否则深色主题下会露出窗口背景（黑）而输入框上方是 #121212，能看出接缝。
	const canvas = "html { background-color: var(--color-bg-app, #ffffff); }";
	const overlays = OVERLAY_RULES.map(
		({ token, sides }) =>
			`[class*="${token}"] { ${sides.map((side) => `padding-${side}: ${inset(side)};`).join(" ")} }`,
	);
	const mobileOverlays = MOBILE_OVERLAY_CLASSES.map(
		({ selector, sides, media }) =>
			`@media ${media} { ${selector} { ${sides.map((side) => `padding-${side}: ${inset(side)} !important;`).join(" ")} } }`,
	);
	return [vars, canvas, ...rules, ...overlays, ...mobileOverlays].join("\n");
}
