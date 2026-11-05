package dev.pideck.nativeapp.ui.chat

/**
 * 每个会话各自保留一份没发出去的草稿。
 *
 * 为什么需要：聊天页是按会话重新创建的（换会话走 `popUpTo(chat) inclusive`），
 * 输入框的状态跟着页面一起没了 —— 写了半段话切去看别的会话，回来就是空的。
 * 这里按 sessionId 单独存，页面重建时把内容取回来。
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
    fun get(sessionId: String): String = drafts[sessionId].orEmpty()

    @Synchronized
    fun put(sessionId: String, text: String) {
        if (text.isEmpty()) {
            drafts.remove(sessionId)
        } else {
            drafts[sessionId] = text
        }
    }

    /** 发出去之后清掉，别让下次进来又看到已经发过的话。 */
    @Synchronized
    fun clear(sessionId: String) {
        drafts.remove(sessionId)
    }
}
