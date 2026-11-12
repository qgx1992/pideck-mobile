package dev.pideck.nativeapp.data

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * 本机提示词模板。
 *
 * 为什么要有这一份：桌面端「提示词」面板里的模板存在 pi 的 prompts 目录（一堆 .md 文件），
 * 走的是纯 IPC（`prompts:list` / `prompts:get`），**Web 侧没有路由**；`GET /api/prompts`
 * 是另一个库（桌面端「提示词库」那套带分类的云端模板，落在本机 sqlite 的 `xueprompts` 表里）。
 * 所以那台机器上要是没导入过云端提示词，`/api/prompts` 就是空的 —— 手机端只能空着。
 *
 * 于是这里给一个本机的模板抽屉：长文本（几 KB 那种）、带标题、可预览可删除，
 * 存在手机上，跟快捷消息分开存（快捷消息是一条 200 字以内的口令，模板是多段说明）。
 */
object LocalPrompts {

    const val MAX_COUNT = 50
    const val MAX_TITLE_LENGTH = 80
    const val MAX_BODY_LENGTH = 20_000

    private const val PREFS = "pideck_local_prompts"
    private const val KEY = "items"
    private const val RECORD = '\u001e'
    private const val FIELD = '\u001f'

    data class Prompt(val id: String, val title: String, val body: String)

    private var context: Context? = null
    private var cached: List<Prompt>? = null

    fun attach(context: Context) {
        this.context = context.applicationContext
        if (cached == null) cached = read()
    }

    fun load(): List<Prompt> = cached ?: read().also { cached = it }

    /** 新建：标题为空时用正文第一行顶上（模板常常只有一段话，别逼人想标题）。 */
    fun add(title: String, body: String): List<Prompt> {
        val text = body.trim().take(MAX_BODY_LENGTH)
        if (text.isEmpty()) return load()
        val name = title.trim().take(MAX_TITLE_LENGTH).ifEmpty {
            text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_TITLE_LENGTH) ?: "未命名模板"
        }
        val id = "local-" + System.currentTimeMillis().toString(36) + "-" + load().size
        val items = (load() + Prompt(id = id, title = name, body = text)).takeLast(MAX_COUNT)
        persist(items)
        return items
    }

    fun remove(id: String): List<Prompt> {
        val items = load().filterNot { it.id == id }
        persist(items)
        return items
    }

    private fun persist(items: List<Prompt>) {
        cached = items
        val prefs = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        runCatching { prefs.edit().putString(KEY, encode(items)).apply() }
    }

    /**
     * JSON 数组存（`[[id,title,body], ...]`）。
     *
     * 以前用「`\u001e` 分记录、`\u001f` 分字段、正文里的换行折成字面 `\n`」：编码不可逆 ——
     * 模板里本来就常出现字面 `\n`（JSON / 正则 / C 转义示例），存一次读回来就断成两行。
     */
    internal fun encode(items: List<Prompt>): String = buildJsonArray {
        items.forEach { prompt ->
            addJsonArray {
                add(prompt.id)
                add(prompt.title)
                add(prompt.body)
            }
        }
    }.toString()

    internal fun decode(raw: String): List<Prompt> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val parsed = runCatching { PideckJson.parseToJsonElement(trimmed) as JsonArray }.getOrNull()
            if (parsed != null) {
                return parsed.mapNotNull { element ->
                    val parts = (element as? JsonArray)?.map {
                        (it as? JsonPrimitive)?.contentOrNull
                    } ?: return@mapNotNull null
                    if (parts.size < 3) return@mapNotNull null
                    val body = parts[2]?.trim().orEmpty()
                    if (body.isEmpty()) return@mapNotNull null
                    Prompt(
                        id = parts[0].orEmpty().trim(),
                        title = parts[1].orEmpty().trim(),
                        body = body,
                    )
                }
            }
        }
        // 老格式（升级前存的）只读一次，下次 persist 就换成 JSON。
        return legacyDecode(raw)
    }

    private fun legacyDecode(raw: String): List<Prompt> = raw.split(RECORD).mapNotNull { record ->
        val parts = record.split(FIELD)
        if (parts.size < 3) return@mapNotNull null
        val body = parts[2].replace("\\n", "\n").trim()
        if (body.isEmpty()) return@mapNotNull null
        Prompt(id = parts[0].trim(), title = parts[1].trim(), body = body)
    }

    private fun read(): List<Prompt> {
        val prefs = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return emptyList()
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull() ?: return emptyList()
        return decode(raw).take(MAX_COUNT)
    }

    /** 单测用：不落盘，直接给定一份。 */
    internal fun resetForTest(context: Context?) {
        this.context = context
        cached = null
    }
}
