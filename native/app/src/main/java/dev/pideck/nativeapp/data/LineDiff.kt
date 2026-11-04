package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 行级 diff。
 *
 * 为什么自己算：桌面端的 `GET /api/git/diff` 返回的**不是** unified diff，而是整份
 * `{path, originalContent, modifiedContent}`（见 `WebWorkspaceRoutes.gitDiff`），
 * 渲染得由客户端自己比。这里就只做「变成 @@ 分块的行文本」这一件事。
 *
 * 算法：先剪掉公共前后缀（改一个文件末尾几行时这一步就把规模压到几十行），中间段用 LCS；
 * 中间段太大（> 1,000,000 格）时退化成「整段替换」——手机上算得动比算得细更重要。
 */
object LineDiff {

    enum class Kind { Context, Added, Removed, Hunk }

    data class Line(val kind: Kind, val text: String)

    private const val MAX_CELLS = 1_000_000

    fun render(original: String, modified: String, context: Int = 3): String =
        toText(unified(original, modified, context))

    fun toText(lines: List<Line>): String = lines.joinToString("\n") { it.text }

    fun unified(original: String, modified: String, context: Int = 3): List<Line> {
        if (original == modified) return emptyList()
        val a = original.split('\n')
        val b = modified.split('\n')

        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) {
            suffix++
        }

        val head = a.subList(0, prefix)
        val tail = a.subList(a.size - suffix, a.size)
        val middleA = a.subList(prefix, a.size - suffix)
        val middleB = b.subList(prefix, b.size - suffix)

        val ops = mutableListOf<Line>()
        head.forEach { ops += Line(Kind.Context, " $it") }
        ops += middle(middleA, middleB)
        tail.forEach { ops += Line(Kind.Context, " $it") }

        return withHunks(ops, context)
    }

    private fun middle(a: List<String>, b: List<String>): List<Line> {
        if (a.isEmpty()) return b.map { Line(Kind.Added, "+$it") }
        if (b.isEmpty()) return a.map { Line(Kind.Removed, "-$it") }

        val matrix = lcsTable(a, b) ?: return a.map { Line(Kind.Removed, "-$it") } + b.map { Line(Kind.Added, "+$it") }

        // 回溯：从右下角往左上走，收集操作后反转
        val reversed = mutableListOf<Line>()
        var i = a.size
        var j = b.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == b[j - 1] -> {
                    reversed += Line(Kind.Context, " ${a[i - 1]}")
                    i--
                    j--
                }

                j > 0 && (i == 0 || matrix[i][j - 1] >= matrix[i - 1][j]) -> {
                    reversed += Line(Kind.Added, "+${b[j - 1]}")
                    j--
                }

                else -> {
                    reversed += Line(Kind.Removed, "-${a[i - 1]}")
                    i--
                }
            }
        }
        return reversed.asReversed()
    }

    /** `null` 表示超预算，调用方退回「整段替换」。 */
    private fun lcsTable(a: List<String>, b: List<String>): Array<IntArray>? {
        if (a.size.toLong() * b.size > MAX_CELLS) return null
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                table[i][j] = if (a[i] == b[j]) {
                    table[i + 1][j + 1] + 1
                } else {
                    maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
        }
        return table
    }

    /** 把连续改动切成 @@ 块，块间只保留 [context] 行上下文。 */
    private fun withHunks(ops: List<Line>, context: Int): List<Line> {
        if (ops.none { it.kind != Kind.Context }) return emptyList()

        val keep = BooleanArray(ops.size)
        ops.forEachIndexed { index, line ->
            if (line.kind != Kind.Context) {
                val from = maxOf(0, index - context)
                val to = minOf(ops.size - 1, index + context)
                for (k in from..to) keep[k] = true
            }
        }

        val result = mutableListOf<Line>()
        // 记录每行在两侧的起始行号（1 基），用于 @@ 头
        val aNo = IntArray(ops.size)
        val bNo = IntArray(ops.size)
        var ai = 1
        var bi = 1
        ops.forEachIndexed { index, line ->
            aNo[index] = ai
            bNo[index] = bi
            when (line.kind) {
                Kind.Context -> {
                    ai++
                    bi++
                }

                Kind.Removed -> ai++
                Kind.Added -> bi++
                Kind.Hunk -> Unit
            }
        }

        var index = 0
        while (index < ops.size) {
            if (!keep[index]) {
                index++
                continue
            }
            // 一块 keep 连续区间的两端
            var end = index
            while (end + 1 < ops.size && keep[end + 1]) end++

            var aCount = 0
            var bCount = 0
            for (k in index..end) {
                when (ops[k].kind) {
                    Kind.Context -> {
                        aCount++
                        bCount++
                    }

                    Kind.Removed -> aCount++
                    Kind.Added -> bCount++
                    Kind.Hunk -> Unit
                }
            }
            val startA = aNo[index]
            val startB = bNo[index]
            result += Line(Kind.Hunk, "@@ -$startA,$aCount +$startB,$bCount @@")
            for (k in index..end) result += ops[k]
            index = end + 1
        }
        return result
    }

    /** `GET /api/git/diff` 的 `diff` 字段：`{path, originalContent, modifiedContent}`。 */
    data class FileDiffPayload(val path: String, val original: String, val modified: String)

    fun parsePayload(root: JsonElement): FileDiffPayload? {
        val obj = root as? JsonObject ?: return null
        fun text(key: String): String? =
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val path = text("path") ?: return null
        return FileDiffPayload(path, text("originalContent") ?: "", text("modifiedContent") ?: "")
    }
}
