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
