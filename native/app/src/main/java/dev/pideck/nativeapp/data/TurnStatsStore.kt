package dev.pideck.nativeapp.data

import android.content.Context

/**
 * 每个会话「最近结算过的那一轮」的节奏数据，跨会话切换、跨 App 重启都保持同一份。
 *
 * 为什么需要它：统计行收尾时是**现算**的（宿主 perf 或本端秒表 + meta）。切走再切回来时
 * 秒表已经清了、宿主 perf 也可能过期，只能退回 meta 里那条**单步**的 `durationMs`，
 * 于是同一轮的数字会变（tok 变多、时间变短、速度翻倍），acp 那种 meta 为 null 的干脆整行变破折号。
 *
 * 所以结算一次就记一次，之后认的是这份记录 —— 与桌面端「本地结算缓存，会话切换/轮询时保持可用」同思路。
 * 只存数字，不存文本；按「会话 + 那条 assistant 消息」索引，上限 [LIMIT] 条。
 */
object TurnStatsStore {

    private const val PREFS = "pideck_turn_stats"
    private const val KEY = "entries"
    private const val LIMIT = 120

    private var context: Context? = null

    /** key = `sessionId|messageId`；只放内存里，attach 时从磁盘补一次。 */
    private val memory = LinkedHashMap<String, TurnStats>()

    fun attach(context: Context) {
        this.context = context.applicationContext
        if (memory.isNotEmpty()) return
        val raw = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
        }.getOrNull().orEmpty()
        for (line in raw) {
            val (key, stats) = decode(line) ?: continue
            memory[key] = stats
        }
    }

    fun remember(sessionId: String, messageId: String, stats: TurnStats) {
        if (stats.isEmpty) return
        val key = keyOf(sessionId, messageId)
        memory[key] = stats
        trim()
        persist()
    }

    fun recall(sessionId: String, messageId: String): TurnStats? = memory[keyOf(sessionId, messageId)]

    /** 测试用：清空内存表（不动磁盘，避免把用户的历史记录洗掉）。 */
    fun forgetAll() = memory.clear()

    private fun keyOf(sessionId: String, messageId: String) = "$sessionId|$messageId"

    private fun trim() {
        while (memory.size > LIMIT) {
            val oldest = memory.keys.firstOrNull() ?: break
            memory.remove(oldest)
        }
    }

    private fun persist() {
        val ctx = context ?: return
        val lines = memory.entries.mapNotNull { (key, stats) -> encode(key, stats) }.toSet()
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, lines).apply()
        }
    }

    private fun encode(key: String, stats: TurnStats): String? {
        if (stats.isEmpty) return null
        fun num(value: Number?): String = value?.toString() ?: ""
        return listOf(
            key,
            num(stats.ttftMs),
            num(stats.totalMs),
            num(stats.outputTokens),
            num(stats.tps),
            num(stats.endToEndTps),
        ).joinToString("\t")
    }

    private fun decode(line: String): Pair<String, TurnStats>? {
        val parts = line.split('\t')
        if (parts.size != 6) return null
        fun long(i: Int): Long? = parts[i].toLongOrNull()
        fun int(i: Int): Int? = parts[i].toIntOrNull()
        fun double(i: Int): Double? = parts[i].toDoubleOrNull()
        val stats = TurnStats(
            ttftMs = long(1),
            totalMs = long(2),
            outputTokens = int(3),
            tps = double(4),
            endToEndTps = double(5),
        )
        return if (stats.isEmpty) null else parts[0] to stats
    }
}
