/**
 * 移动端动效层。
 *
 * 上游（桌面端 web 客户端）的动效是按鼠标/大屏调的：多以 `ease-out` 直出，容量大、
 * 位移长。搬到手机上有两个具体问题：
 *
 *   1. 侧栏抽屉是「瞬时到位」——`web.css` 里只给了 180ms 的 `ease-out`，手机上看起来像
 *      直接闪出来，没有「抽屉被拉出来」的实体感；
 *   2. 新消息 10px 的位移在长列表里会跟「贴底追滚」叠在一起，显得弹一下。
 *
 * 这里不碰 `vendor/`（下次同步上游会丢），只在运行时往 head 末尾追加一张样式表：
 * 同优先级靠「后出现」赢过上游的 CSS，keyframes 同名重定义也是后者覆盖。
 *
 * 调参全在这个文件顶部：`MOTION_VARS` 改缓动/时长，`MOTION_CSS` 里每条规则上面写了
 * 它对应上游哪一处（`where`），改完 `npm run verify` 会跑 `motion.test.ts` 对账
 * ——上游改 class 就会失败，而不是静默失效。
 */

const STYLE_ID = "pideck-motion";

/** 三档时长 + 两条缓动。改这里就是全局调手感。 */
export const MOTION_VARS = {
	/** 进场：起手快、尾巴长，落位有「惯性」感（iOS 抽屉那套） */
	enter: "cubic-bezier(0.32, 0.72, 0, 1)",
	/** 退场：加速离开，别让人等 */
	exit: "cubic-bezier(0.4, 0, 1, 1)",
	fast: "130ms",
	base: "200ms",
	slow: "280ms",
} as const;

/** 注入的样式表。`where` 只给人看，`motion.test.ts` 会按它去 vendor 里对账。 */
export const MOTION_RULES: Array<{ where: string; css: string }> = [
	{
		where: "vendor/pideck/src/renderer/src/web/web.css:422（≤760px 左侧会话栏）",
		css: `@media (max-width: 760px) {
			.app.wechat-shell > .chat-list-pane {
				transition: transform 280ms ${MOTION_VARS.enter}, box-shadow 280ms ease-out !important;
				will-change: transform;
			}
			/* 关抽屉走退场曲线：先给 .mobile-open 移除后的状态，保证两个方向时长不同 */
			.app.wechat-shell > .chat-list-pane:not(.mobile-open) {
				transition: transform 180ms ${MOTION_VARS.exit}, box-shadow 180ms ease-in !important;
			}
			.mobile-sidebar-backdrop {
				transition: opacity 240ms ease-out !important;
			}
		}`,
	},
	{
		where: "vendor/pideck/src/renderer/src/web/WebBottomSheet.tsx:87（底部弹层上滑）",
		css: `@media (max-width: 640px) {
			[class*="max-h-[85dvh]"][class*="rounded-t-2xl"] {
				animation: pideck-sheet-up 260ms ${MOTION_VARS.enter} both !important;
			}
		}
		@keyframes pideck-sheet-up {
			from { transform: translateY(22px); opacity: 0; }
			to { transform: translateY(0); opacity: 1; }
		}`,
	},
	{
		where: "vendor/pideck/src/renderer/src/styles/timeline.css:2333（新消息进场）",
		css: `@keyframes message-enter {
			from { opacity: 0; transform: translateY(6px) scale(0.99); }
			to { opacity: 1; transform: translateY(0) scale(1); }
		}`,
	},
	{
		where: "vendor/pideck/src/renderer/src/components/session/SurfaceComponents.tsx:888（时长来自这条 arbitrary utility）",
		css: `.user-turn--fresh {
			animation-duration: 200ms !important;
		}`,
	},
	{
		where: "vendor/pideck/src/renderer/src/styles/timeline.css:116（顶部历史展开）",
		css: `@keyframes top-enter {
			from { opacity: 0; transform: translateY(-6px); }
			to { opacity: 1; transform: translateY(0); }
		}`,
	},
	{
		where: "touch 反馈：侧栏会话行 / 项目行",
		css: `@media (hover: none) {
			.app.wechat-shell .session-row,
			.app.wechat-shell .project-row {
				transition: transform 90ms ease-out;
			}
			.app.wechat-shell .session-row:active,
			.app.wechat-shell .project-row:active {
				transform: scale(0.985);
			}
		}`,
	},
];

/** 拼成一张样式表；纯函数，便于单测。 */
export function buildMotionCss(vars: typeof MOTION_VARS = MOTION_VARS): string {
	const tokens = `:root {
		--pd-ease-enter: ${vars.enter};
		--pd-ease-exit: ${vars.exit};
		--pd-dur-fast: ${vars.fast};
		--pd-dur-base: ${vars.base};
		--pd-dur-slow: ${vars.slow};
	}`;
	return [tokens, ...MOTION_RULES.map((rule) => rule.css)].join("\n");
}

/**
 * 追加到 head **末尾**，并在样式加载完成后把它再挪一次。
 *
 * 不能只在 boot 里插一次：`boot.js` 是 vite 注入到 `<head>` 最前面的，这时上游的 CSS
 * 可能还没插进来（打包后的 `<link>` 由 html 里的顺序决定）。挪到最后才能保证
 * 同优先级下我们赢、同名 keyframes 也是我们覆盖。
 */
export function installMotion(root: Document = document, win: Window = window): void {
	const apply = (): void => {
		const existing = root.getElementById(STYLE_ID);
		if (existing) {
			// 已经在表里：往后挪一位，确保仍在上游样式之后
			root.head.appendChild(existing);
			return;
		}
		const style = root.createElement("style");
		style.id = STYLE_ID;
		style.textContent = buildMotionCss();
		root.head.appendChild(style);
	};
	apply();
	if (root.readyState === "loading") root.addEventListener("DOMContentLoaded", apply, { once: true });
	win.addEventListener("load", apply, { once: true });
}
