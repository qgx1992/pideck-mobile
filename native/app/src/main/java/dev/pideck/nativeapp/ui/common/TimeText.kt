package dev.pideck.nativeapp.ui.common

import java.util.concurrent.TimeUnit

/**
 * 会话「多久以前」的唯一实现。
 *
 * 之前有两份手抄：`ui/sessions/SessionsScreen.kt` 超过 7 天给「93 天前」，
 * `ui/chat/SessionDrawer.kt` 超过 7 天给「08-08」—— 同一个会话在列表页和抽屉里显示不同的时间，
 * 用户报过「两边不一样」。现在全走这里，口径与桌面端 `formatRelativeTime` 一致：
 * 刚刚 / N 分钟前 / N 小时前 / N 天前 / N 个月前（30 天以上按 30 天一段）。
 *
 * 自己算，**不用** `SimpleDateFormat` / `Date` 的本地化输出：CI 的 runner 是 en-US，
 * 本地是 zh-CN，格式化结果会在两边分叉（踩过：消息时间成了 `3:04:`）。
 */
internal fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return ""
    val diff = now - epochMillis
    if (diff < 0L) return "刚刚"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        diff < TimeUnit.HOURS.toMillis(1) -> "$minutes 分钟前"
        diff < TimeUnit.DAYS.toMillis(1) -> "$hours 小时前"
        diff < TimeUnit.DAYS.toMillis(30) -> "$days 天前"
        else -> "${days / 30} 个月前"
    }
}
