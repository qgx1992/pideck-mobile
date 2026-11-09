package dev.pideck.nativeapp.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

@Serializable
data class Device(
    val id: String,
    val name: String,
    /** 形如 `http://10.10.0.172:8765`，不含路径与查询串 */
    val baseUrl: String,
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
)

private val Context.deviceStore by preferencesDataStore("pideck_devices")

/**
 * 设备清单 + 令牌。
 *
 * - 清单（名字/地址）走 DataStore，是普通偏好，不需要加密；
 * - 令牌单独走 EncryptedSharedPreferences（Keystore 里的 AES256-GCM 主密钥），
 *   绝不写进 DataStore 的明文 XML —— 这是相对现有 WebView 壳（localStorage 明文）的改进点。
 */
class DeviceRepository(private val context: Context) : DeviceDirectory {

    private val devicesKey = stringPreferencesKey("devices")

    private companion object {
        const val TOKEN_STORE = "pideck_tokens"

        /** AndroidX Security 默认的主密钥别名；重建令牌库时要连它一起删。 */
        const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }

    /**
     * 令牌库（EncryptedSharedPreferences，主密钥在 Android Keystore 里）。
     *
     * 这块最容易「一次坏就一直坏」：换签名重装、系统恢复备份、部分 ROM 清理 keystore 之后，
     * 主密钥没了/换了，旧密文解出来就是 `AEADBadTagException`。而 AndroidX 的实现是**打开时**
     * 就建好、之后每次读写都过 GCM 校验 —— 不清理那两条东西（prefs 文件 + 密钥别名），
     * 连「重新配对」的写入都会失败，表现就是「一直连不上，怎么重配都不行」。
     *
     * 所以读/写失败时都走 [resetStore] 重建一次，再失败才认命（[tokenReadFailed] = true，
     * 启动页据此把用户送到设备页让他重配）。
     */
    @Volatile
    private var tokensRef: android.content.SharedPreferences? = null

    /** 令牌区不可用（且重建过也没救回来）。启动页要据此落到设备页，而不是装没事进首页。 */
    @Volatile
    var tokenReadFailed: Boolean = false
        private set

    private fun buildTokens(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            TOKEN_STORE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun openTokens(): android.content.SharedPreferences =
        tokensRef ?: synchronized(this) {
            tokensRef ?: buildTokens().also { tokensRef = it }
        }

    /** 主密钥与密文一起清掉再建一份空的：旧密文已经解不开了，留着只会挡住写入。 */
    private fun resetStore() {
        tokensRef = null
        runCatching { context.deleteSharedPreferences(TOKEN_STORE) }
        runCatching {
            val keystore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keystore.load(null)
            keystore.deleteEntry(MASTER_KEY_ALIAS)
        }
        tokenReadFailed = true
    }

    /**
     * 读令牌。失败时重建一次令牌库；仍然失败就返回 null 并置 [tokenReadFailed]。
     *
     * 以前这里直接抛 —— 调用点全在 `viewModelScope.launch` 里，一次异常就把进程带走，
     * 表现正是「冷启动闪退」。
     */
    override fun tokenOf(deviceId: String): String? {
        repeat(2) { attempt ->
            try {
                return openTokens().getString(deviceId, null)
            } catch (e: Throwable) {
                if (attempt == 0) resetStore() else logTokenFailure(e)
            }
        }
        return null
    }

    /** 写令牌。同样自带一次重建重试 —— 否则修好之前「重新配对」也存不进去。 */
    private fun writeToken(deviceId: String, token: String) {
        repeat(2) { attempt ->
            try {
                // 用 commit 而不是 apply：密文写失败要让调用方看见（apply 把它丢进后台队列就无声了）
                if (openTokens().edit().putString(deviceId, token).commit()) {
                    tokenReadFailed = false
                    return
                }
            } catch (e: Throwable) {
                if (attempt == 0) resetStore() else logTokenFailure(e)
                return@repeat
            }
            if (attempt == 0) resetStore()
        }
    }

    private fun removeToken(deviceId: String) {
        repeat(2) { attempt ->
            try {
                if (openTokens().edit().remove(deviceId).commit()) return
            } catch (e: Throwable) {
                if (attempt == 0) resetStore() else logTokenFailure(e)
                return@repeat
            }
            if (attempt == 0) resetStore()
        }
    }

    private fun logTokenFailure(e: Throwable) {
        android.util.Log.w("PiDeckTokens", "令牌库不可用：${e::class.java.simpleName}: ${e.message}")
    }

    override val devices: Flow<List<Device>> = context.deviceStore.data.map { prefs ->
        val raw = prefs[devicesKey] ?: return@map emptyList()
        runCatching {
            PideckJson.decodeFromString(ListSerializer(Device.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    /** 配对：同 baseUrl 视为同一台设备，重复添加只更新名字与令牌。 */
    suspend fun upsert(device: Device, token: String?) = withContext(Dispatchers.IO) {
        val current = readDevices()
        val existing = current.firstOrNull { it.baseUrl == device.baseUrl }
        val record = (existing?.copy(name = device.name.ifBlank { existing.name }) ?: device)
            .copy(
                id = existing?.id ?: device.id.ifBlank { UUID.randomUUID().toString() },
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
        val next = current.filterNot { it.id == record.id }.filterNot { it.baseUrl == record.baseUrl } + record
        writeDevices(next)
        if (!token.isNullOrBlank()) writeToken(record.id, token)
        record
    }

    suspend fun remove(deviceId: String) = withContext(Dispatchers.IO) {
        writeDevices(readDevices().filterNot { it.id == deviceId })
        removeToken(deviceId)
    }

    /** 改名：只动清单里的名字；空名字/只空白的直接忽略，别把显示名弄成空行。 */
    suspend fun rename(deviceId: String, name: String) = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext
        writeDevices(readDevices().map { if (it.id == deviceId) it.copy(name = trimmed) else it })
    }

    suspend fun touch(deviceId: String) = withContext(Dispatchers.IO) {
        val next = readDevices().map {
            if (it.id == deviceId) it.copy(lastUsedAt = System.currentTimeMillis()) else it
        }
        writeDevices(next)
    }

    private suspend fun readDevices(): List<Device> {
        var result: List<Device> = emptyList()
        context.deviceStore.edit { prefs ->
            val raw = prefs[devicesKey]
            if (raw != null) {
                result = runCatching {
                    PideckJson.decodeFromString(ListSerializer(Device.serializer()), raw)
                }.getOrDefault(emptyList())
            }
        }
        return result
    }

    private suspend fun writeDevices(list: List<Device>) {
        val encoded = PideckJson.encodeToString(ListSerializer(Device.serializer()), list)
        context.deviceStore.edit { it[devicesKey] = encoded }
    }
}
