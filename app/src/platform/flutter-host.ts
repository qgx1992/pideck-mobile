/**
 * 打开 Flutter 页（Capacitor 原生插件 `FlutterHost` 的薄封装）。
 *
 * 对应原生：app/android/app/src/main/java/dev/pideck/mobile/FlutterLauncherPlugin.java
 * 承载页： {@link FlutterHostActivity}
 * Dart 端：flutter_module/lib/main.dart（channel `pideck/flutter_host`）
 *
 * 为什么是「另开一个 Activity」而不是把 Flutter 塞进 WebView：
 * Flutter 走自己的渲染引擎，只能作为原生页面存在。两个 Activity 各管各的栈，
 * 返回键行为由系统保证，不需要手写导航同步。
 *
 * 非 Android / 老包（没有这个插件）时静默失败，调用方可以先 isFlutterHostAvailable() 判断。
 */

import { Capacitor, registerPlugin } from "@capacitor/core";

export interface FlutterHostBridge {
  open(options: { route: string; sessionId?: string }): Promise<{ opened: boolean; route: string }>;
  available(): Promise<{ available: boolean }>;
  addListener(
    eventName: "pageVisibility",
    listener: (data: { open: boolean }) => void,
  ): Promise<{ remove: () => Promise<void> }>;
}

const bridge = Capacitor.isNativePlatform() ? registerPlugin<FlutterHostBridge>("FlutterHost") : null;

/** 当前包是否带 Flutter 模块（会真实调一次原生；失败即视为没有）。 */
export async function isFlutterHostAvailable(): Promise<boolean> {
  if (!bridge || Capacitor.getPlatform() !== "android") return false;
  try {
    const result = await bridge.available();
    return result.available === true;
  } catch {
    return false;
  }
}

/**
 * 打开 Flutter 页。
 * @param route     路由名，Dart 侧按它决定渲染哪个页面
 * @param sessionId 可选，从聊天页跳过来时带上，Dart 侧可直接定位会话
 */
export async function openFlutterPage(route: string, sessionId?: string): Promise<boolean> {
  if (!bridge || Capacitor.getPlatform() !== "android") return false;
  try {
    const result = await bridge.open({ route, sessionId });
    return result.opened === true;
  } catch (error) {
    console.warn("[flutter-host] 打开失败", error);
    return false;
  }
}

/**
 * 订阅「Flutter 页开/关」。返回 detach。
 *
 * 为什么需要：Flutter 页一 onStart，WebView 就被 pause，
 * `document.visibilityState` 变 hidden。但 App 明明还在前台（用户就在看 Flutter 页），
 * 不能据此判后台——否则会在用户看着屏幕时弹完成通知。
 * 原生侧（FlutterHostActivity.onStart/onStop）会把真实情况报过来。
 */
export async function onFlutterPageVisibility(
  handler: (open: boolean) => void,
): Promise<() => void> {
  if (!bridge || Capacitor.getPlatform() !== "android") return () => undefined;
  try {
    const handle = await bridge.addListener("pageVisibility", (data) => {
      handler(data?.open === true);
    });
    return () => {
      void handle.remove();
    };
  } catch (error) {
    console.warn("[flutter-host] 订阅页面可见性失败", error);
    return () => undefined;
  }
}
