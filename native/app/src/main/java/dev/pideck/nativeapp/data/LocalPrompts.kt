package dev.pideck.nativeapp.data

import android.content.Context

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

    /** 记录之间 `\u001e`，字段之间 `\u001f`，正文里的换行折成字面 `\n` —— 一行一条最省事。 */
    private fun encode(items: List<Prompt>): String = items.joinToString(RECORD.toString()) { prompt ->
        listOf(
            prompt.id.replace(RECORD, ' ').replace(FIELD, ' '),
            prompt.title.replace(RECORD, ' ').replace(FIELD, ' '),
            prompt.body.replace(RECORD, ' ').replace(FIELD, ' ').replace("\n", "\\n"),
        ).joinToString(FIELD.toString())
    }

    private fun decode(raw: String): List<Prompt> = raw.split(RECORD).mapNotNull { record ->
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
