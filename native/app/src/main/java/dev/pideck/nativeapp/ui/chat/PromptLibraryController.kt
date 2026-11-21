package dev.pideck.nativeapp.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pideck.nativeapp.data.LocalPrompts
import dev.pideck.nativeapp.data.PromptSummary
import dev.pideck.nativeapp.data.PromptTemplate
import dev.pideck.nativeapp.data.PromptTemplatesParse
import dev.pideck.nativeapp.data.PromptsParse
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.attempt
import dev.pideck.nativeapp.data.describeConnectionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 输入面板里「提示词库」那一整套：桌面端模板（`/api/prompt-templates`）与云端提示词库（`/api/prompts`）。
 *
 * 从 `ChatViewModel` 分出来的第四块。它与聊天流无关，只读宿主数据 + 一份预览态。
 */
internal class PromptLibraryController(
    private val scope: CoroutineScope,
    private val api: () -> PideckApi,
    private val device: () -> dev.pideck.nativeapp.data.Device?,
    private val token: () -> String?,
    private val localPrompts: () -> List<LocalPrompts.Prompt>,
) {
    var prompts by mutableStateOf<List<PromptSummary>>(emptyList())
        private set

    var promptCategories by mutableStateOf<List<String>>(emptyList())
        private set

    /** null = 全部分类。 */
    var promptCategory by mutableStateOf<String?>(null)
        private set

    var promptQuery by mutableStateOf("")
        private set

    var promptsBusy by mutableStateOf(false)
        private set

    var promptsError by mutableStateOf<String?>(null)
        private set

    /** 插入失败的一句提示（输入框旁边一行小字，别用 Toast：会盖住键盘上方）。 */
    var promptInsertError by mutableStateOf<String?>(null)

    /**
     * 预览中的那条提示词（点列表行 → 先看模板正文，再决定插不插入）。
     *
     * 桌面端点一条也是「先展开正文」：模板里常有 `{{变量}}` 与多段说明，
     * 直接塞进输入框会让人不知道自己在发什么。
     */
    var promptPreview by mutableStateOf<PromptSummary?>(null)
        private set

    var promptPreviewBody by mutableStateOf<String?>(null)
        private set

    var promptPreviewBusy by mutableStateOf(false)
        private set

    /**
     * 桌面端提示词模板（`GET /api/prompt-templates`，宿主 0.13.0 起）。
     *
     * 注意与 [prompts] 的区别：那个是桌面端「提示词库」（sqlite 里导入过的），
     * 这个是 pi 自己 `~/.pi/agent/prompts` 里的模板 —— 以前只有桌面端读得到。
     */
    var hostTemplates by mutableStateOf<List<PromptTemplate>>(emptyList())
        private set

    var hostTemplatesBusy by mutableStateOf(false)
        private set

    /** 宿主太旧（没有这条路由）时说一句，别把「读不到」当成「没有模板」。 */
    var hostTemplatesError by mutableStateOf<String?>(null)
        private set

    /** 快捷消息（本机清单，见 `data/QuickMessages.kt`）。 */
    private var promptPage = 1
    private var promptsHasMore = false

    val promptsHasMorePages: Boolean get() = promptsHasMore

    fun openPromptLibrary() {
        prompts = emptyList()
        promptPage = 1
        promptsHasMore = false
        promptsError = null
        loadPrompts(reset = true)
    }

    fun searchPrompts(text: String) {
        if (text == promptQuery) return
        promptQuery = text
        // 搜索是服务端做的（`?search=`），这里退抖一下再打，别每敲一个字都请求
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(300)
            loadPrompts(reset = true)
        }
    }

    fun pickPromptCategory(category: String?) {
        if (category == promptCategory) return
        promptCategory = category
        loadPrompts(reset = true)
    }

    fun loadMorePrompts() {
        if (promptsBusy || !promptsHasMore) return
        loadPrompts(reset = false)
    }

    private var searchJob: Job? = null
    private var searchSeq = 0

    private fun loadPrompts(reset: Boolean) {
        val device = device() ?: return
        // 新搜索必须顶掉旧的：原本 busy 时直接 return，列表会停在上一轮关键字的结果上；
        // 单靠 busy 还会让旧请求的 finally 把新请求的忙碌状态提前复位。
        val seq = ++searchSeq
        promptsBusy = true
        promptsError = null
        val page = if (reset) 1 else promptPage + 1
        scope.launch {
            try {
                val target = device() ?: return@launch
                val result = api().prompts(
                    device = target,
                    token = token(),
                    search = promptQuery.trim(),
                    category = promptCategory,
                    page = page,
                    pageSize = 20,
                )
                if (seq != searchSeq) return@launch
                prompts = if (reset) result.prompts else prompts + result.prompts.filterNot { incoming ->
                    prompts.any { it.slug == incoming.slug }
                }
                promptCategories = result.categories
                promptPage = page
                promptsHasMore = result.hasMore && result.prompts.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                promptsError = dev.pideck.nativeapp.data.describeConnectionError(e)
            } finally {
                if (seq == searchSeq) promptsBusy = false
            }
        }
    }

    fun previewPrompt(prompt: PromptSummary) {
        promptPreview = prompt
        promptPreviewBody = null
        promptPreviewBusy = true
        scope.launch {
            try {
                promptPreviewBody = promptBody(prompt)
                if (promptPreviewBody == null) {
                    promptInsertError = "这条提示词取不到内容（可能已经在桌面端删掉了）"
                }
            } finally {
                promptPreviewBusy = false
            }
        }
    }

    fun closePromptPreview() {
        promptPreview = null
        promptPreviewBody = null
        promptPreviewBusy = false
    }

    /** 打开提示词面板时拉一次宿主模板（失败静默到 [hostTemplatesError]，不挡面板）。 */
    fun loadHostTemplates() {
        if (hostTemplatesBusy) return
        hostTemplatesBusy = true
        scope.launch {
            try {
                val target = device() ?: return@launch
                val result = attempt { api().promptTemplates(target, token()) }
                result
                    .onSuccess {
                        hostTemplates = it
                        hostTemplatesError = null
                    }
                    .onFailure {
                        hostTemplatesError = "桌面端模板没读到（宿主 0.13.0 起才有这条接口）：" +
                            dev.pideck.nativeapp.data.describeConnectionError(it)
                    }
            } finally {
                hostTemplatesBusy = false
            }
        }
    }

    /** 预览宿主模板：先看正文（模板里常有 `{{变量}}`），再决定插不插入。 */
    fun previewHostTemplate(template: PromptTemplate) {
        promptPreview = PromptSummary(
            slug = template.name,
            title = template.name,
            description = template.description,
            category = "桌面端",
        )
        promptPreviewBody = null
        promptPreviewBusy = true
        scope.launch {
            try {
                val target = device()
                promptPreviewBody = if (target == null) {
                    null
                } else {
                    attempt { api().promptTemplateContent(target, token(), template.name) }.getOrNull()
                }
                if (promptPreviewBody == null) {
                    promptInsertError = "这条桌面端模板取不到正文（可能已经在电脑上删掉了）"
                }
            } finally {
                promptPreviewBusy = false
            }
        }
    }
    suspend fun promptBody(prompt: PromptSummary): String? = attempt {
        val target = device() ?: return@attempt null
        api().promptContent(target, token(), prompt.slug, prompt.category)
    }.getOrNull()

    fun previewLocalPrompt(prompt: LocalPrompts.Prompt) {
        promptPreview = PromptSummary(
            slug = prompt.id,
            title = prompt.title,
            description = "",
            category = "本机",
        )
        promptPreviewBody = prompt.body
        promptPreviewBusy = false
    }
}
