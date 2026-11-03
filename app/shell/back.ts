/**
 * Android 返回键 / 全面屏手势。
 *
 * 以前的写法是 `canGoBack ? history.back() : location.replace("/")`，在 WebView 里几乎
 * 永远走前一支：上游给每个覆盖层压一条 `__pideckDismiss` 记录，**选中会话还会
 * `pushState("/s/<id>")`**（`vendor/pideck/src/renderer/src/web/WebChatApp.tsx:228-241`）。
 * 于是返回手势变成了「浏览器后退」：关掉面板之后还在会话之间一格一格退。
 *
 * 现在的顺序（对齐 Android 习惯，会话路由不参与返回）：
 *   1. 有二级面板开着 → 只关那一层（下拉菜单 / 底部弹层 / 右侧栏 / 文件预览 / 左侧会话栏）；
 *   2. 什么都没有 → 就地处理：聊天页回启动页，启动页连按两次退出。
 *
 * 注意：上游只有 `WebWorkspaceDrawer` 与 `WebFilePreview` 自己订阅了返回（压 `__pideckDismiss`
 * 记录）。左侧会话栏（`WebSidebar` 的 `mobileOpen`）、`WebBottomSheet` 家族（模型 / 思考 /
 * 后端 / 技能 / 搜索 / 提示词）、以及安全等级这类 Radix 下拉菜单**都不压记录**，
 * 只认各自的关闭按钮 / Escape / 点击外部 —— 所以这些层由壳这边按选择器命中后自己关。
 */

import { App } from "@capacitor/app";

/** 上游 `useDismissOnBack.ts:4` 的标记键名，改名要同步。 */
export const DISMISS_MARKER = "__pideckDismiss";

export type BackAction = "dismiss" | "warn" | "act";

/**
 * 该做什么，纯函数便于单测。
 *
 * @param hasLayer       还有可以关的层
 * @param sinceLastPress 距上次按键的毫秒数（从没按过传 Infinity）
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

/** 栈顶是否压着上游自己的覆盖层（右侧栏 / 全屏文件预览）。 */
export function hasOpenDismissLayer(): boolean {
	const state = history.state as Record<string, unknown> | null;
	return state?.[DISMISS_MARKER] === true;
}

/** 合成一次 Escape：`WebBottomSheet` 与 Radix 弹层都是 document 级 keydown 监听。 */
function dispatchEscape(): void {
	document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true }));
}

/**
 * 合成一次「点在外面」：Radix 的 DismissableLayer 在 document 上监听 `pointerdown`（捕获阶段），
 * 点外部就关。用它而不是 Escape，是因为底部弹层也在 document 上听 Escape ——
 * 一个 Escape 会把「弹层 + 里面的下拉菜单」一起关掉，那就不是逐层退了。
 */
function dispatchOutsidePointer(): void {
	const event = typeof PointerEvent === "function"
		? new PointerEvent("pointerdown", { bubbles: true, cancelable: true })
		: new Event("pointerdown", { bubbles: true, cancelable: true });
	document.body.dispatchEvent(event);
}

/** 底部弹层（`WebBottomSheet` 家族）与 Radix Dialog（搜索 / 技能面板）都算这一层。 */
const DIALOG_SELECTOR = '[role="dialog"][aria-modal="true"], [role="dialog"][data-state="open"]';

/** 点某个元素（找不到就返回 false，让调用方走别的路子）。 */
function click(selector: string): boolean {
	const element = document.querySelector<HTMLElement>(selector);
	if (!element) return false;
	element.click();
	return true;
}

export interface BackLayer {
	id: string;
	/** 这一层按什么认（DOM 选择器，或上游压的历史记录）。 */
	selector: string;
	/** 选择器对账用的上游出处，`文件:行号`。 */
	where: string;
	visible: () => boolean;
	close: () => boolean;
}

/**
 * 从最上面一层往下排。顺序有意义：下拉菜单通常开在弹层/抽屉之上，所以排最前；
 * 上游自己管的两层（右侧栏 → 文件预览）靠 `history.back()` 逐层退，历史栈天然是 LIFO。
 */
export const BACK_LAYERS: BackLayer[] = [
	{
		id: "radix-popper",
		selector: "[data-radix-popper-content-wrapper]",
		where: "components/ui-shadcn/dropdown-menu.tsx（安全等级、模型等下拉）",
		visible: () => Boolean(document.querySelector("[data-radix-popper-content-wrapper]")),
		close: () => {
			dispatchOutsidePointer();
			// 合成 PointerEvent 的 pointerType 是空串，Radix 某些版本会当成「非指针」忽略；
			// 下一拍还在就补一次 Escape。那时弹层已经不在了，不会连带关掉底下那一层。
			setTimeout(() => {
				if (document.querySelector("[data-radix-popper-content-wrapper]")) dispatchEscape();
			}, 90);
			return true;
		},
	},
	{
		id: "dialog",
		selector: 'role=dialog（aria-modal 或 data-state=open）',
		where: "web/WebBottomSheet.tsx:80 / WebSearchDialog.tsx:33 / WebSkillsExtensionsDialog.tsx:62",
		visible: () => Boolean(document.querySelector(DIALOG_SELECTOR)),
		close: () => {
			dispatchEscape();
			return true;
		},
	},
	{
		id: "workspace-drawer",
		selector: DISMISS_MARKER,
		where: "web/WebWorkspaceDrawer.tsx:23",
		visible: hasOpenDismissLayer,
		close: () => {
			// 上游的 popstate 处理器关掉那一层，并保持历史栈干净
			history.back();
			return true;
		},
	},
	{
		id: "mobile-sidebar",
		selector: ".mobile-sidebar-backdrop.is-open",
		where: "web/WebSidebar.tsx:314",
		visible: () => Boolean(document.querySelector(".mobile-sidebar-backdrop.is-open")),
		close: () => {
			if (click(".mobile-sidebar-close")) return true;
			dispatchEscape();
			return true;
		},
	},
];

/** 还有没有可以关的层（只看可见性，不动手）。 */
export function hasAnyLayer(layers: readonly BackLayer[] = BACK_LAYERS): boolean {
	return layers.some((layer) => layer.visible());
}

/**
 * 关掉最上面一层。返回 false 表示没有可关的层 —— 调用方该执行「离开当前页 / 退出」。
 */
export function closeTopLayer(layers: readonly BackLayer[] = BACK_LAYERS): boolean {
	for (const layer of layers) {
		if (!layer.visible()) continue;
		layer.close();
		return true;
	}
	return false;
}

/**
 * 装上返回处理，返回卸载函数。
 *
 * - `onIntercept`：先问一句，返回 true 表示这一按已经被处理掉（启动页的扫码取景框就
 *   用它取消扫码，而不是退出应用）；
 * - `onBack`：没有层可关时要做什么；
 * - 给了 `confirmWithinMs` 就要求连按两次（第一次只调 `onWarn`）。
 */
export function installBackHandler(options: {
	onBack: () => void | Promise<void>;
	onIntercept?: () => boolean;
	confirmWithinMs?: number;
	onWarn?: () => void;
}): () => void {
	let lastPress = Number.NEGATIVE_INFINITY;

	const pending = App.addListener("backButton", () => {
		if (options.onIntercept?.()) return;

		const sinceLastPress = Date.now() - lastPress;
		const action = resolveBackAction(hasAnyLayer(), sinceLastPress, options.confirmWithinMs);

		if (action === "dismiss") {
			closeTopLayer();
			lastPress = Number.NEGATIVE_INFINITY;
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
