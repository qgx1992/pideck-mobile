package dev.pideck.nativeapp.data

import android.content.Context
import android.util.Log
import dev.pideck.nativeapp.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常落地。
 *
 * 「冷启动直接闪退」这类问题，现场一没就只剩用户的一句描述 —— 栈拿不到，
 * 只能靠猜。这里把最后一次崩溃的栈写进 `filesDir/last-crash.txt`（设置页「诊断」里能看能复制），
 * 装完之后仍然交给系统默认处理器：该崩还是崩，但下次有据可查。
 *
 * 不做「自动上报」：这是局域网里的私有客户端，不往外发任何东西。
 */
object CrashLog {

    private const val NAME = "last-crash.txt"
    private const val TAG = "PiDeckCrash"

    fun file(context: Context): File = File(context.filesDir, NAME)

    /** 在 `Application.onCreate` 里装一次（越早越好）。 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            attempt { write(app, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** 读上一次的崩溃记录；没有就是 null。 */
    fun read(context: Context): String? = attempt {
        file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        attempt { file(context).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val head = buildString {
            append("时间 ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("线程 ").append(thread.name).append('\n')
            append("App ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("系统 Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" / API ").append(android.os.Build.VERSION.SDK_INT).append('\n')
            append("机型 ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append("\n\n")
        }
        attempt { file(context).writeText(head + stack) }
        Log.e(TAG, "uncaught: ${error::class.java.name}: ${error.message}")
    }
}
