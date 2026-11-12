package dev.pideck.nativeapp.ui.common

import dev.pideck.nativeapp.data.attempt
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.net.Uri

/**
 * 用系统浏览器 / 应用商店打开一个网址。
 *
 * 包一层只为两件事：`Intent` 的 try/catch（没有浏览器时会抛 `ActivityNotFoundException`），
 * 以及全项目只有这一处拼 `ACTION_VIEW`，以后要换实现只改这里。
 */
/** 跳到系统的「应用通知设置」页（通知被关掉时给一条能自己修的路）。 */
fun openNotificationSettings(context: Context): Boolean = attempt {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)

fun openUrl(context: Context, url: String): Boolean = attempt {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)
