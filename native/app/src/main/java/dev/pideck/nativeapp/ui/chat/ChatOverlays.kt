package dev.pideck.nativeapp.ui.chat

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.pideck.nativeapp.data.ImagePrep
import dev.pideck.nativeapp.data.SessionModel
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import dev.pideck.nativeapp.data.TimelineItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import kotlinx.coroutines.CoroutineScope

/**
 * 聊天页的浮层开关（提示词面板 / 附件 / 模型 / 历史 / 上下文 / 导出预览……）。
 *
 * 这些以前是 `ChatScreen` 里 18 个分散的 `remember`，主函数光状态声明就占了一屏。
 * 收进一个容器后，主函数只留一行 `val overlays = remember { ChatOverlays() }`；
 * 容器不进 ViewModel —— 它描述的是「这一屏上开着什么」，换会话或重建组合时就该重来一次。
 */
internal class ChatOverlays {
    var workspace by mutableStateOf(false)
    var model by mutableStateOf(false)
    var thinking by mutableStateOf(false)
    var history by mutableStateOf(false)
    var more by mutableStateOf(false)
    var permission by mutableStateOf(false)
    var security by mutableStateOf(false)
    var context by mutableStateOf(false)
    var attach by mutableStateOf(false)
    var prompts by mutableStateOf(false)
    var panelTab by mutableStateOf(ComposerPanelTab.Quick)
    var actionTarget by mutableStateOf<TimelineItem?>(null)
    var detail by mutableStateOf<DetailContent?>(null)
    var freeCopy by mutableStateOf<TimelineItem?>(null)
    var jumpKey by mutableStateOf<String?>(null)
    var jumpTick by mutableIntStateOf(0)
    var flashKey by mutableStateOf<String?>(null)
    var flashTick by mutableIntStateOf(0)
}

