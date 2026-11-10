package dev.pideck.nativeapp.data

/**
 * 宿主版本与客户端的适配基线。
 *
 * `/api/state` 的 `version` 是桌面端 0.10.0 才加进来的常量字段（老宿主没有，解析出来是 null）。
 * 本客户端按 **0.11.0** 的接口面对账过一次（0.11.0–0.11.3 与 0.10.0 的路由 / 字段 / 流帧完全一致，
 * 唯一新增是 `role:"tool"` 消息可以带 `images`），
 * 所以低版本不是不能用，只是「没对账过」—— 设置页把这件事说清楚，别让人对着一个
 * 表现怪异的宿主猜。
 */
object HostCompat {

    /** 本客户端按哪个桌面端版本对过接口。涨这个值要真的重新对账，别当装饰。 */
    const val BASELINE = "0.11.0"

    /** 取版本号前三段数字（`0.10.0-beta.1` → `[0, 10, 0]`），认不出来返回 null。 */
    fun parse(version: String?): List<Int>? {
        val raw = version?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val numbers = Regex("""\d+""").findAll(raw).take(3).map { it.value.toIntOrNull() ?: 0 }.toList()
        return if (numbers.isEmpty()) null else numbers
    }

    /** `version` 是否严格低于 [BASELINE]；版本未知（老宿主）返回 false —— 不确定就不吓人。 */
    fun isOlderThanBaseline(version: String?): Boolean {
        val given = parse(version) ?: return false
        val base = parse(BASELINE) ?: return false
        for (index in 0 until maxOf(given.size, base.size)) {
            val left = given.getOrElse(index) { 0 }
            val right = base.getOrElse(index) { 0 }
            if (left != right) return left < right
        }
        return false
    }

    /** 设置页那一行提示；不需要提示时返回 null。 */
    fun hint(version: String?): String? =
        if (isOlderThanBaseline(version)) {
            "低于本客户端适配基线 $BASELINE：接口没对过账，个别功能可能不可用（升级桌面端最省事）。"
        } else {
            null
        }
}
