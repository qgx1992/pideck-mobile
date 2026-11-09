package dev.pideck.nativeapp.data

import android.net.Uri

/**
 * 聊天页与会话列表页碰到的那几件「必须真的在 Android 上做」的小事。
 *
 * 收成一个接口的唯一目的：**让 ViewModel 能在 JVM 单测里跑起来**。以前这些调用直接吃
 * `@ApplicationContext Context`，于是 `ChatViewModel` / `SessionsViewModel` 的构造参数里
 * 钉着一个造不出来的 `Context`（单测里 android.jar 的每个方法都会抛），整个页面只能靠手点。
 *
 * 生产实现是 `AndroidPlatformActions`，测试里给一个空实现即可（不用的方法直接 `error()`）。
 * **别往这里加「业务」**：它只做转发，一旦有判断逻辑就该挪回 ViewModel 或 data 层。
 */
interface PlatformActions {
    /** 起前台看护服务（跑完了一条通知）。后台启动会被系统拒，调用点必须保证 App 在前台。 */
    fun watchRuns()

    /** 记住「上次停在哪个会话」，下次冷启动直接回来。 */
    suspend fun rememberSession(deviceId: String, sessionId: String)

    /** 记「这台设备刚用过」（会话列表页每次刷新后打点）。 */
    suspend fun touchDevice(deviceId: String)

    /** 记住「运行中发送」的档位。 */
    suspend fun setBusySendMode(mode: BusySendMode)

    /** 相册/相机回来的 Uri → 压好的图片（读不出来返回 null）。 */
    fun imageFromUri(uri: Uri): PendingImage?

    /** 剪贴板里的图片（截图后直接粘）。 */
    fun imageFromClipboard(): PendingImage?

    /** 存到「下载/PiDeck」，返回给用户看的位置描述。 */
    suspend fun saveExport(fileName: String, mime: String, content: String): String
}
