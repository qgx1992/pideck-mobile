package dev.pideck.nativeapp.data

/**
 * 流式正文的「增量修复」：把还没写完的 Markdown 补成能解析的样子。
 *
 * 桌面端流式时也是边来边排版的（`MarkdownBody` 会对未闭合的围栏/强调做修复），
 * 手机端以前是「流式期间只画纯文本，收尾才解析」，所以表格、代码块、加粗都要等一句说完才生效。
 * 这里只补最小的几处，够 [dev.pideck.nativeapp.ui.chat.MarkdownCache] 认出结构即可：
 *
 * 1. 未闭合的 ``` 代码块 → 末尾补一行 ```；
 * 2. 奇数个行内反引号 → 补一个（不然会把后面整段当代码）；
 * 3. 奇数个 `**` → 补一对（半截加粗会把后面的字全吃掉）；
 * 4. 最后一行像表头、下面还没写分隔行 → 补一行 `| --- | --- |`。
 *
 * 修复失败也不能抛：调用方拿它当「尽量好看」的输入，解析器本身对坏输入是容错的。
 */
fun repairStreamingMarkdown(text: String): String {
    if (text.isBlank()) return text
    var out = text.trimEnd('\n')
    if (countOf(out, "```") % 2 == 1) out += "\n```"
    // 围栏里的反引号不算行内代码
    val prose = out.replace(FENCE, "")
    if (countOf(prose, "`") % 2 == 1) out += "`"
    if (countOf(out, "**") % 2 == 1) out += "**"
    return repairTableTail(out)
}

/**
 * 「已经写完的段落」到哪里结束（返回下标，[0] 表示还没有完整段落）。
 *
 * 流式渲染只把这段交给解析器，后面那一段（正在长的那段）按纯文本逐帧画。
 * 之前是整段一起解析、解析完才替换 —— 文字只在每次解析时前进一次，看起来「一大块一大块」地出现。
 *
 * 未闭合的代码块不切：切在围栏里会把半截代码当普通段落排一遍，反而跳。
 */
fun stablePrefixLength(text: String): Int {
    if (text.isEmpty()) return 0
    val openFence = countOf(text, "```") % 2 == 1
    val limit = if (openFence) text.lastIndexOf("```").coerceAtLeast(0) else text.length
    if (limit <= 0) return 0
    val cut = text.lastIndexOf(NL + NL, limit - 1)
    return if (cut < 0) 0 else cut + 2
}

/**
 * 要不要重新解析一次（解析放在后台线程，每帧都做会把主线程挤爆）。
 *
 * @param shown 当前已经露出来的字数
 * @param parsedAt 上次解析时露出来的字数
 * @param sinceLastParseMs 距上次解析的毫秒数
 */
fun shouldReparse(
    shown: Int,
    parsedAt: Int,
    sinceLastParseMs: Long,
    minGrowth: Int = 80,
    maxWaitMs: Long = 400L,
): Boolean {
    if (shown <= parsedAt) return false
    if (parsedAt < 0) return true
    return shown - parsedAt >= minGrowth || sinceLastParseMs >= maxWaitMs
}

private const val NL = "\n"

private val FENCE = Regex("```")

private fun countOf(source: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = source.indexOf(needle)
    while (index >= 0) {
        count += 1
        index = source.indexOf(needle, index + needle.length)
    }
    return count
}

private fun repairTableTail(text: String): String {
    val lines = text.split(NL).toMutableList()
    if (lines.size < 2) return text
    val pipeLines = lines.indices.filter { lines[it].contains('|') }
    // 只处理「表头 + 第一行数据，但缺分隔行」这一种；已经写全的表不动。
    if (pipeLines.size != 2 || isSeparatorRow(lines[pipeLines[1]])) return text
    val columns = lines[pipeLines[0]].count { it == '|' } - 1
    if (columns < 1) return text
    lines.add(pipeLines[0] + 1, "|" + " --- |".repeat(columns))
    return lines.joinToString(NL)
}

private fun isSeparatorRow(line: String): Boolean {
    val cells = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    if (cells.isEmpty()) return false
    return cells.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' } && cell.contains('-') }
}
