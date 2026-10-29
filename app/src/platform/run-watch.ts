/**
 * 后台任务看护（原生前台服务的 JS 侧）。
 *
 * 为什么需要：App 切到后台会被系统冻结，WebView 里 SSE 回调和定时器全部停摆，
 * 「跑完了」在 JS 侧根本观察不到 —— 这就是「通知时灵时不灵」的根因。
 * 原生侧用前台服务（不会被冻结）轮询 /api/sessions/runtimes 并自己发通知。
 *
 * 三条约定：
 *   1. 必须在「还有会话在跑」的时候启动服务（Android 12+ 禁止后台启动前台服务），
 *      所以由这里在状态更新里顺手拉起/停掉
 *   2. App 可见时服务只跟踪状态、不发通知（可见时由 JS 自己发）
 *   3. 用户点通知进来时，原生会把 sessionId 挂一次，这里取走后用于跳转
 */

import { Capacitor, registerPlugin } from "@capacitor/core";

interface RunWatchNative {
  start(options: { baseUrl: string; token: string; titles: Record<string, string> }): Promise<void>;
  stop(): Promise<void>;
  setVisible(options: { visible: boolean }): Promise<void>;
  consumeNotified(): Promise<{ sessionIds: string[] }>;
  consumeLaunchSession(): Promise<{ sessionId: string }>;
}

const native = Capacitor.isNativePlatform()
  ? registerPlugin<RunWatchNative>("RunWatch")
  : null;

/** 服务当前是否已在跑（避免每帧都发 start）。 */
let watching = false;

export function isRunWatchActive(): boolean {
  return watching;
}

export interface RunWatchStart {
  baseUrl: string;
  token: string;
  /** sessionId → 会话标题，通知正文用 */
  titles: Record<string, string>;
}

/** 启动后台看护。重复调用是安全的（原生侧会复用线程）。 */
export async function startRunWatch(options: RunWatchStart): Promise<boolean> {
  if (!native) return false;
  if (!options.baseUrl || !options.token) return false;
  try {
    await native.start(options);
    watching = true;
    return true;
  } catch (error) {
    console.warn("[run-watch] 启动失败", error);
    return false;
  }
}

/** 停止后台看护（没有会话在跑时调用）。 */
export async function stopRunWatch(): Promise<void> {
  if (!native || !watching) return;
  watching = false;
  try {
    await native.stop();
  } catch (error) {
    console.warn("[run-watch] 停止失败", error);
  }
}

/** 告诉原生 App 是否可见（不可见时才由它发通知）。 */
export async function setRunWatchVisible(visible: boolean): Promise<void> {
  if (!native || !watching) return;
  try {
    await native.setVisible({ visible });
  } catch {
    /* 忽略 */
  }
}

/** 取出「原生已经通知过」的会话 id，避免回到前台后重复弹。 */
export async function consumeNotifiedSessions(): Promise<Set<string>> {
  if (!native) return new Set();
  try {
    const result = await native.consumeNotified();
    return new Set(result.sessionIds ?? []);
  } catch {
    return new Set();
  }
}

/** 取出「点通知进来」要跳转的会话 id（一次性）。 */
export async function consumeLaunchSession(): Promise<string> {
  if (!native) return "";
  try {
    const result = await native.consumeLaunchSession();
    return typeof result.sessionId === "string" ? result.sessionId : "";
  } catch {
    return "";
  }
}

/**
 * 是否该启动看护：只要有一个会话处于 running。
 * 单独抽出来是为了能脱离 DOM/插件单测。
 */
export function shouldStartRunWatch(
  runtimes: readonly { sessionId: string; status: string }[],
): boolean {
  return runtimes.some((item) => item.status === "running");
}

/**
 * 收集「正在跑的会话 → 标题」，供原生通知正文使用。
 * 找不到标题就不放进去（原生那边会退化成通用文案）。
 */
export function collectRunWatchTitles(
  runtimes: readonly { sessionId: string; status: string }[],
  lookup: (sessionId: string) => { title?: string } | undefined,
): Record<string, string> {
  const titles: Record<string, string> = {};
  for (const item of runtimes) {
    if (item.status !== "running") continue;
    const title = lookup(item.sessionId)?.title?.trim();
    if (title) titles[item.sessionId] = title;
  }
  return titles;
}
