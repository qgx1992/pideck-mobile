package dev.pideck.nativeapp.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pideck.nativeapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 「检查更新」的界面状态。设置页与首页提示都读它。 */
sealed interface UpdateState {

    /** 还没查过（自动检查关着、或 App 刚起来）。 */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** 远端确实更新。 */
    data class Available(val release: RemoteRelease) : UpdateState

    /** 已经是最新（记下查到的是哪一版，方便确认真的查过）。 */
    data class UpToDate(val versionName: String) : UpdateState

    /** 没查成：没网 / 超时 / 被挡 / 回包不像样。 */
    data class Failed(val message: String) : UpdateState
}

/**
 * 更新检查的唯一入口。
 *
 * - 状态放成 Compose 的 `mutableStateOf`：设置页与首页提示都直接读，不用各存一份。
 * - 同一时刻只放一次请求（`inFlight`）：设置页点两下、启动时自动查一次，不会打三条出去。
 * - 查到新版之后不重复查（除非用户手点「检查更新」`force = true`）。
 */
object UpdateCenter {

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** 当前装的版本（`BuildConfig` 是编译期的，运行时变不了）。 */
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    @Volatile
    private var inFlight = false

    /** 有新版时给的那条 Release（没查过 / 已最新 / 失败都是 null）。 */
    val available: RemoteRelease? get() = (state as? UpdateState.Available)?.release

    suspend fun check(force: Boolean = false): UpdateState {
        val current = state
        if (!force && (current is UpdateState.Available || current is UpdateState.UpToDate)) return current
        if (inFlight) return state
        inFlight = true
        state = UpdateState.Checking
        return try {
            withContext(Dispatchers.IO) {
                val raw = UpdateCheck.fetchLatestBlocking().getOrElse { error ->
                    state = UpdateState.Failed(describe(error))
                    return@withContext state
                }
                val release = UpdateCheck.parseLatestRelease(raw)
                state = if (release == null) {
                    UpdateState.Failed("GitHub 的回包里没有可用的版本号")
                } else if (UpdateCheck.isNewer(release.versionName, currentVersion)) {
                    UpdateState.Available(release)
                } else {
                    UpdateState.UpToDate(release.versionName)
                }
                state
            }
        } finally {
            inFlight = false
        }
    }

    /** 给设置页显示的状态文案。 */
    fun describeState(value: UpdateState = state): String = when (value) {
        UpdateState.Idle -> "还没查过"
        UpdateState.Checking -> "正在检查…"
        is UpdateState.Available -> "有新版 ${value.release.versionName}（你现在 $currentVersion）"
        is UpdateState.UpToDate -> "已是最新（${currentVersion}）"
        is UpdateState.Failed -> "没查成：${value.message}"
    }

    internal fun resetForTest() {
        state = UpdateState.Idle
        inFlight = false
    }

    private fun describe(error: Throwable): String = when (error) {
        is UnknownHostException -> "连不上 GitHub（手机没网，或者被网络环境挡了）"
        is SocketTimeoutException -> "GitHub 没响应（超时）"
        is IOException -> error.message ?: "网络出错了"
        else -> error.message ?: error::class.simpleName ?: "未知错误"
    }
}
