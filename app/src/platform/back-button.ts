/**
 * Android 物理返回 / 侧滑返回。
 *
 * 为什么需要它：Capacitor 的 Activity 默认不拦返回键，按下就直接 finish() 回桌面——
 * 于是抽屉、面板还开着时用侧滑返回，会整个 App 退到桌面，而不是先收起最上层界面。
 *
 * 做法是装 @capacitor/app 的 backButton 事件：
 *   它用 OnBackPressedCallback 占住返回键，只要 JS 侧注册了监听就不再 finish，
 *   而是把事件交给我们（AppPlugin.java 里 hasListeners("backButton") 为真时不走默认分支）。
 */

import { App } from "@capacitor/app";

/** 当前打开的层，按「从上到下」的顺序判断该收谁。 */
export interface BackLayers {
  /** 底部面板 / 弹窗（选模型、跳转历史、确认框…） */
  sheetOpen: boolean;
  /** 右侧「工作区」抽屉 */
  workspaceOpen: boolean;
  /** 左侧项目/会话抽屉 */
  sidebarOpen: boolean;
}

export type BackIntent = "close-sheet" | "close-workspace" | "close-sidebar" | "leave";

/**
 * 决定这次返回该做什么：只关最上面那一层，一次只退一步。
 * 什么都没开才算「要离开」。
 */
export function resolveBackIntent(layers: BackLayers): BackIntent {
  if (layers.sheetOpen) return "close-sheet";
  if (layers.workspaceOpen) return "close-workspace";
  if (layers.sidebarOpen) return "close-sidebar";
  return "leave";
}

export interface BackHandlerOptions {
  /** 读出当前各层是否打开 */
  readLayers: () => BackLayers;
  /** 关掉最上层界面 */
  onIntent: (intent: Exclude<BackIntent, "leave">) => void;
  /** 已经在最外层又按了返回 */
  onLeave: () => void;
}

/** 连续两次返回之间的最大间隔：第一次只提示，这个时间内再按才真正退出。 */
export const LEAVE_CONFIRM_MS = 2_000;

/**
 * 两次返回的决策（纯函数，方便测）。
 *
 * 第一次：只提示「再按一次返回退出」，**不退出也不缩到后台**——
 * 因为缩到后台后提示就看不见了，用户会以为返回键没反应。
 * 窗口内再按一次：真正退出。
 */
export function resolveLeaveAction(
  lastLeaveAt: number,
  now: number,
  windowMs = LEAVE_CONFIRM_MS,
): "confirm" | "exit" {
  if (lastLeaveAt > 0 && now - lastLeaveAt <= windowMs) return "exit";
  return "confirm";
}

/**
 * 注册返回键处理。返回取消函数；非原生平台（浏览器里跑）什么都不做。
 */
export async function attachBackButton(options: BackHandlerOptions): Promise<() => void> {
  let lastLeaveAt = 0;

  const handle = (): void => {
    const intent = resolveBackIntent(options.readLayers());
    if (intent !== "leave") {
      options.onIntent(intent);
      return;
    }

    const action = resolveLeaveAction(lastLeaveAt, Date.now());
    if (action === "exit") {
      void App.exitApp();
      return;
    }
    lastLeaveAt = Date.now();
    options.onLeave();
  };

  try {
    const listener = await App.addListener("backButton", handle);
    return () => {
      void listener.remove();
    };
  } catch {
    // 浏览器 / 旧版插件：没有返回键可拦
    return () => undefined;
  }
}