// 聊天页的浮层挂载：面板拖出来放在这里，主函数只留一行调用。
// 新增浮层时先在这个文件里加 `if (overlays.xxx) { ... }`，不要在 ChatScreen 里再堆一段。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatOverlayHost(
    viewModel: ChatViewModel,
    overlays: ChatOverlays,
    listState: LazyListState,
    clipboard: ClipboardManager,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    insertIntoDraft: (String) -> Unit,
    sendText: (String) -> Unit,
    onPickImage: () -> Unit,
    onJumpTo: (String) -> Unit,
    onScrolledAway: () -> Unit,
) {
    if (overlays.prompts) {
        ComposerPanelSheet(
            tab = overlays.panelTab,
            onTabChange = { overlays.panelTab = it },
            prompts = viewModel.prompts,
            categories = viewModel.promptCategories,
            category = viewModel.promptCategory,
            query = viewModel.promptQuery,
            loading = viewModel.promptsBusy,
            error = viewModel.promptsError,
            hasMore = viewModel.promptsHasMorePages,
            preview = viewModel.promptPreview,
            previewBody = viewModel.promptPreviewBody,
            previewBusy = viewModel.promptPreviewBusy,
            onQueryChange = viewModel::searchPrompts,
            onCategoryChange = viewModel::pickPromptCategory,
            onLoadMore = viewModel::loadMorePrompts,
            onPreview = { prompt -> viewModel.previewPrompt(prompt) },
            onBackFromPreview = { viewModel.closePromptPreview() },
            onInsertPreview = { body ->
                insertIntoDraft(body)
                overlays.prompts = false
            },
            localPrompts = viewModel.localPrompts,
            localEditor = viewModel.localPromptEditor,
            localTitle = viewModel.localPromptTitle,
            localBody = viewModel.localPromptBody,
            onLocalTitleChange = { viewModel.localPromptTitle = it },
            onLocalBodyChange = { viewModel.localPromptBody = it },
            onOpenLocalEditor = { viewModel.openLocalPromptEditor() },
            onCloseLocalEditor = { viewModel.closeLocalPromptEditor() },
            onSaveLocal = { viewModel.saveLocalPrompt() },
            onRemoveLocal = { viewModel.removeLocalPrompt(it) },
            onPreviewLocal = { viewModel.previewLocalPrompt(it) },
            hostTemplates = viewModel.hostTemplates,
            hostTemplatesBusy = viewModel.hostTemplatesBusy,
            hostTemplatesError = viewModel.hostTemplatesError,
            onPreviewHost = { viewModel.previewHostTemplate(it) },
            quickMessages = viewModel.quickMessages,
            quickDraft = viewModel.quickDraft,
            onQuickDraftChange = viewModel::updateQuickDraft,
            onAddQuick = { viewModel.addQuickMessage() },
            onRemoveQuick = { viewModel.removeQuickMessage(it) },
            onInsertQuick = { text ->
                insertIntoDraft(text)
                overlays.prompts = false
            },
            onSendQuick = { text ->
                overlays.prompts = false
                sendText(text)
            },
            onDismiss = {
                overlays.prompts = false
                viewModel.closePromptPreview()
            },
        )
    }
    if (overlays.attach) {
        ModalBottomSheet(onDismissRequest = { overlays.attach = false }) {
            SheetAction(
                label = "从相册选择",
                detail = "最多 ${ImagePrep.MAX_COUNT} 张",
                onClick = {
                    overlays.attach = false
                    onPickImage()
                },
            )
            SheetAction(
                label = "粘贴图片",
                detail = "剪贴板里的截图",
                onClick = {
                    overlays.attach = false
                    viewModel.pasteImage()
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
    if (overlays.model) {
        ModelSheet(
            models = viewModel.availableModels,
            current = viewModel.displayModel,
            busy = viewModel.modelsBusy,
            onRefresh = { viewModel.loadModels(force = true) },
            onPick = { model ->
                overlays.model = false
                viewModel.applyModel(
                    SessionModel(provider = model.provider, modelId = model.id, modelName = model.name),
                )
            },
            onDismiss = { overlays.model = false },
        )
    }
    if (overlays.thinking) {
        ThinkingSheet(
            levels = viewModel.thinkingLevels,
            current = viewModel.displayThinking,
            modelLabel = viewModel.displayModel?.shortLabel,
            onPick = { level ->
                overlays.thinking = false
                viewModel.applyThinking(level)
            },
            onDismiss = { overlays.thinking = false },
        )
    }
    if (overlays.history) {
        val turns = remember(viewModel.items) { buildHistoryTurns(viewModel.items, ::clock) }
        HistorySheet(
            turns = turns,
            onDismiss = { overlays.history = false },
            onPick = { turn ->
                overlays.history = false
                onScrolledAway()
                overlays.jumpKey = turn.key
                overlays.jumpTick += 1
                overlays.flashKey = turn.key
                overlays.flashTick += 1
            },
        )
    }
    if (overlays.security) {
        SecurityLevelSheet(
            security = viewModel.security,
            sessionId = viewModel.sessionId,
            busy = viewModel.securityBusy,
            onPick = { levelId ->
                viewModel.chooseSecurityLevel(levelId)
                overlays.security = false
            },
            onRefresh = { viewModel.refreshSecurity() },
            onDismiss = { overlays.security = false },
        )
    }
    if (overlays.permission) {
        PermissionSheet(
            current = viewModel.permissionPreset,
            busy = viewModel.presetBusy,
            onPick = { preset ->
                viewModel.choosePermission(preset)
                overlays.permission = false
            },
            onDismiss = { overlays.permission = false },
        )
    }
    if (overlays.context) {
        ContextSheet(
            usage = viewModel.contextUsage,
            busy = viewModel.contextBusy,
            compactBusy = viewModel.compactBusy,
            cache = viewModel.cacheStat,
            cacheBusy = viewModel.cacheBusy,
            control = viewModel.securityControl,
            securityLevelLabel = viewModel.securityLevelLabel,
            permissionLabel = dev.pideck.nativeapp.data.PermissionPresets.labelOf(viewModel.permissionPreset),
            onRefresh = {
                viewModel.refreshContext()
                viewModel.refreshCacheStat()
            },
            onCompact = { prompt -> viewModel.compact(prompt) },
            onDismiss = { overlays.context = false },
        )
    }
}