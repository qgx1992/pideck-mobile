package dev.pideck.nativeapp.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pideck.nativeapp.data.LocalPrompts
import dev.pideck.nativeapp.data.QuickMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 输入面板里「只存在这台手机上」的两样东西：快捷消息清单与本机提示词模板。
 *
 * 从 `ChatViewModel` 分出来的第三块：纯本地读写（SharedPreferences），不发任何 HTTP，
 * 所以搬出来不会牵动聊天流。界面仍走 `viewModel.quickMessages` 等转发属性。
 */
internal class LocalLibraryController(private val scope: CoroutineScope) {
    var quickMessages by mutableStateOf<List<String>>(emptyList())
        private set

    var quickDraft by mutableStateOf("")

    var prompts by mutableStateOf<List<LocalPrompts.Prompt>>(emptyList())
        private set

    var promptTitle by mutableStateOf("")
    var promptBody by mutableStateOf("")

    var editorOpen by mutableStateOf(false)
        private set
    fun loadQuickMessages() {
        quickMessages = QuickMessages.load()
    }

    fun updateQuickDraft(text: String) {
        quickDraft = text
    }

    fun addQuickMessage() {
        val text = quickDraft.trim()
        if (text.isBlank()) return
        quickMessages = QuickMessages.add(text)
        quickDraft = ""
    }

    fun removeQuickMessage(text: String) {
        quickMessages = QuickMessages.remove(text)
    }
    fun loadLocalPrompts() {
        prompts = LocalPrompts.load()
    }

    fun openLocalPromptEditor() {
        promptTitle = ""
        promptBody = ""
        editorOpen = true
    }

    fun closeLocalPromptEditor() {
        editorOpen = false
    }

    fun saveLocalPrompt() {
        if (promptBody.isBlank()) return
        prompts = LocalPrompts.add(promptTitle, promptBody)
        editorOpen = false
        promptTitle = ""
        promptBody = ""
    }

    fun removeLocalPrompt(id: String) {
        prompts = LocalPrompts.remove(id)
    }

    /** 本机模板也走同一个预览态：先看正文再插入，行为与桌面端模板一致。 */
}
