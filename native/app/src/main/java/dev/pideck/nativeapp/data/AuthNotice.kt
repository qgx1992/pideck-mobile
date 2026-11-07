package dev.pideck.nativeapp.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「这台设备的授权没了」的一次性提示。
 *
 * 桌面端可以撤销/重建 Web 服务的访问令牌，之后本机还能存着旧令牌 —— 请求全是 401。
 * 冷启动时如果直接按「有设备就进首页」走，进去只会是一屏红字，退出重进还是一样，
 * 用户能做的只有「删掉设备重配」但没人告诉他。
 *
 * 所以：启动时先验一次（`StartViewModel`），401/403 就落到设备页并把这句话摆在那儿；
 * 会话里撞到 401 也写这里，退出到设备页时同样能看到。
 */
object AuthNotice {

    var message by mutableStateOf<String?>(null)
        private set

    fun show(text: String = DEFAULT) {
        message = text
    }

    fun clear() {
        message = null
    }

    const val DEFAULT: String =
        "这台设备的授权已经失效（桌面端撤销过令牌，或者桌面端重置了 Web 服务）。" +
            "回「设备」页删掉这台、用新令牌重新配对。"
}
