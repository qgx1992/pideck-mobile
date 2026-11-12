package dev.pideck.nativeapp.ui.chat

import dev.pideck.nativeapp.data.SessionKey

/**
 * 每个会话各自保留一份没发出去的草稿。
 *
 * 为什么需要：聊天页是按会话重新创建的（换会话走 `popUpTo(chat) inclusive`），
 * 输入框的状态跟着页面一起没了 —— 写了半段话切去看别的会话，回来就是空的。
 * 这里按 [SessionKey]（设备 + 会话）单独存，页面重建时把内容取回来 —— 只按 sessionId 存的话，
 * 换一台电脑配对后，同名的 uuid 会串台（会话 id 只在它自己的宿主里有意义）。
 *
 * 只在内存里（进程活着期间有效）：草稿是一次性的临时输入，落盘会让「上次没写完的
 * 半句话」在重启后突然冒出来，反而更意外。
 */
object ChatDrafts {

    /** 最多同时记这么多条，超出就丢最久没碰的那条，别让长会话列表把内存撑住。 */
    private const val MAX_ENTRIES = 40

    private val drafts = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(deviceId: String, sessionId: String): String =
        drafts[keyOf(deviceId, sessionId)].orEmpty()

    @Synchronized
    fun put(deviceId: String, sessionId: String, text: String) {
        val key = keyOf(deviceId, sessionId)
        if (text.isEmpty()) {
            drafts.remove(key)
        } else {
            drafts[key] = text
        }
    }

    /** 发出去之后清掉，别让下次进来又看到已经发过的话。 */
    @Synchronized
    fun clear(deviceId: String, sessionId: String) {
        drafts.remove(keyOf(deviceId, sessionId))
    }

    private fun keyOf(deviceId: String, sessionId: String): String =
        SessionKey(deviceId, sessionId).toString()

    /** 单测用：进程级单例，用例之间不清就会互相污染。 */
    @Synchronized
    internal fun resetForTest() {
        drafts.clear()
    }
}
