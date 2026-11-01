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
  /** 推一份最新的设置数据（JSON 字符串）；原生会缓存并在 Flutter 首帧同步返回。 */
  pushSettings(options: { json: string }): Promise<void>;
  /** 推一份最新的聊天页快照（JSON 字符串），同样会被原生缓存。 */
  pushChat(options: { json: string }): Promise<void>;
  /** 让 Flutter 页弹一句提示（WebView 的 toast 在 Flutter 页上不可见）。 */
  pushNotice(options: { text: string }): Promise<void>;
  /** 主动关掉 Flutter 页（handler 里要切到别的界面时）。 */
  closePage(): Promise<void>;
  addListener(
    eventName: "pageVisibility",
    listener: (data: { open: boolean }) => void,
  ): Promise<{ remove: () => Promise<void> }>;
  addListener(
    eventName: "settingsAction",
    listener: (data: { json: string }) => void,
  ): Promise<{ remove: () => Promise<void> }>;
  addListener(
    eventName: "chatAction",
    listener: (data: { json: string }) => void,
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

/**
 * 把设置页数据推给 Flutter。
 *
 * 设置页走的是「视图迁移」：状态源仍在 WebView（`settingsData()` 组装），
 * Flutter 只负责画。所以每次数据变就推一份，Flutter 侧整页重绘。
 * 打开 Flutter 页**之前**也要先推一次 —— 原生会缓存，首帧直接取走，不会白屏。
 */
export async function pushFlutterSettings(json: string): Promise<void> {
  if (!bridge || Capacitor.getPlatform() !== "android") return;
  try {
    await bridge.pushSettings({ json });
  } catch (error) {
    console.warn("[flutter-host] 推送设置数据失败", error);
  }
}

/** 让 Flutter 页弹一句提示。非 Android / 老包时调用方应回退到 toast。 */
export async function pushFlutterNotice(text: string): Promise<void> {
  if (!bridge || Capacitor.getPlatform() !== "android") return;
  try {
    await bridge.pushNotice({ text });
  } catch (error) {
    console.warn("[flutter-host] 推送提示失败", error);
  }
}

/** 关掉当前 Flutter 页（JS 侧主动关，比如要跳到配对页）。 */
export async function closeFlutterPage(): Promise<void> {
  if (!bridge || Capacitor.getPlatform() !== "android") return;
  try {
    await bridge.closePage();
  } catch (error) {
    console.warn("[flutter-host] 关闭 Flutter 页失败", error);
  }
}

/**
 * 订阅 Flutter 页回传的动作（用户点了开关 / 切了设备 / 点了刷新）。
 *
 * 动作只是一份 JSON，具体执行交给与 Web 版共用的那套 handler，
 * 这样两个界面的行为不会分叉。返回 detach。
 */
export async function onFlutterSettingsAction(
  handler: (action: Record<string, unknown>) => void,
): Promise<() => void> {
  if (!bridge || Capacitor.getPlatform() !== "android") return () => undefined;
  try {
    const handle = await bridge.addListener("settingsAction", (data) => {
      const raw = typeof data?.json === "string" ? data.json : "";
      if (!raw) return;
      try {
        const parsed: unknown = JSON.parse(raw);
        if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
          handler(parsed as Record<string, unknown>);
        }
      } catch (error) {
        console.warn("[flutter-host] 动作解析失败", error);
      }
    });
    return () => {
      void handle.remove();
    };
  } catch (error) {
    console.warn("[flutter-host] 订阅设置动作失败", error);
    return () => undefined;
  }
}

/**
 * 把聊天页快照推给 Flutter（`ChatSnapshot` 的 JSON）。
 *
 * 与设置页同理：状态源在 WebView（`ChatReducer` 是协议核心，桌面端共用），
 * Flutter 只画。频率由调用方的 `FlutterChatMirror` 压到 40ms 一次。
 */
export async function pushFlutterChat(json: string): Promise<void> {
  if (!bridge || Capacitor.getPlatform() !== "android") return;
  try {
    await bridge.pushChat({ json });
  } catch (error) {
    console.warn("[flutter-host] 推送聊天数据失败", error);
  }
}

/**
 * 订阅 Flutter 聊天页回传的动作（发消息 / 停止 / 回答问题 / 长按菜单）。
 *
 * 与 `onFlutterSettingsAction` 同一套做法：动作只是一份 JSON，执行交给
 * `app/src/main.ts` 里与 Web 版共用的那套函数。返回 detach。
 */
export async function onFlutterChatAction(
  handler: (action: Record<string, unknown>) => void,
): Promise<() => void> {
  if (!bridge || Capacitor.getPlatform() !== "android") return () => undefined;
  try {
    const handle = await bridge.addListener("chatAction", (data) => {
      const raw = typeof data?.json === "string" ? data.json : "";
      if (!raw) return;
      try {
        const parsed: unknown = JSON.parse(raw);
        if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
          handler(parsed as Record<string, unknown>);
        }
      } catch (error) {
        console.warn("[flutter-host] 聊天动作解析失败", error);
      }
    });
    return () => {
      void handle.remove();
    };
  } catch (error) {
    console.warn("[flutter-host] 订阅聊天动作失败", error);
    return () => undefined;
  }
}
