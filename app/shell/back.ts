/**
 * Android 返回键 / 全面屏手势。
 *
 * 以前的写法是 `canGoBack ? history.back() : location.replace("/")`，在 WebView 里几乎
 * 永远走前一支：上游给每个覆盖层压一条 `__pideckDismiss` 记录，**选中会话还会
 * `pushState("/s/<id>")`**（`vendor/pideck/src/renderer/src/web/WebChatApp.tsx:228-241`）。
 * 于是返回手势变成了「浏览器后退」：关掉面板之后还在会话之间一格一格退，
 * 与手机上的直觉完全不符。
 *
 * 现在的规则（对齐 Android 习惯，会话路由不参与返回）：
 *   1. 栈顶是我们（上游）压的覆盖层记录 → 退掉它，popstate 会让上游关掉那一层；
 *   2. 否则就地处理：聊天页回启动页，启动页连按两次退出。
 */

import { App } from "@capacitor/app";

/** 上游 `useDismissOnBack.ts:4` 的标记键名，改名要同步。 */
export const DISMISS_MARKER = "__pideckDismiss";

export type BackAction = "dismiss" | "warn" | "act";

/**
 * 该做什么，纯函数便于单测。
 *
 * @param hasLayer       栈顶是不是覆盖层记录
 * @param sinceLastPress 距上次按键的毫秒数（从未按过传 Infinity）
 * @param confirmAfter   连按确认窗口；不传表示不需要确认
 */
export function resolveBackAction(
	hasLayer: boolean,
	sinceLastPress: number,
	confirmAfter?: number,
): BackAction {
	if (hasLayer) return "dismiss";
	if (confirmAfter !== undefined && sinceLastPress > confirmAfter) return "warn";
	return "act";
}

/** 栈顶是否压着覆盖层（抽屉 / 全屏预览 / 底部弹层）。 */
export function hasOpenDismissLayer(): boolean {
	const state = history.state as Record<string, unknown> | null;
	return state?.[DISMISS_MARKER] === true;
}

/**
 * 装上返回处理，返回卸载函数。
 *
 * `onBack` 是「没有东西可关时要做什么」。给了 `confirmWithinMs` 就要求连按两次
 * （第一次只调 `onWarn`），用于启动页——那里退出应用是不可逆的。
 */
export function installBackHandler(options: {
	onBack: () => void | Promise<void>;
	confirmWithinMs?: number;
	onWarn?: () => void;
}): () => void {
	let lastPress = Number.NEGATIVE_INFINITY;

	const pending = App.addListener("backButton", () => {
		const sinceLastPress = Date.now() - lastPress;
		const action = resolveBackAction(hasOpenDismissLayer(), sinceLastPress, options.confirmWithinMs);

		if (action === "dismiss") {
			// 上游的 popstate 处理器会关掉最上面那一层，并保持历史栈干净
			history.back();
			return;
		}
		if (action === "warn") {
			lastPress = Date.now();
			options.onWarn?.();
			return;
		}
		lastPress = Number.NEGATIVE_INFINITY;
		void options.onBack();
	});

	return () => {
		void pending.then((handle) => handle.remove()).catch(() => undefined);
	};
}
