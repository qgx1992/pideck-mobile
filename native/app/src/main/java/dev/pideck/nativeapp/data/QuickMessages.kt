package dev.pideck.nativeapp.data

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * 快捷消息（「底栏那颗闪电按钮」的对应物）。
 *
 * 桌面端把它放在 `userData/quick-messages.json`，走的是**纯 IPC**（`quick-messages:get` /
 * `quick-messages:save`），HTTP 侧没有任何路由 —— 所以手机读不到桌面端那份清单。
 * 这里退一步：手机维护自己的一份（本机偏好里），点一下插进输入框、或者直接发出去。
 *
 * 为什么不复用「提示词库」：提示词是带分类、带描述、动辄几屏的模板（`/api/prompts` 在桌面端
 * 是另一个面板）；快捷消息是「一句话，随手点」，两者在桌面端也是两套数据。
 *
 * 上限与桌面端一致（30 条 / 每条 200 字），免得用户从桌面端抄过来时被截断。
 */
object QuickMessages {

    const val MAX_COUNT = 30
    const val MAX_LENGTH = 200

    private const val PREFS = "pideck_quick_messages"
    private const val KEY = "items"

    /** 首次打开时的样子：与桌面端默认清单同思路（问候 / 继续 / 总结 / 自查），可随时删改。 */
    private val SEED = listOf(
        "继续",
        "请用中文简要总结一下上面的内容",
        "把刚才的改动整理成要点",
        "先别动手，说说你的方案",
    )

    private var context: Context? = null
    private var cached: List<String>? = null

    fun attach(context: Context) {
        this.context = context.applicationContext
        if (cached == null) cached = read()
    }

    fun load(): List<String> = cached ?: read().also { cached = it }

    fun add(text: String): List<String> {
        val trimmed = text.trim().take(MAX_LENGTH)
        if (trimmed.isEmpty()) return load()
        val items = (load() + trimmed).distinct().takeLast(MAX_COUNT)
        persist(items)
        return items
    }

    fun remove(text: String): List<String> {
        val items = load().filterNot { it == text }
        persist(items)
        return items
    }

    private fun persist(items: List<String>) {
        cached = items
        val prefs = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        runCatching { prefs.edit().putString(KEY, encode(items)).apply() }
    }

    /**
     * JSON 数组存。
     *
     * 以前「`\u001e` 分隔 + 把换行折成字面 `\n`」是编码不可逆的：快捷消息里写 `printf("a\nb")`
     * 这种内容，存一次再读就变成真换行。
     */
    internal fun encode(items: List<String>): String = buildJsonArray {
        items.forEach { add(it) }
    }.toString()

    internal fun decode(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val parsed = runCatching { PideckJson.parseToJsonElement(trimmed) as JsonArray }.getOrNull()
            if (parsed != null) {
                return parsed.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                    .filter { it.isNotEmpty() }
            }
        }
        // 老格式只读一次。
        return raw.split('\u001e').mapNotNull { line ->
            line.replace("\\n", "\n").trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun read(): List<String> {
        val prefs = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return SEED
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull()
            ?: return SEED
        val items = decode(raw).take(MAX_COUNT)
        return items.ifEmpty { SEED }
    }

    /** 单测用：不落盘，直接给定一份。 */
    internal fun resetForTest(context: Context?) {
        this.context = context
        cached = null
    }
}
