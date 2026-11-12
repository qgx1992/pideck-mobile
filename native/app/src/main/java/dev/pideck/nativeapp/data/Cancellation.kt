package dev.pideck.nativeapp.data

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` 的取消安全版本。
 *
 * `attempt { }` 抓的是 `Throwable`，把 `CancellationException` 一起吞了：协程被 cancel
 * （点停止、离开页面、`viewModelScope` 销毁）时它只是「静默失败」，后面的代码继续跑，
 * 而且要等很长一段才结束 —— 用户看到的是「点了停止还在跑」「退出页面后又弹出错误」。
 *
 * 所以凡是**可能被取消**的调用（挂起函数、`viewModelScope.launch` 里的一切）都用这个：
 * 取消照常往上抛，其它异常照旧收成 `Result.failure`，可以继续 `.getOrNull()` / `.onFailure { }`。
 *
 * 纯同步、不会挂起的解析（`Json.decodeFromString`、`parseX`）用 `runCatching` 也没问题；
 * 但统一成 `attempt` 更省心 —— 哪天被挪进协程也不会悄悄变成 bug。
 */
inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
