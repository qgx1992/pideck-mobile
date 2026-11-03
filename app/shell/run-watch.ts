/**
 * 后台看护的 JS 侧：什么时候需要原生服务。
 *
 * 原生 `RunWatchService` 自己订阅 `/api/events`、自己判断「跑完了 / 有人提问」，
 * 并在 App 不可见时发通知——它需要的是**在会话开始跑的那一刻就被启动**
 * （Android 12+ 禁止从后台启动前台服务，等切走了再启动会被拒）。
 *
 * 所以这里只做一件事：轮询运行态，发现「有 running」就 start，长时间没有就 stop。
 * 轮询用 `/api/sessions/runtimes`（约 3KB）而不是 `/api/state`（200KB+）。
 */

import { Capacitor, registerPlugin } from "@capacitor/core";

import type { DeviceRecord } from "./device.ts";

interface RunWatchApi {
	start(options: { baseUrl: string; token: string; titles: Record<string, string> }): Promise<void>;
	stop(): Promise<void>;
}

const RunWatch = registerPlugin<RunWatchApi>("RunWatch");

const RUNNING_STATUSES = new Set(["running", "starting"]);
const POLL_MS = 15_000;
/** 连续几次没看到 running 才停服务（避免「刚跑完又发一条」时反复启停） */
const IDLE_POLLS_BEFORE_STOP = 4;

type RuntimesPayload = { sessionId?: string; status?: string }[] | { runtimes?: { sessionId?: string; status?: string }[] };

function pickRuntimes(payload: unknown): { sessionId: string; status: string }[] {
	const list = Array.isArray(payload)
		? payload
		: Array.isArray((payload as { runtimes?: unknown })?.runtimes)
			? ((payload as { runtimes: unknown[] }).runtimes as unknown[])
			: [];
	const result: { sessionId: string; status: string }[] = [];
	for (const item of list) {
		if (!item || typeof item !== "object") continue;
		const record = item as { sessionId?: unknown; status?: unknown };
		if (typeof record.sessionId !== "string" || typeof record.status !== "string") continue;
		result.push({ sessionId: record.sessionId, status: record.status });
	}
	return result;
}

async function fetchRuntimes(device: DeviceRecord): Promise<{ sessionId: string; status: string }[]> {
	const headers: Record<string, string> = {};
	if (device.token) headers.authorization = `Bearer ${device.token}`;
	const response = await fetch(`${device.baseUrl}/api/sessions/runtimes`, { headers });
	if (!response.ok) throw new Error(`runtimes ${response.status}`);
	return pickRuntimes((await response.json()) as RuntimesPayload);
}

/** 通知正文要会话标题；只在「开始看护」那一刻拉一次 state，别每轮都拉。 */
async function fetchTitles(device: DeviceRecord): Promise<Record<string, string>> {
	try {
		const headers: Record<string, string> = {};
		if (device.token) headers.authorization = `Bearer ${device.token}`;
		const response = await fetch(`${device.baseUrl}/api/state`, { headers });
		if (!response.ok) return {};
		const state = (await response.json()) as { sessions?: { id?: unknown; title?: unknown }[] };
		const titles: Record<string, string> = {};
		for (const session of state.sessions ?? []) {
			if (typeof session.id === "string" && typeof session.title === "string") titles[session.id] = session.title;
		}
		return titles;
	} catch {
		return {};
	}
}

export interface RunWatchHandle {
	stop(): void;
	/** 立即检查一次（回到前台时调用） */
	poke(): void;
}

export function startRunWatch(device: DeviceRecord): RunWatchHandle {
	if (!Capacitor.isNativePlatform() || !device.token) {
		// 浏览器里没有前台服务，令牌缺失时原生侧也会拒绝启动
		return { stop: () => undefined, poke: () => undefined };
	}

	let timer: ReturnType<typeof setInterval> | undefined;
	let watching = false;
	let idlePolls = 0;
	let titles: Record<string, string> = {};
	let stopped = false;

	async function ensureStarted(): Promise<void> {
		if (watching) return;
		titles = await fetchTitles(device);
		try {
			await RunWatch.start({ baseUrl: device.baseUrl, token: device.token, titles });
			watching = true;
		} catch {
			// 权限/系统拒绝（例如后台启动限制）：下一轮再试，不影响前台使用
		}
	}

	async function tick(): Promise<void> {
		if (stopped) return;
		let runtimes: { sessionId: string; status: string }[] = [];
		try {
			runtimes = await fetchRuntimes(device);
		} catch {
			return; // 网络抖动：保持现状，别把正在跑的看护停掉
		}
		const running = runtimes.some((runtime) => RUNNING_STATUSES.has(runtime.status));
		if (running) {
			idlePolls = 0;
			await ensureStarted();
			return;
		}
		if (!watching) return;
		idlePolls += 1;
		if (idlePolls < IDLE_POLLS_BEFORE_STOP) return;
		idlePolls = 0;
		watching = false;
		await RunWatch.stop().catch(() => undefined);
	}

	timer = setInterval(() => void tick(), POLL_MS);
	void tick();

	return {
		stop(): void {
			stopped = true;
			if (timer) clearInterval(timer);
			timer = undefined;
			if (watching) {
				watching = false;
				void RunWatch.stop().catch(() => undefined);
			}
		},
		poke(): void {
			void tick();
		},
	};
}

export { pickRuntimes, RUNNING_STATUSES };
