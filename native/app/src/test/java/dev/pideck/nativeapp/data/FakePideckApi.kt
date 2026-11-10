package dev.pideck.nativeapp.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject

/**
 * 单测用的假实现：每个方法都抛「未打桩」，测试里用子类只覆盖自己关心的那两三个。
 *
 * 为什么要这份基底：`PideckApi` 有 36 个方法，而一个 ViewModel 用例通常只碰 2~3 个；
 * 没有它时每个测试都要手写全接口，最后大家干脆不写 ViewModel 测试了。
 * 接口加方法后这里会编译红（不会静默漏掉）。
 *
 * 生成方式：按接口源码机械展开。override 不允许再写默认值，所以参数里的 `= …` 都去掉了。
 */
open class FakePideckApi : PideckApi {
    override suspend fun state(device: Device, token: String?, etag: String?): StateFetch =
        throw NotImplementedError("FakePideckApi.state 未打桩")

    override suspend fun messagesPage(device: Device, token: String?, sessionId: String, pageSize: Int, before: Long?): SessionMessagePage =
        throw NotImplementedError("FakePideckApi.messagesPage 未打桩")

    override fun sessionStream(device: Device, token: String?, sessionId: String): Flow<SseFrame> =
        throw NotImplementedError("FakePideckApi.sessionStream 未打桩")

    override fun chat(device: Device, token: String?, body: JsonObject): Flow<SseFrame> =
        throw NotImplementedError("FakePideckApi.chat 未打桩")

    override suspend fun createSession(device: Device, token: String?, projectId: String, backend: String?, acpToolId: String?, model: SessionModel?, thinkingLevel: String?): String =
        throw NotImplementedError("FakePideckApi.createSession 未打桩")

    override suspend fun sendPrompt(device: Device, token: String?, sessionId: String, body: kotlinx.serialization.json.JsonObject): PromptDelivery =
        throw NotImplementedError("FakePideckApi.sendPrompt 未打桩")

    override suspend fun createAnonymousSession(device: Device, token: String?, projectId: String): String =
        throw NotImplementedError("FakePideckApi.createAnonymousSession 未打桩")

    override suspend fun stopRuntime(device: Device, token: String?, sessionId: String, agentId: String, runtimeGeneration: Long): Unit =
        throw NotImplementedError("FakePideckApi.stopRuntime 未打桩")

    override suspend fun renameSession(device: Device, token: String?, sessionId: String, title: String): Unit =
        throw NotImplementedError("FakePideckApi.renameSession 未打桩")

    /** 推流默认给一条空流：ViewModel 的 init 会挂它，抛异常会冒到测试线程上当失败。 */
    override fun stateEvents(device: Device, token: String?): Flow<String> = emptyFlow()

    override suspend fun createProject(device: Device, token: String?, path: String): Project? =
        throw NotImplementedError("FakePideckApi.createProject 未打桩")

    override suspend fun deleteSession(device: Device, token: String?, sessionId: String): Unit =
        throw NotImplementedError("FakePideckApi.deleteSession 未打桩")

    override suspend fun copySession(device: Device, token: String?, sessionId: String): String? =
        throw NotImplementedError("FakePideckApi.copySession 未打桩")

    override suspend fun removeProject(device: Device, token: String?, projectId: String): Unit =
        throw NotImplementedError("FakePideckApi.removeProject 未打桩")

    override suspend fun rawGet(device: Device, token: String?, path: String): String =
        throw NotImplementedError("FakePideckApi.rawGet 未打桩")

    override suspend fun runtimes(device: Device, token: String?): List<RuntimeInfo> =
        throw NotImplementedError("FakePideckApi.runtimes 未打桩")

    override suspend fun todo(device: Device, token: String?, sessionId: String): WorkspaceTodo? =
        throw NotImplementedError("FakePideckApi.todo 未打桩")

    override suspend fun changes(device: Device, token: String?, projectId: String, projectPath: String?): WorkspaceChanges =
        throw NotImplementedError("FakePideckApi.changes 未打桩")

    override suspend fun files(device: Device, token: String?, projectId: String, path: String): List<FileNode> =
        throw NotImplementedError("FakePideckApi.files 未打桩")

    override suspend fun fileText(device: Device, token: String?, projectId: String, path: String, sessionId: String?): String =
        throw NotImplementedError("FakePideckApi.fileText 未打桩")

    override suspend fun fileDiff(device: Device, token: String?, projectId: String, group: String, path: String): LineDiff.FileDiffPayload? =
        throw NotImplementedError("FakePideckApi.fileDiff 未打桩")

    override suspend fun models(device: Device, token: String?, force: Boolean): List<AvailableModel> =
        throw NotImplementedError("FakePideckApi.models 未打桩")

    override suspend fun updateSession(device: Device, token: String?, sessionId: String, patch: JsonObject): Unit =
        throw NotImplementedError("FakePideckApi.updateSession 未打桩")

    override suspend fun setSessionSecurityLevel(device: Device, token: String?, sessionId: String, levelId: String?): SecurityState =
        throw NotImplementedError("FakePideckApi.setSessionSecurityLevel 未打桩")

    override suspend fun respondUi(device: Device, token: String?, body: JsonObject): Unit =
        throw NotImplementedError("FakePideckApi.respondUi 未打桩")

    override suspend fun subagents(device: Device, token: String?, sessionId: String): List<SubagentEntry> =
        throw NotImplementedError("FakePideckApi.subagents 未打桩")

    override suspend fun runtimeState(device: Device, token: String?, target: RuntimeTarget): RuntimeSnapshot =
        throw NotImplementedError("FakePideckApi.runtimeState 未打桩")

    override suspend fun runtimeCommand(device: Device, token: String?, target: RuntimeTarget, action: String, extra: JsonObject): String =
        throw NotImplementedError("FakePideckApi.runtimeCommand 未打桩")

    override suspend fun exportHtml(device: Device, token: String?, sessionId: String): String =
        throw NotImplementedError("FakePideckApi.exportHtml 未打桩")

    override suspend fun skills(device: Device, token: String?): SkillsPayload =
        throw NotImplementedError("FakePideckApi.skills 未打桩")

    override suspend fun toggleSkill(device: Device, token: String?, name: String, sourceId: String, enabled: Boolean): Unit =
        throw NotImplementedError("FakePideckApi.toggleSkill 未打桩")

    override suspend fun extensions(device: Device, token: String?): ExtensionsPayload =
        throw NotImplementedError("FakePideckApi.extensions 未打桩")

    override suspend fun toggleExtension(device: Device, token: String?, source: String, scope: String, enabled: Boolean): Unit =
        throw NotImplementedError("FakePideckApi.toggleExtension 未打桩")

    override suspend fun prompts(device: Device, token: String?, search: String, category: String?, page: Int, pageSize: Int): PromptsPage =
        throw NotImplementedError("FakePideckApi.prompts 未打桩")

    override suspend fun promptContent(device: Device, token: String?, slug: String, category: String?): String? =
        throw NotImplementedError("FakePideckApi.promptContent 未打桩")

    override suspend fun gitLog(device: Device, token: String?, projectId: String, limit: Int): List<GitCommit> =
        throw NotImplementedError("FakePideckApi.gitLog 未打桩")

}
