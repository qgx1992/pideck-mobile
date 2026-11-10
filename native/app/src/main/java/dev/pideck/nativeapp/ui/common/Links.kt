package dev.pideck.nativeapp.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 用系统浏览器 / 应用商店打开一个网址。
 *
 * 包一层只为两件事：`Intent` 的 try/catch（没有浏览器时会抛 `ActivityNotFoundException`），
 * 以及全项目只有这一处拼 `ACTION_VIEW`，以后要换实现只改这里。
 */
fun openUrl(context: Context, url: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)
