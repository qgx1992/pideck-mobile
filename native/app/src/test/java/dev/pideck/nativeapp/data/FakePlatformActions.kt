package dev.pideck.nativeapp.data

import android.net.Uri

/**
 * 单测用的空平台实现：只记录调用，不做任何 Android 的事。
 *
 * 页面 ViewModel 的所有副作用（起看护、记落脚点、写偏好、读图、存文件）都从这里出去，
 * 于是「点一下发送会不会顺手弹通知」这类行为可以在 JVM 里断言，而不必上真机。
 */
class FakePlatformActions : PlatformActions {

    var watchRunsCount = 0
        private set

    var remembered: Pair<String, String>? = null
        private set

    var touchedDevice: String? = null
        private set

    var savedBusyMode: BusySendMode? = null
        private set

    var exported: Triple<String, String, String>? = null
        private set

    /** 设置后 `imageFromUri` 就返回它（默认「读不出来」）。 */
    var nextImage: PendingImage? = null

    override fun watchRuns() {
        watchRunsCount++
    }

    override suspend fun rememberSession(deviceId: String, sessionId: String) {
        remembered = deviceId to sessionId
    }

    override suspend fun touchDevice(deviceId: String) {
        touchedDevice = deviceId
    }

    override suspend fun setBusySendMode(mode: BusySendMode) {
        savedBusyMode = mode
    }

    override fun imageFromUri(uri: Uri): PendingImage? = nextImage

    override fun imageFromClipboard(): PendingImage? = nextImage

    override suspend fun saveExport(fileName: String, mime: String, content: String): String {
        exported = Triple(fileName, mime, content)
        return "下载/PiDeck/$fileName"
    }
}
