package dev.pideck.nativeapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pideck.nativeapp.data.AskQuestion
import dev.pideck.nativeapp.data.AskUi
import dev.pideck.nativeapp.data.PendingUiRequest
import dev.pideck.nativeapp.ui.theme.AppButton
import dev.pideck.nativeapp.ui.theme.AppButtonStyle
import kotlinx.serialization.json.JsonObject

/**
 * AI 提问卡（`ask_question` / `confirm` / `input` / `multi_select`，含批量信封）。
 *
 * 放在输入条上方常驻：一轮 agent 正在等用户回答时，用户可能滚到了历史里，
 * 卡片不能只在列表尾部出现（桌面网页版也是这么做的，见 WebChatApp 的 `activePendingUi`）。
 *
 * 可折叠（默认展开）：题目长、选项多的时候卡片能占掉半屏，而用户往往正想先读完上面的
 * 上下文再回来答。点标题栏收起，收起后标题栏右侧仍留一行题干（`maxLines = 1`），
 * 不会变成「有一张卡但不知道在问什么」。
 */
@Composable
fun AskCard(
    request: PendingUiRequest,
    busy: Boolean,
    error: String?,
    onRespond: (JsonObject) -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
    val questions = request.batchQuestions
    val isBatch = questions.isNotEmpty()

    var tab by rememberSaveable(request.requestId) { mutableIntStateOf(0) }
    var answers by remember(request.requestId) {
        mutableStateOf<Map<String, AskUi.Answer>>(emptyMap())
    }
    var draft by rememberSaveable(request.requestId) { mutableStateOf(request.prefill.orEmpty()) }
    var multi by remember(request.requestId) { mutableStateOf<Set<String>>(emptySet()) }
    // 默认展开；按 requestId 记住，换一道新题重新展开。
    var expanded by rememberSaveable(request.requestId) { mutableStateOf(true) }

    val current: AskQuestion? = if (isBatch) questions.getOrNull(tab) else null
    val prompt = current?.question?.ifBlank { request.title } ?: request.title
    val type = current?.type ?: request.method
    val options = remember(request.requestId, tab, type) {
        val fromQuestion = current?.options
        if (fromQuestion.isNullOrEmpty()) AskUi.options(request.options) else AskUi.options(fromQuestion)
    }
    val allowCustom = (current?.allowOther ?: request.allowOther) != false

    fun submit(answer: AskUi.Answer) {
        if (!isBatch) {
            onRespond(AskUi.response(type, answer))
            return
        }
        val next = answers + (questions[tab].id to answer)
        answers = next
        draft = ""
        multi = emptySet()
        if (tab >= questions.size - 1) {
            onRespond(
                AskUi.response(
                    "batch",
                    AskUi.Answer.Text(AskUi.serializeBatch(questions, next)),
                ),
            )
        } else {
            tab += 1
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // 标题栏 = 折叠开关：整条可点，右侧箭头指示状态。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isBatch) "需要你回答（${tab + 1}/${questions.size}）" else "需要你回答",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                )
            }

            // 收起时把题干压成一行，仍然看得出在问什么。
            if (!expanded && prompt.isNotBlank()) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp, top = 2.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    if (prompt.isNotBlank()) {
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            options.isNotEmpty() && type == "multi_select" -> {
                                options.forEach { option ->
                                    val selected = option.value in multi
                                    AppButton(
                                        text = (if (selected) "✓ " else "○ ") + option.label,
                                        onClick = {
                                            multi = if (selected) multi - option.value else multi + option.value
                                        },
                                        style = if (selected) AppButtonStyle.Filled else AppButtonStyle.Tonal,
                                        fillWidth = true,
                                    )
                                }
                                AppButton(
                                    text = if (isBatch && tab < questions.size - 1) "下一题" else "提交",
                                    onClick = { submit(AskUi.Answer.Many(multi.toList())) },
                                    enabled = !busy && multi.isNotEmpty(),
                                    fillWidth = true,
                                )
                            }

                            options.isNotEmpty() -> {
                                options.forEach { option ->
                                    Column {
                                        AppButton(
                                            text = option.label,
                                            onClick = { submit(AskUi.Answer.Text(option.value)) },
                                            enabled = !busy,
                                            style = AppButtonStyle.Tonal,
                                            fillWidth = true,
                                        )
                                        option.description?.let { desc ->
                                            Text(
                                                text = desc,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                                            )
                                        }
                                    }
                                }
                                if (allowCustom) {
                                    OutlinedTextField(
                                        value = draft,
                                        onValueChange = { draft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text(request.placeholder ?: "或者直接写你的回答") },
                                        minLines = 2,
                                        maxLines = 4,
                                    )
                                    AppButton(
                                        text = if (isBatch && tab < questions.size - 1) "下一题" else "提交",
                                        onClick = { submit(AskUi.Answer.Text(draft.trim())) },
                                        enabled = !busy && draft.isNotBlank(),
                                        fillWidth = true,
                                    )
                                }
                            }

                            type == "confirm" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppButton(
                                        text = "是",
                                        onClick = { submit(AskUi.Answer.Flag(true)) },
                                        enabled = !busy,
                                    )
                                    AppButton(
                                        text = "否",
                                        onClick = { submit(AskUi.Answer.Flag(false)) },
                                        enabled = !busy,
                                        style = AppButtonStyle.Tonal,
                                    )
                                }
                            }

                            else -> {
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text(request.placeholder ?: "输入你的回答") },
                                    minLines = 2,
                                    maxLines = 6,
                                )
                                AppButton(
                                    text = if (isBatch && tab < questions.size - 1) "下一题" else "提交",
                                    onClick = { submit(AskUi.Answer.Text(draft.trim())) },
                                    enabled = !busy && draft.isNotBlank(),
                                    fillWidth = true,
                                )
                            }
                        }
                    }

                    error?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismissError) { Text("知道了") }
                        }
                    }

                    Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onCancel, enabled = !busy) { Text("取消回答") }
                        Spacer(Modifier.weight(1f))
                        if (isBatch && tab > 0) {
                            TextButton(onClick = { tab -= 1 }, enabled = !busy) { Text("上一题") }
                        }
                    }
                }
            }
        }
    }
}
