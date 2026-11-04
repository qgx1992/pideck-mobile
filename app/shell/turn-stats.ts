/**
 * 回合统计行：把「上一次回答花了多久、跑了多少 token、多快」显示在输入卡下面。
 *
 * 桌面端的这条带子在 `components/session/ComposerStatsLine.tsx`，数据来自主进程算好的
 * `AgentRuntimeState`（`durationMs` / `ttftMs` / `tps` / `endToEndTps`）。**Web 端没有这条带子**
 * （`web/WebComposer.tsx` 只画输入卡，`/api/state` 的 runtimes 也只有 status），
 * 所以手机上一直看不到耗时与 tok/s。
 *
 * 这里不碰 `vendor/`，用两样现成的东西自己算：
 *
 *   - 数据：`GET /api/sessions/:id/messages/page?pageSize=1` 里 assistant 消息的
 *     `meta = { usage: { inputTokens, outputTokens, cacheReadTokens, cost }, durationMs, startedAt, endedAt }`。
 *     这正是桌面端结算同一条回复用的原始数（`durationMs` = 请求起点 → message_end）。
 *   - 时机：上游 composer 在 `streaming` 时渲染「停止」按钮、结束后换回「发送」按钮
 *     （`data-testid="web-composer-stop[-compact]"`），DOM 上直接能看到这一跳，不用自己轮询。
 *
 * 口径与桌面端对齐：桌面同时给「流式 tok/s（扣掉首字等待）」和「端到端 tok/s」，
 * 我们拿不到首字时刻（`meta` 里没存），所以只报**端到端**，并且文案里写清是端到端。
 */

const STATS_ID = "pideck-turn-stats";

/** 触发后等多久再拉：给上游把权威消息页重拉回来留时间。 */
const REFRESH_DELAY_MS = 700;
/** 一次会话只缓存一份（切走再切回来不重复拉）。 */

export interface TurnStats {
	/** 请求起点 → message_end（不含前序工具执行） */
	durationMs: number;
	outputTokens: number;
	inputTokens?: number;
	cacheReadTokens?: number;
	cost?: number;
	/** 端到端速度：output tokens ÷ durationMs（桌面端 `endToEndTps` 同口径） */
	endToEndTps?: number;
}

type MessageMeta = {
	durationMs?: unknown;
	startedAt?: unknown;
	endedAt?: unknown;
	usage?: {
		inputTokens?: unknown;
		outputTokens?: unknown;
		cacheReadTokens?: unknown;
		cacheWriteTokens?: unknown;
		reasoningTokens?: unknown;
		cost?: unknown;
	};
};

const num = (value: unknown): number | undefined =>
	typeof value === "number" && Number.isFinite(value) ? value : undefined;

/**
 * 一条 assistant 消息的 `meta` → 统计（纯函数）。
 *
 * 时间优先用 `durationMs`；缺了（老会话/流式中途落盘）就用 `endedAt - startedAt`。
 * 两者都没有、或者输出 0 token → null（宁可不显示，也不显示「0.0s」这种噪声）。
 */
export function statsFromMessage(value: unknown): TurnStats | null {
	if (!value || typeof value !== "object") return null;
	const meta = value as MessageMeta;
	const usage = meta.usage && typeof meta.usage === "object" ? meta.usage : undefined;

	const counted = num(meta.durationMs);
	const started = num(meta.startedAt);
	const ended = num(meta.endedAt);
	const durationMs = counted && counted > 0 ? counted : started != null && ended != null ? ended - started : 0;
	const outputTokens = num(usage?.outputTokens) ?? 0;
	if (!(durationMs > 0) || !(outputTokens > 0)) return null;

	const inputTokens = num(usage?.inputTokens);
	const cacheReadTokens = num(usage?.cacheReadTokens);
	const cost = num(usage?.cost);
	const endToEndTps = outputTokens / (durationMs / 1000);
	return {
		durationMs,
		outputTokens,
		...(inputTokens === undefined ? {} : { inputTokens }),
		...(cacheReadTokens === undefined ? {} : { cacheReadTokens }),
		...(cost === undefined ? {} : { cost }),
		...(Number.isFinite(endToEndTps) && endToEndTps > 0 ? { endToEndTps } : {}),
	};
}

