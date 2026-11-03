/**
 * 后台看护的 JS 侧：什么时候需要原生服务。
 *
 * 原生 `RunWatchService` 自己订阅 `/api/events`、自己判断「跑完了 / 有人提问」，
 * 并在 App 不可见时发通知——它需要的是**在会话开始跑的那一刻就被启动**
 * （Android 12+ 禁止从后台启动前台服务，等切走了再启动会被拒）。
 *
 * 所以这里只做一件事：轮询**每台配对设备**的运行态，把「当前有会话在跑」的设备清单
 * 交给原生（原生给每台各开一条 SSE），长时间没人在跑就 stop。
 * 轮询用 `/api/sessions/runtimes`（约 3KB）而不是 `/api/state`（200KB+）。
 *
 * 多设备的意义：切到 B 设备之后，A 上跑完的照样要提醒 —— 清单里必须包含所有在跑的
 * 设备，而不是只有一个「当前设备」。
 */

import { Capacitor, registerPlugin } from "@capacitor/core";

import type { DeviceRecord } from "./device.ts";

/** 交给原生的一台设备：一条 SSE + 标题表（通知正文用）。 */
export interface WatchTarget {
	deviceId: string;
	baseUrl: string;
	token: string;
	titles: Record<string, string>;
}

interface RunWatchApi {
	start(options: { targets: WatchTarget[]; page: ShellPage }): Promise<void>;
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

/**
 * 前台服务是哪个页面拉起来的。
 *
 * 原生据此决定「App 在前台时还要不要弹通知」：聊天页静音（用户正盯着对话），
 * 启动页照弹（那页没有任何会话视图，跑完了不提醒就真的错过）。
 */
export type ShellPage = "chat" | "launcher";

export interface RunWatchOptions {
	/** 缺省按聊天页算 */
	page?: ShellPage;
}

export interface RunWatchHandle {
	stop(): void;
	/** 立即检查一次（回到前台时调用） */
	poke(): void;
}

/** 设备清单签名：只有变化的设备集合才重新下发给原生，避免每次轮询都重连。 */
function targetSignature(devices: DeviceRecord[]): string {
	return devices
		.map((device) => device.id)
		.sort()
		.join(",");
}

export function startRunWatch(devices: DeviceRecord[], options: RunWatchOptions = {}): RunWatchHandle {
	const page: ShellPage = options.page ?? "chat";
	const usable = devices.filter((device) => device.token);
	if (!Capacitor.isNativePlatform() || usable.length === 0) {
		// 浏览器里没有前台服务；一台带令牌的设备都没有时也无需看护
		return { stop: () => undefined, poke: () => undefined };
	}

	let timer: ReturnType<typeof setInterval> | undefined;
	let watching = false;
	let idlePolls = 0;
	let watchingSignature = "";
	let stopped = false;
	/** 同一轮 tick 不叠跑（poke 可能在网络慢时接连触发） */
	let ticking = false;

	/** 给「有会话在跑」的设备各抓一次标题表，拼接成原生要的 targets。 */
	async function buildTargets(running: DeviceRecord[]): Promise<WatchTarget[]> {
		return Promise.all(
			running.map(async (device) => ({
				deviceId: device.id,
				baseUrl: device.baseUrl,
				token: device.token,
				titles: await fetchTitles(device),
			})),
		);
	}

	async function tick(): Promise<void> {
		if (stopped || ticking) return;
		ticking = true;
		try {
			// 每台设备各查一次：网络抖动只影响那一台，别把别的设备的看护也停掉
			const results = await Promise.all(
				usable.map(async (device) => {
					try {
						const runtimes = await fetchRuntimes(device);
						return { device, running: runtimes.some((runtime) => RUNNING_STATUSES.has(runtime.status)) };
					} catch {
						return { device, running: null };
					}
				}),
			);

			const running = results.filter((item) => item.running === true).map((item) => item.device);
			if (running.length > 0) {
				idlePolls = 0;
				const signature = targetSignature(running);
				if (watching && signature === watchingSignature) return;
				const targets = await buildTargets(running);
				try {
					await RunWatch.start({ targets, page });
					watching = true;
					watchingSignature = signature;
				} catch {
					// 权限/系统拒绝（例如后台启动限制）：下一轮再试，不影响前台使用
				}
				return;
			}

			// 全都查不到运行态时保持现状：可能只是网络抖了，别急着停
			if (results.some((item) => item.running === null)) return;
			if (!watching) return;
			idlePolls += 1;
			if (idlePolls < IDLE_POLLS_BEFORE_STOP) return;
			idlePolls = 0;
			watching = false;
			watchingSignature = "";
			await RunWatch.stop().catch(() => undefined);
		} finally {
			ticking = false;
		}
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
				watchingSignature = "";
				void RunWatch.stop().catch(() => undefined);
			}
		},
		poke(): void {
			void tick();
		},
	};
}

export { pickRuntimes, RUNNING_STATUSES, targetSignature };
