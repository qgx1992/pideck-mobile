/**
 * 安全区样式表的纯函数部分（不碰 DOM / Capacitor，便于单测）。
 */

/** 上游会带 `env()` 的 class 片段 → 补哪一侧的 padding。 */
export const PATCHED_CLASSES: Array<{ token: string; side: "top" | "bottom"; base: string }> = [
	{ token: "pt-[env(safe-area-inset-top)]", side: "top", base: "env(safe-area-inset-top, 0px)" },
	{ token: "pb-[env(safe-area-inset-bottom)]", side: "bottom", base: "env(safe-area-inset-bottom, 0px)" },
	{
		token: "pb-[max(env(safe-area-inset-bottom)",
		side: "bottom",
		base: "max(env(safe-area-inset-bottom, 0px), 0.5rem)",
	},
];

export const round = (value: number): number => {
	const rounded = Math.round(value * 100) / 100;
	// 负零会写进 CSS 变成 `-0px`，归一掉
	return rounded === 0 ? 0 : rounded;
};

/**
 * 生成注入用样式表。
 *
 * `extra` 是「原生实测重叠 - env() 已经在算的那部分」，两边相加正好等于真实重叠；
 * `!important` 是必需的：Tailwind 的 utility 层比这层更靠后。
 */
export function buildSafeAreaCss(extra: { top: number; bottom: number }): string {
	const vars = `:root { --pideck-safe-top: ${round(extra.top)}px; --pideck-safe-bottom: ${round(extra.bottom)}px; }`;
	const rules = PATCHED_CLASSES.map(
		({ token, side, base }) =>
			`[class*="${token}"] { padding-${side}: calc(${base} + var(--pideck-safe-${side}, 0px)) !important; }`,
	);
	// 手势条下面那一条是页面画布（WebView 背景是透明的），给它铺主题底色，
	// 否则深色主题下会露出窗口背景（黑）而输入框上方是 #121212，能看出接缝。
	return [vars, "html { background-color: var(--color-bg-app, #ffffff); }", ...rules].join("\n");
}
