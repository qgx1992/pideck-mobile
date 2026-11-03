/**
 * 运行时权限。
 *
 * Android 13（API 33）起 `POST_NOTIFICATIONS` 是**运行时**权限：清单里声明了也照样默认关闭，
 * 不主动请求的话 `NotificationManager.notify()` 静默丢弃——表现就是「跑完了但一点动静都没有」。
 * `RunWatchService` 是在后台跑的，没有机会弹框，所以必须在启动页/进聊天前就把权限要到。
 */

import { Capacitor } from "@capacitor/core";
import { LocalNotifications } from "@capacitor/local-notifications";

export type NotificationPermission = "granted" | "denied" | "prompt" | "unsupported";

/** 已经问过、但用户拒过（Android 只会弹两次，之后只能去系统设置改）。 */
const DENIED_KEY = "pideck.notifyDenied";

function cacheDecision(state: NotificationPermission): NotificationPermission {
	try {
		if (state === "denied") localStorage.setItem(DENIED_KEY, "1");
		else if (state === "granted") localStorage.removeItem(DENIED_KEY);
	} catch {
		/* 隐私模式 / 存储满：无关紧要 */
	}
	return state;
}

/**
 * 确保通知权限可用。
 *
 * `request === false` 时只查不弹（用于每次进启动页静默刷新状态）；
 * 用户在场的操作（点「开启通知」）才传 true。
 */
export async function ensureNotificationPermission(request = false): Promise<NotificationPermission> {
	if (!Capacitor.isNativePlatform()) return "unsupported";
	try {
		const current = await LocalNotifications.checkPermissions();
		if (current.display === "granted") return cacheDecision("granted");
		if (!request) return cacheDecision(current.display === "denied" ? "denied" : "prompt");
		const asked = await LocalNotifications.requestPermissions();
		return cacheDecision(asked.display === "granted" ? "granted" : "denied");
	} catch {
		// 插件缺失（旧包）或系统接口异常：别让启动页跟着崩
		return "unsupported";
	}
}

/** 上一次是否被拒过（用于把「去系统设置」的提示留在页面上）。 */
export function rememberedNotificationDenial(): boolean {
	try {
		return localStorage.getItem(DENIED_KEY) === "1";
	} catch {
		return false;
	}
}
