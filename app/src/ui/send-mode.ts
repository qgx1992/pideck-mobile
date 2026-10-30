/**
 * 运行中发消息的两种行为（对齐桌面端 ComposerArea / WebComposer）。
 *
 *   steer     插话：立刻插进当前轮，改变它接下来的动作（「别查了，直接改文件」）
 *   followUp  排队：等这一轮结束后作为新的一轮发出（最安全，默认）
 *
 * 空闲时两者都等于普通发送，所以只在 running 时才把 streamingBehavior 传上去，
 * 避免给服务端塞无意义的字段。
 */

import type { StreamingBehavior } from "@core/api/client.ts";

export type SendMode = "queue" | "steer";

export const SEND_MODE_ORDER: readonly SendMode[] = ["queue", "steer"];

export function sendModeLabel(mode: SendMode): string {
  return mode === "steer" ? "插话" : "排队";
}

/** 主按钮/提示用的完整文案。 */
export function sendModeActionLabel(mode: SendMode): string {
  return mode === "steer" ? "插话发送" : "排队发送";
}

export function sendModeHint(mode: SendMode): string {
  return mode === "steer"
    ? "插话：立刻打断当前轮，把这句话插进去（适合纠正方向）"
    : "排队：等当前轮结束后再发（默认，适合追加要求）";
}

export function toggleSendMode(mode: SendMode): SendMode {
  return mode === "queue" ? "steer" : "queue";
}

/**
 * 决定这次 prompt 要不要带 streamingBehavior。
 * 空闲时不带：让服务端按普通发送处理，也免得旧版本 PiDeck 收到未知字段。
 */
export function streamingBehaviorFor(running: boolean, mode: SendMode): StreamingBehavior | undefined {
  if (!running) return undefined;
  return mode === "steer" ? "steer" : "followUp";
}

/**
 * 「运行中发送」按钮的显隐。
 *
 * 两个条件同时满足才显示：这一轮正在跑（否则主按钮就是「发送」），
 * 且输入框里有东西可发（文字或图片）。以前只看 running，没打字也会挂一个按钮出来。
 */
export function shouldShowRunningSend(running: boolean, hasContent: boolean): boolean {
  return running && hasContent;
}

/** 长按切换模式后的提示文案（手机上只能靠这个告诉用户切换生效了）。 */
export function sendModeSwitchToast(mode: SendMode): string {
  return `已切换为「${sendModeLabel(mode)}」：${sendModeHint(mode)}`;
}
