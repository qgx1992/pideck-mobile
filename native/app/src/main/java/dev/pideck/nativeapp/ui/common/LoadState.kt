package dev.pideck.nativeapp.ui.common

import dev.pideck.nativeapp.data.describeConnectionError

/**
 * 列表类页面只有这四种状态，统一成一份（0.5.42）。
 *
 * 之前每个 ViewModel 各有一对 `loading: Boolean` + `error: String?`，而界面判断空态时是
 * `if (items.isEmpty()) …`，于是「加载失败」和「真的没有内容」在屏幕上长得一模一样
 * （用户看到的是「还没有会话」而不是「连不上」）。现在状态只有一个来源，[isLoading] /
 * [message] 是给界面用的便捷读法。
 *
 * 注意：**不要**再加一个 `Offline` 之类与 `Failed` 语义重叠的状态 —— 判断「能不能连上」
 * 是请求层的事，这里只记「这一屏现在是什么样」。
 */
sealed interface LoadState {
    /** 还没开始拉（首帧），界面按「空的」渲染，别显示转圈。 */
    data object Idle : LoadState

    /** 正在拉。已经有内容时也可以处在 Loading（下拉刷新），所以界面要自己判断要不要盖骨架。 */
    data object Loading : LoadState

    /** 拉完了，内容可能是空的 —— 这时候「空」是真结论。 */
    data object Ready : LoadState

    /** 失败了。[message] 已经是给用户看的中文（来自 `describeConnectionError`）。 */
    data class Failed(val message: String) : LoadState
}

val LoadState.isLoading: Boolean
    get() = this is LoadState.Loading

/** 需要显示的错误文案；不是失败就是 null。 */
val LoadState.message: String?
    get() = (this as? LoadState.Failed)?.message

/** 失败（把异常翻成人话）；不是异常就当成未知。 */
fun failureOf(e: Throwable): LoadState = LoadState.Failed(describeConnectionError(e))

/** 服务端明确说「没有」时用这个，别让界面把它渲染成错误。 */
fun failureOf(message: String): LoadState = LoadState.Failed(message)
