package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 「编辑并重发」用到的 pi fork 协议。
 *
 * 宿主那侧走的是 `runtime/get-fork-messages` + `runtime/fork {entryId, mutationFork}`：
 * `get_fork_messages` 的 RPC 回包是 `{messages: [{entryId, text}]}`，Web 路由再包一层
 * `{result: …}`。手机上只有消息正文（`meta` 里没有 entryId），所以按正文反查 ——
 * **从后往前找第一条相同正文**：同一句话说过两次时，用户在气泡上长按的通常是最新那条。
 */
object ForkEdit {

    /** 一条可以 fork 的入口（pi 的 `get_fork_messages` 一项）。 */
    data class ForkPoint(val entryId: String, val text: String)

    /**
     * 解析 `runtime/get-fork-messages` 的回包。
     *
     * 认三种形状（宿主版本不同包法不同）：`{result:[…]}`、`{result:{messages:[…]}}`、
     * 直接 `{messages:[…]}`。解析不出就给空列表 —— 调用方按「拿不到入口」提示。
     */
    fun points(raw: String): List<ForkPoint> {
        val root = runCatching { PideckJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val payload = root["result"] ?: root
        val array: JsonArray = when (payload) {
            is JsonArray -> payload
            is JsonObject -> (payload["messages"] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["entryId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val text = (obj["text"] as? JsonPrimitive)?.content ?: ""
            id?.let { ForkPoint(it, text) }
        }
    }

    /**
     * 按正文反查 fork 入口（从后往前第一条完全相同的）。
     *
     * 不做模糊匹配：pi 的 fork 是「从这个 entry 之前截断」，找错一条就会截到别的地方。
     */
    fun entryIdFor(raw: String, text: String): String? {
        val wanted = text.trim()
        if (wanted.isEmpty()) return null
        return points(raw).lastOrNull { it.text.trim() == wanted }?.entryId
    }

    /** 宿主回包里的 `result` 若自带提示，就把它原样带出来给用户看。 */
    fun failureText(raw: String): String? {
        val root = runCatching { PideckJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return null
        val result = root["result"] as? JsonObject ?: return null
        val error = (result["error"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        return error
    }

    /** 测试与调试用：把 `{messages:[…]}` 的条数读出来。 */
    internal fun count(raw: String): Int = runCatching {
        PideckJson.parseToJsonElement(raw).jsonObject["result"]!!.jsonArray.size
    }.getOrDefault(0)
}
