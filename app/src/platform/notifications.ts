/**
 * 本地通知：任务完成时提醒（App 在前台或后台存活时）。
 *
 * 边界（重要，别对用户过度承诺）：
 *   - Android 会冻结后台 WebView 的 SSE，App 被系统挂起后收不到任何事件，
 *     也就发不出通知。真正的「关掉 App 也能收到」需要推送通道，
 *     而 PiDeck 服务端没有。
 *   - 所以这里只保证：App 仍在运行（前台，或刚切后台还没被冻结）时，
 *     会话从 running 变成 idle/error 会立刻弹一条系统通知。
 *
 * 通知点击后回到对应会话由调用方处理（extra.sessionId）。
 */

import { Capacitor } from "@capacitor/core";
import { LocalNotifications } from "@capacitor/local-notifications";

const ENABLED_KEY = ["pideck", "notify", "task-finish"].join("-");
/** 通知 id 需要是 32 位 int，用递增序号避免重复覆盖 */
let nextId = 1000;

export function isTaskNotifyEnabled(): boolean {
  try {
    const raw = localStorage.getItem(ENABLED_KEY);
    return raw === null ? true : raw === "1";
  } catch {
    return true;
  }
}

export function setTaskNotifyEnabled(enabled: boolean): void {
  try {
    localStorage.setItem(ENABLED_KEY, enabled ? "1" : "0");
  } catch {
    /* 隐私模式忽略 */
  }
}

/** 请求通知权限（Android 13+ 需要；已授过直接返回 true）。 */
export async function ensureNotificationPermission(): Promise<boolean> {
  if (!Capacitor.isNativePlatform()) return false;
  try {
    let status = await LocalNotifications.checkPermissions();
    if (status.display !== "granted") {
      status = await LocalNotifications.requestPermissions();
    }
    return status.display === "granted";
  } catch (error) {
    console.warn("[notify] 权限申请失败", error);
    return false;
  }
}

export interface TaskFinishedNotice {
  sessionId: string;
  title: string;
  body: string;
}

/** 发一条「任务结束」通知。返回是否发出。 */
export async function notifyTaskFinished(notice: TaskFinishedNotice): Promise<boolean> {
  if (!Capacitor.isNativePlatform()) return false;
  if (!isTaskNotifyEnabled()) return false;

  const granted = await ensureNotificationPermission();
  if (!granted) return false;

  try {
    await LocalNotifications.schedule({
      notifications: [
        {
          id: nextId++,
          title: notice.title,
          body: notice.body,
          // 用额外字段带上会话 id，点击后跳转
          extra: { sessionId: notice.sessionId },
          autoCancel: true,
          channelId: "pideck-task",
        },
      ],
    });
    return true;
  } catch (error) {
    console.warn("[notify] 发送失败", error);
    return false;
  }
}

/** 点击通知时回调会话 id（用于跳转）。返回 detach。 */
export async function onNotificationTap(handler: (sessionId: string) => void): Promise<() => void> {
  if (!Capacitor.isNativePlatform()) return () => undefined;
  try {
    const handle = await LocalNotifications.addListener("localNotificationActionPerformed", (action) => {
      const extra = action.notification.extra as { sessionId?: unknown } | undefined;
      const sessionId = typeof extra?.sessionId === "string" ? extra.sessionId : "";
      if (sessionId) handler(sessionId);
    });
    return () => {
      void handle.remove();
    };
  } catch (error) {
    console.warn("[notify] 监听点击失败", error);
    return () => undefined;
  }
}

/** 建通知渠道（Android 8+ 建议显式建，才能在系统设置里单独控制）。 */
export async function ensureTaskChannel(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  try {
    await LocalNotifications.createChannel({
      id: "pideck-task",
      name: "任务完成提醒",
      description: "会话里 Agent 跑完一轮时提醒",
      importance: 3,
      visibility: 0,
    });
  } catch (error) {
    console.warn("[notify] 创建渠道失败", error);
  }
}