/** 毫秒 → `5.3s` / `1m12s`。与桌面 `formatDuration` 的口径一致：一个量级只留一位小数。 */
export function formatSeconds(ms: number): string {
	const seconds = ms / 1000;
	if (seconds < 60) return `${seconds.toFixed(1)}s`;
	const total = Math.round(seconds);
	const m = Math.floor(total / 60);
	const s = total % 60;
	return `${m}m${String(s).padStart(2, "0")}s`;
}

/** token 数友好化：`433` / `1.2k` / `18k`。 */
export function formatTokenCount(tokens: number): string {
	if (tokens < 1000) return String(Math.round(tokens));
	if (tokens < 10_000) return `${(tokens / 1000).toFixed(1)}k`;
	return `${Math.round(tokens / 1000)}k`;
}

/**
 * 统计 → 一行文本（纯函数）。
 *
 * 例：`回复 5.3s · 433 tok · 端到端 82 tok/s`。
 * 数字之间用中点分隔，让「时长 / 量 / 速度」三件事一眼分得开。
 */
export function formatTurnStats(stats: TurnStats): string {
	const parts = [`回复 ${formatSeconds(stats.durationMs)}`, `${formatTokenCount(stats.outputTokens)} tok`];
	if (stats.endToEndTps) parts.push(`端到端 ${formatTokensPerSecond(stats.endToEndTps)} tok/s`);
	return parts.join(" · ");
}

/** 速度：≥10 取整，否则一位小数（`82 tok/s` / `9.6 tok/s`）。 */
export function formatTokensPerSecond(tps: number): string {
	return tps >= 10 ? String(Math.round(tps)) : tps.toFixed(1);
}

/** 当前路由上的会话 id（上游 `/s/<id>`；无会话时为空串）。 */
export function sessionIdFromPath(pathname: string): string {
	const match = /^\/s\/([A-Za-z0-9_-]+)/.exec(pathname);
	return match?.[1] ?? "";
}

/** 从消息页响应里挑最后一条 assistant 的 meta（纯函数，便于测试）。 */
export function lastAssistantMessage(payload: unknown): unknown {
	const messages = (payload as { messages?: unknown })?.messages;
	if (!Array.isArray(messages)) return null;
	for (let i = messages.length - 1; i >= 0; i -= 1) {
		const message = messages[i] as { role?: unknown; meta?: unknown } | null;
		if (!message || typeof message !== "object") continue;
		if (message.role !== "assistant") continue;
		return message.meta ?? null;
	}
	return null;
}

