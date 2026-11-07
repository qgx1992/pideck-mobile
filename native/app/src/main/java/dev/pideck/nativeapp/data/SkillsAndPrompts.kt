package dev.pideck.nativeapp.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 宿主上的技能 / 扩展（`GET /api/skills`、`GET /api/extensions`）。
 *
 * 桌面端把这两个放在「设置 → PiDeck 插件」里，Web 版是「技能与扩展」对话框。
 * 手机端以前直接放弃（注释写着功能的判断是在只有 dsh 私有接口的时候下的），
 * 现在全局接口是通的，而「某个技能把回答搞坏了，想先在手机上关掉」是最常见的诉求。
 *
 * 都是读接口 + 一个 `toggle`；**新建 / 删除技能、安装扩展只有 IPC**，搬不了。
 */
data class SkillEntry(
    val id: String,
    val name: String,
    val description: String,
    val sourceId: String,
    val sourceLabel: String,
    /** `directory` / `markdown`，只用来显示来源形态。 */
    val type: String,
    val userOnly: Boolean,
    val enabled: Boolean,
    val valid: Boolean,
    val warnings: List<String>,
)

data class ExtensionEntry(
    val id: String,
    val source: String,
    /** `user` / `project` / `builtin`。 */
    val scope: String,
    val builtIn: Boolean,
    val enabled: Boolean,
    /** 被更高优先级的版本盖掉（同名扩展同时存在时会这样）。 */
    val filtered: Boolean,
    val currentVersion: String?,
)

data class SkillsPayload(
    val locations: List<SkillLocation>,
    val skills: List<SkillEntry>,
) {
    data class SkillLocation(val id: String, val label: String)
}

data class ExtensionsPayload(
    val extensions: List<ExtensionEntry>,
    val conflicts: List<String>,
)

/** 解析与纯逻辑都放这里，单测直接打这几个函数。 */
object SkillsParse {

    fun skills(text: String): SkillsPayload {
        val root = runCatching { PideckJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return SkillsPayload(emptyList(), emptyList())
        val locations = (root["locations"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            SkillsPayload.SkillLocation(id = id, label = obj.str("label") ?: id)
        }
        val skills = (root["skills"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            SkillEntry(
                id = obj.str("id") ?: name,
                name = name,
                description = obj.str("description").orEmpty(),
                sourceId = obj.str("sourceId").orEmpty(),
                sourceLabel = obj.str("sourceLabel") ?: obj.str("sourceId").orEmpty(),
                type = obj.str("type").orEmpty(),
                userOnly = obj.bool("userOnly"),
                enabled = obj.bool("enabled"),
                valid = obj.bool("valid") != false,
                warnings = (obj["warnings"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            )
        }
        return SkillsPayload(locations = locations, skills = skills)
    }

    fun extensions(text: String): ExtensionsPayload {
        val root = runCatching { PideckJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return ExtensionsPayload(emptyList(), emptyList())
        val extensions = (root["extensions"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val source = obj.str("source") ?: return@mapNotNull null
            ExtensionEntry(
                id = obj.str("id") ?: source,
                source = source,
                scope = obj.str("scope") ?: "user",
                builtIn = obj.bool("builtIn"),
                enabled = obj.bool("enabled"),
                filtered = obj.bool("filtered"),
                currentVersion = obj.str("currentVersion"),
            )
        }
        val conflicts = (root["conflicts"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return ExtensionsPayload(extensions = extensions, conflicts = conflicts)
    }

    /**
     * 排序：能用的在前 → 已启用在前 → 名字。
     *
     * 与桌面端列表的口径一致（失效的沉底），但**不隐藏**失效项 —— 用户要能看到
     * 「这个技能为什么没生效」，直接把它藏起来等于让人无从下手。
     */
    fun sortedSkills(skills: List<SkillEntry>): List<SkillEntry> = skills.sortedWith(
        compareByDescending<SkillEntry> { it.valid }
            .thenByDescending { it.enabled }
            .thenBy { it.name.lowercase() },
    )

    fun sortedExtensions(list: List<ExtensionEntry>): List<ExtensionEntry> = list.sortedWith(
        compareByDescending<ExtensionEntry> { it.enabled }
            .thenByDescending { it.builtIn.not() }
            .thenBy { it.source.lowercase() },
    )

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.jsonPrimitive.contentOrNull == "true") } ?: false
}

/**
 * 提示词库条目（`GET /api/prompts?search=&category=&page=&pageSize=`）。
 *
 * 列表接口只给摘要，「插入」要再按 slug 取一次正文（`/api/prompts/:slug`）——
 * 桌面端 Web 也是这样两步。**新建 / 删除提示词是 IPC**，搬不了。
 */
data class PromptSummary(
    val slug: String,
    val title: String,
    val description: String,
    val category: String?,
)

data class PromptsPage(
    val categories: List<String>,
    val prompts: List<PromptSummary>,
    val page: Int,
    val pageSize: Int,
) {
    val hasMore: Boolean get() = prompts.size >= pageSize && pageSize > 0
}

object PromptsParse {

    fun page(text: String): PromptsPage {
        val root = runCatching { PideckJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return PromptsPage(emptyList(), emptyList(), 1, 20)
        val categories = (root["categories"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
        }
        val prompts = (root["prompts"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val slug = (obj["slug"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            PromptSummary(
                slug = slug,
                title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: slug,
                description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                category = (obj["category"] as? JsonPrimitive)?.contentOrNull,
            )
        }
        val page = (root["page"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
        val pageSize = (root["pageSize"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 20
        return PromptsPage(categories = categories, prompts = prompts, page = page, pageSize = pageSize)
    }

    /** `{"detail":{"title","description","promptContent"}}` → 正文；取不到给 null。 */
    fun content(text: String): String? {
        val root = runCatching { PideckJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return null
        val detail = root["detail"] as? JsonObject ?: return null
        return (detail["promptContent"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
