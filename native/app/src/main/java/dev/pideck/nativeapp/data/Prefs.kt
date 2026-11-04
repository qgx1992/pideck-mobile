package dev.pideck.nativeapp.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.prefsStore by preferencesDataStore("pideck_prefs")

/**
 * 全局偏好：主题、跑完要不要通知。
 *
 * 这两个值被非 Compose 的地方读（主题在 Activity 最外层、通知开关在前台服务里），
 * 所以除了 DataStore 之外再挂一份内存镜像（[theme]/[notifyDone]），免得服务里去 await 磁盘。
 * 写的时候先落盘再改镜像 —— 反过来的话应用被杀就丢设置。
 */
object Prefs {

    private val KEY_THEME = stringPreferencesKey("theme")          // system | light | dark
    private val KEY_NOTIFY_DONE = booleanPreferencesKey("notify_done")
    private val KEY_LAST_DEVICE = stringPreferencesKey("last_device")
    private val KEY_LAST_SESSION = stringPreferencesKey("last_session")

    /** 主题变化用 StateFlow 播出去，MainActivity 直接 collect。 */
    val themeFlow = MutableStateFlow(ThemeMode.SYSTEM)
    val notifyFlow = MutableStateFlow(true)

    @Volatile
    var theme: ThemeMode = ThemeMode.SYSTEM
        private set

    @Volatile
    var notifyDone: Boolean = true
        private set

    /** 上次停在哪个会话 —— 冷启动直接进聊天页要用。 */
    @Volatile
    var lastDeviceId: String? = null
        private set

    @Volatile
    var lastSessionId: String? = null
        private set

    private var loaded = false

    /** 在 Application.onCreate 里调一次；之后的改动都由 [setTheme]/[setNotifyDone] 维护。 */
    fun load(context: Context, scope: CoroutineScope) {
        if (loaded) return
        loaded = true
        scope.launch {
            context.prefsStore.data.collectLatest { data ->
                val mode = ThemeMode.parse(data[KEY_THEME])
                val notify = data[KEY_NOTIFY_DONE] ?: true
                theme = mode
                notifyDone = notify
                themeFlow.value = mode
                notifyFlow.value = notify
                lastDeviceId = data[KEY_LAST_DEVICE]
                lastSessionId = data[KEY_LAST_SESSION]
            }
        }
    }

    /**
     * 记下「现在停在哪个会话」。每进一次会话就写一次 —— 只有几百字节，
     * 换来的是冷启动能直接回到上次看的地方。
     */
    suspend fun rememberSession(context: Context, deviceId: String, sessionId: String) {
        if (lastDeviceId == deviceId && lastSessionId == sessionId) return
        lastDeviceId = deviceId
        lastSessionId = sessionId
        context.prefsStore.edit {
            it[KEY_LAST_DEVICE] = deviceId
            it[KEY_LAST_SESSION] = sessionId
        }
    }

    /** 冷启动时直接问磁盘（Application 里的异步 load 可能还没跑完）。 */
    suspend fun lastSession(context: Context): Pair<String, String>? {
        val data = context.prefsStore.data.first()
        val device = data[KEY_LAST_DEVICE]
        val session = data[KEY_LAST_SESSION]
        return if (device != null && session != null) device to session else null
    }

    suspend fun setTheme(context: Context, mode: ThemeMode) {
        theme = mode
        themeFlow.value = mode
        context.prefsStore.edit { it[KEY_THEME] = mode.id }
    }

    suspend fun setNotifyDone(context: Context, enabled: Boolean) {
        notifyDone = enabled
        notifyFlow.value = enabled
        context.prefsStore.edit { it[KEY_NOTIFY_DONE] = enabled }
    }
}

enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    ;

    companion object {
        fun parse(raw: String?): ThemeMode = entries.firstOrNull { it.id == raw } ?: SYSTEM
    }
}