/** 拉一次某个会话的「最后一轮」，解析出统计；失败/无数据 → null（静默）。 */
async function fetchTurnStats(sessionId: string): Promise<TurnStats | null> {
	try {
		// 不用 AbortController 超时：WebView 与 jsdom（冒烟脚本）两边的 signal 实现不一定
		// 能被 fetch 接受，失败一次就整条统计消失，不划算。真卡住的代价只是这条行晚出现。
		const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/messages/page?pageSize=1`, {
			headers: { accept: "application/json" },
		});
		if (!res.ok) return null;
		return statsFromMessage(lastAssistantMessage(await res.json()));
	} catch {
		return null;
	}
}

interface InstallOptions {
	/** 注入用的文档（测试传 jsdom 的 document）。 */
	doc?: Document;
	/** 拉数据（测试替换）。 */
	load?: (sessionId: string) => Promise<TurnStats | null>;
	/** 触发后的等待，测试可调小。 */
	delayMs?: number;
}

/**
 * 装上统计行。返回卸载函数。
 *
 * 三个触发点：会话切换（`popstate` + 上游的 `pushState`）、一轮结束（停止按钮消失）、
 * composer 迟到挂载（`MutationObserver` 兜底找挂载点）。
 */
export function installTurnStats(options: InstallOptions = {}): () => void {
	const doc = options.doc ?? document;
	const win = doc.defaultView ?? window;
	const load = options.load ?? fetchTurnStats;
	const delayMs = options.delayMs ?? REFRESH_DELAY_MS;

	let timer: ReturnType<typeof setTimeout> | null = null;
	let disposed = false;
	/** 最近一次要显示的文字：composer 迟到挂载时补画（否则数据先到、挂载点后到就丢了） */
	let current: string | null = null;
	/** 已经拉过的会话（避免每次 DOM 抖动都重拉） */
	const loadedFor = new Map<string, TurnStats | null>();
	/** 本轮开始时刻：跑着的时候显示「生成中 3.2s」，让「等多久了」有反馈 */
	let runStartedAt = 0;
	let ticker: ReturnType<typeof setInterval> | null = null;

	const element = (): HTMLElement | null => doc.getElementById(STATS_ID);

	const mount = (): HTMLElement | null => {
		const existing = element();
		if (existing) return existing;
		const form = doc.querySelector<HTMLFormElement>("form.composer");
		if (!form) return null;
		const node = doc.createElement("div");
		node.id = STATS_ID;
		// 与上游 `ComposerStatsLine` 同规格：11px / 三级文字色 / 等宽数字
		node.setAttribute(
			"style",
			[
				"display:flex",
				"align-items:center",
				"justify-content:center",
				"gap:6px",
				"padding:2px 12px 0",
				"font-size:11px",
				"line-height:16px",
				"color:var(--color-text-tertiary,#8b8b8b)",
				"text-align:center",
				"font-variant-numeric:tabular-nums",
				"white-space:nowrap",
				"overflow:hidden",
				"text-overflow:ellipsis",
			].join(";"),
		);
		node.hidden = true;
		form.appendChild(node);
		return node;
	};

	const paint = (text: string | null): void => {
		current = text;
		const node = mount();
		if (!node) return;
		if (!text) {
			node.hidden = true;
			node.textContent = "";
			return;
		}
		node.hidden = false;
		node.textContent = text;
	};

	const refresh = (force = false): void => {
		// 正在生成：这一行归计时器，别被上一轮的旧数字顶掉
		if (ticker) return;
		const sessionId = sessionIdFromPath(location.pathname);
		if (!sessionId) {
			paint(null);
			return;
		}
		if (!force && loadedFor.has(sessionId)) {
			const cached = loadedFor.get(sessionId) ?? null;
			paint(cached ? formatTurnStats(cached) : null);
			return;
		}
		void load(sessionId).then((stats) => {
			if (disposed) return;
			// 回来时已经切走了：丢掉，别把别人的数字画到当前会话上
			if (sessionIdFromPath(location.pathname) !== sessionId) return;
			loadedFor.set(sessionId, stats);
			paint(stats ? formatTurnStats(stats) : null);
		});
	};

	const stopTicker = (): void => {
		if (ticker) clearInterval(ticker);
		ticker = null;
		runStartedAt = 0;
	};

	const startTicker = (): void => {
		stopTicker();
		runStartedAt = Date.now();
		const tick = (): void => paint(`生成中 ${formatSeconds(Date.now() - runStartedAt)}`);
		tick();
		ticker = setInterval(tick, 200);
	};

	const schedule = (force: boolean): void => {
		if (timer) clearTimeout(timer);
		timer = setTimeout(() => {
			timer = null;
			refresh(force);
		}, delayMs);
	};

	// 会话切换：上游 pushState/replaceState 不派发事件，包一层
	const patchHistory = (): (() => void) => {
		const history = win.history;
		const originalPush = history.pushState.bind(history);
		const originalReplace = history.replaceState.bind(history);
		const onNavigate = (): void => schedule(false);
		history.pushState = ((...args: Parameters<History["pushState"]>) => {
			const result = originalPush(...args);
			onNavigate();
			return result;
		}) as History["pushState"];
		history.replaceState = ((...args: Parameters<History["replaceState"]>) => {
			const result = originalReplace(...args);
			onNavigate();
			return result;
		}) as History["replaceState"];
		return () => {
			history.pushState = originalPush;
			history.replaceState = originalReplace;
		};
	};

	const restoreHistory = patchHistory();
	const onPopState = (): void => schedule(false);
	win.addEventListener("popstate", onPopState);

	// 一轮结束：停止按钮消失 = 上游把 streaming 置回 false
	let stopVisible = Boolean(doc.querySelector('[data-testid^="web-composer-stop"]'));
	const observer = new MutationObserver(() => {
		const now = Boolean(doc.querySelector('[data-testid^="web-composer-stop"]'));
		if (!stopVisible && now) startTicker();
		if (stopVisible && !now) {
			stopTicker();
			schedule(true);
		}
		stopVisible = now;
		// composer 是异步挂载的：统计行还没地方待就先补挂载点，再把已有数据画上去
		if (!element() && mount() && current) paint(current);
	});
	observer.observe(doc.body, { childList: true, subtree: true });

	refresh(false);

	return () => {
		disposed = true;
		stopTicker();
		if (timer) clearTimeout(timer);
		observer.disconnect();
		win.removeEventListener("popstate", onPopState);
		restoreHistory();
		element()?.remove();
	};
}
