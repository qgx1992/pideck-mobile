package dev.pideck.nativeapp.data

/**
 * 新会话的「Agent 后端」，对齐桌面端 composer 里那个后端选择器。
 *
 * 宿主 `POST /api/sessions` 的 body 只认两种值（`createSessionDraft`）：
 *
 * ```js
 * const backend = input.backend === "dsh" ? "dsh" : input.backend === "acp" ? "acp" : void 0;
 * ```
 *
 * 也就是说 **pi 是「不写」**，写别的值（`imagegen` / `videogen` / 拼错的）一律退回 pi ——
 * 实测创建出来的记录 `backend` 为空。所以手机端只提供这三类：pi / DSH / 某个 ACP 工具。
 *
 * ACP 还必须带 `acpToolId`（`/api/state` 的 `acpTools[i].id`），而且宿主对 acp 会话
 * **不落 `model` / `thinkingLevel`**（`createSessionDraft` 里 `acp ? void 0 : …`），
 * 所以聊天页对这两类后端要收掉模型与思考胶囊，别让人对着一个不生效的选择器点。
 */
sealed class AgentBackend {
    /** 写进请求体的 `backend` 字段；null = 不写（pi）。 */
    abstract val wire: String?
    abstract val label: String
    abstract val hint: String

    /** 桌面端不给后端时就是 pi：默认后端，模型、思考档位、安全等级都在它这边管。 */
    object Pi : AgentBackend() {
        override val wire: String? = null
        override val label: String = "Pi"
        override val hint: String = "默认后端：模型、思考档位与安全等级都在这边"
    }

    /** DSH 运行时（它自带模型与插件体系，权限走 permissionPreset 而不是安全等级）。 */
    object Dsh : AgentBackend() {
        override val wire: String? = "dsh"
        override val label: String = "DSH"
        override val hint: String = "走桌面端的 DSH 运行时，模型与插件由它自己管"
    }

    /** 某个 ACP 工具接管的会话（CodeBuddy 之类）：权限由 agent CLI 自己谈，模型不可选。 */
    class Acp(val toolId: String, override val label: String) : AgentBackend() {
        override val wire: String? = "acp"
        override val hint: String = "由 ACP 工具接管：模型与权限都在工具里"
    }

    companion object {
        /** pi 一定在，DSH 一定在（桌面端能藏，但手机端拿不到 `hiddenModules`），然后才是 ACP 工具。 */
        fun options(tools: List<AcpTool>): List<AgentBackend> = buildList {
            add(Pi)
            add(Dsh)
            tools.forEach { tool ->
                if (tool.id.isNotBlank()) add(Acp(tool.id, tool.name.ifBlank { tool.id }))
            }
        }

        /** 本地记的是 `""`（pi）/ `"dsh"` / `"acp:<toolId>"`；认不出来就退回 pi，别把会话建坏。 */
        fun parse(stored: String?, tools: List<AcpTool>): AgentBackend {
            val raw = stored?.trim().orEmpty()
            if (raw == "dsh") return Dsh
            if (raw.startsWith("acp:")) {
                val toolId = raw.removePrefix("acp:")
                val name = tools.firstOrNull { it.id == toolId }?.name?.ifBlank { toolId }
                if (name != null) return Acp(toolId, name)
            }
            return Pi
        }

        fun idOf(backend: AgentBackend): String = when (backend) {
            Pi -> ""
            Dsh -> "dsh"
            is Acp -> "acp:${backend.toolId}"
        }
    }
}
