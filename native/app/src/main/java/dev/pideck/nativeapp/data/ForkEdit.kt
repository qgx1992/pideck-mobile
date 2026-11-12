package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「编辑并重发」用到的 pi fork 协议。
 *
 * 宿主那侧走的是 `runtime/get-fork-messages` + `runtime/fork {entryId, mutationFork}`。
 * 0.11.x 的实测回包**套了三层**（0.5.54 第一版只认平铺形状，于是永远说「宿主没给 fork 入口」）：
 *
 * ```json
 * {"result":{"ok":true,"value":{"target":{…},"value":[{"entryId":"55aef2ba","text":"不用了，我自己操作"}]}}}
 * ```
 *
 * 所以这里不再猜具体层级，直接递归找「第一个装着 `entryId` 的数组」。
 */
object ForkEdit {

    /** 一条可以 fork 的入口（pi 的 `get_fork_messages` 一项）。 */
    data class ForkPoint(val entryId: String, val text: String)

    private const val MAX_DEPTH = 6

    /** 解析 `runtime/get-fork-messages` 的回包；认不出就是空列表。 */
    fun points(raw: String): List<ForkPoint> {
        val root = attempt { PideckJson.parseToJsonElement(raw) }.getOrNull()
            ?: return emptyList()
        return arrayOfPoints(root, 0)
    }

    private fun arrayOfPoints(node: JsonElement, depth: Int): List<ForkPoint> {
        if (depth > MAX_DEPTH) return emptyList()
        return when (node) {
            is JsonArray -> {
                val parsed = node.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val id = (obj["entryId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    val text = (obj["text"] as? JsonPrimitive)?.content ?: ""
                    id?.let { ForkPoint(it, text) }
                }
                // 数组里没有 entryId 时继续往下找（回包里可能还嵌着别的数组）
                if (parsed.isNotEmpty()) parsed else node.firstOrNull { arrayOfPoints(it, depth + 1).isNotEmpty() }?.let { arrayOfPoints(it, depth + 1) }.orEmpty()
            }

            is JsonObject -> node.values
                .firstOrNull { arrayOfPoints(it, depth + 1).isNotEmpty() }
                ?.let { arrayOfPoints(it, depth + 1) }
                .orEmpty()
            else -> emptyList()
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

    /** 宿主回包里若带 `error`（`{result:{ok:false,error:"…"}}` 之类），原样交给界面。 */
    fun failureText(raw: String): String? {
        val root = attempt { PideckJson.parseToJsonElement(raw) }.getOrNull()
            ?: return null
        return errorOf(root, 0)
    }

    private fun errorOf(node: JsonElement, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        return when (node) {
            is JsonObject -> {
                val direct = (node["error"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                direct ?: node.values.firstNotNullOfOrNull { errorOf(it, depth + 1) }
            }

            is JsonArray -> node.firstNotNullOfOrNull { errorOf(it, depth + 1) }
            else -> null
        }
    }
}
