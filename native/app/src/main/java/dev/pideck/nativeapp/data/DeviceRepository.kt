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
class DeviceRepository(private val context: Context) {

    private val devicesKey = stringPreferencesKey("devices")

    private val tokens by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "pideck_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val devices: Flow<List<Device>> = context.deviceStore.data.map { prefs ->
        val raw = prefs[devicesKey] ?: return@map emptyList()
        runCatching {
            PideckJson.decodeFromString(ListSerializer(Device.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun tokenOf(deviceId: String): String? = tokens.getString(deviceId, null)

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
        if (!token.isNullOrBlank()) tokens.edit().putString(record.id, token).apply()
        record
    }

    suspend fun remove(deviceId: String) = withContext(Dispatchers.IO) {
        writeDevices(readDevices().filterNot { it.id == deviceId })
        tokens.edit().remove(deviceId).apply()
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
