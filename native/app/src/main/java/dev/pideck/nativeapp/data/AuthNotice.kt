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

    const val TOKEN_LOST: String =
        "本机保存的令牌读不出来了（系统密钥库被重建过：换签名重装、恢复备份，或系统清理了 Keystore）。" +
            "App 已经自动重建过一次令牌库；如果还连不上，到「设备」页删掉这台，" +
            "用桌面端连接面板里的链接重新配对一次。"

    const val DEFAULT: String =
        "这台设备的授权已经失效（桌面端撤销过令牌，或者桌面端重置了 Web 服务）。" +
            "回「设备」页删掉这台、用新令牌重新配对。"
}
