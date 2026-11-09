package dev.pideck.nativeapp.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pideck.nativeapp.watch.RunWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** [PlatformActions] 的真实实现：纯转发，一行判断都不许有。 */
@Singleton
class AndroidPlatformActions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DeviceRepository,
) : PlatformActions {

    override fun watchRuns() {
        RunWatcher.start(context)
    }

    override suspend fun rememberSession(deviceId: String, sessionId: String) {
        Prefs.rememberSession(context, deviceId, sessionId)
    }

    override suspend fun touchDevice(deviceId: String) {
        repository.touch(deviceId)
    }

    override suspend fun setBusySendMode(mode: BusySendMode) {
        Prefs.setBusySendMode(context, mode)
    }

    override fun imageFromUri(uri: Uri): PendingImage? = ImagePrep.fromUri(context, uri)

    override fun imageFromClipboard(): PendingImage? = ImagePrep.fromClipboard(context)

    override suspend fun saveExport(fileName: String, mime: String, content: String): String =
        withContext(Dispatchers.IO) { FileSaver.saveText(context, fileName, mime, content) }
}
