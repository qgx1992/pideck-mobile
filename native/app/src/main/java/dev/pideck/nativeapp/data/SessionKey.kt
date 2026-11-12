package dev.pideck.nativeapp.data

/**
 * 一台设备上的一个会话。
 *
 * 手机可以同时配几台电脑，而**会话 id 只在它自己的宿主里有意义** —— 两台电脑上的
 * `63de8961-…` 毫不相干。以前有几处只拿 `sessionId` 当键（草稿箱、统计缓存），
 * 单设备时看不出问题，加了第二台之后就是「A 电脑的草稿出现在 B 电脑的输入框里」。
 *
 * 用它当 map 的键：`toString()` 是 `deviceId|sessionId`，写进 SharedPreferences 时直接可用。
 */
data class SessionKey(val deviceId: String, val sessionId: String) {
    override fun toString(): String = "$deviceId|$sessionId"
}
