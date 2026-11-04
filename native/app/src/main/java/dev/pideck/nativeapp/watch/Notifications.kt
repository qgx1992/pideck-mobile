package dev.pideck.nativeapp.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.pideck.nativeapp.MainActivity
import dev.pideck.nativeapp.R

/** 通知渠道与两类通知的构造（看护常驻 / 回复完成）。 */
object Notifications {

    const val CHANNEL_ACTIVE = "run-watch-active"
    const val CHANNEL_DONE = "run-watch-done"
    const val ACTIVE_ID = 0x7E0

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVE, "正在等待回复", NotificationManager.IMPORTANCE_LOW).apply {
                description = "有会话在跑的时候常驻，告诉你还在等"
                setShowBadge(false)
                enableVibration(false)
            },
        )
        @Suppress("DEPRECATION")
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "回复完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "后台时，某个会话的回复跑完了"
            },
        )
    }

    /** 点通知回到 App。 */
    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 前台服务的常驻通知（不可划掉，IMPORTANCE_LOW 不出声）。 */
    fun active(context: Context, count: Int): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (count > 1) "$count 个会话在等回复" else "正在等回复")
            .setContentText("跑完了会通知你")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
            .build()

    /** 回复完成通知。同一会话复用同一个 id，避免堆一屏。 */
    fun finished(context: Context, sessionId: String, title: String, failed: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val builder = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (failed) "回复中断了" else "回复完成")
            .setContentText(title.ifBlank { "会话" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(title.ifBlank { "会话" }))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context))
        runCatching { manager.notify(sessionId.hashCode(), builder.build()) }
    }
}
