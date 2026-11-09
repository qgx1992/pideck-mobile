package dev.pideck.nativeapp.ui.chat

import androidx.lifecycle.SavedStateHandle
import dev.pideck.nativeapp.data.BusySendMode
import dev.pideck.nativeapp.data.ChatMessage
import dev.pideck.nativeapp.data.ConnectionResolver
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.DeviceDirectory
import dev.pideck.nativeapp.data.FakePideckApi
import dev.pideck.nativeapp.data.FakePlatformActions
import dev.pideck.nativeapp.data.PideckException
import dev.pideck.nativeapp.data.PideckState
import dev.pideck.nativeapp.data.RuntimeInfo
import dev.pideck.nativeapp.data.RuntimeSnapshot
import dev.pideck.nativeapp.data.RuntimeTarget
import dev.pideck.nativeapp.data.SessionActivity
import dev.pideck.nativeapp.data.SessionMessagePage
import dev.pideck.nativeapp.data.StateCache
import dev.pideck.nativeapp.data.SessionStateStore
import dev.pideck.nativeapp.data.SseFrame
import dev.pideck.nativeapp.data.StateFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 聊天页 ViewModel 的行为测试（0.5.43）。
 *
 * **这里刻意不用 `runTest`**：`startWatching()` 的重连与 `startStatePolling()` 的每秒轮询都是
 * 无限 `while + delay`，而 `runTest` 收尾会把虚拟时间一路推下去（不花真实时间，于是永不结束）——
 * 踩过一次，整个测试套件挂死 15 分钟。改成「`Dispatchers.setMain(StandardTestDispatcher())` +
 * `scheduler.runCurrent()` 手动跑一拍」，只执行立刻能跑完的任务，停在第一个 `delay` 上就收工。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // StateCache / SessionActivity 是进程级单例：不清就会串味（上一个用例的 runtime 会让下一个多发一次 abort）
        StateCache.resetForTest()
        SessionActivity.reset()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 把已经排到 Main 上的任务跑完（不推进虚拟时间，所以无限轮询不会转起来）。 */
    private fun tick() = dispatcher.scheduler.runCurrent()

    private val device = Device(
        id = "d1",
        name = "本机",
        baseUrl = "http://127.0.0.1:8765",
        createdAt = 1L,
        lastUsedAt = 2L,
    )

    private class FakeDirectory(
        override val devices: Flow<List<Device>>,
        private val token: String? = "tok",
    ) : DeviceDirectory {
        override fun tokenOf(deviceId: String): String? = token
    }

    /** 记录所有运行时命令，用来断言「停止走的是 abort」。 */
    private open class RecordingApi(
        private val page: suspend () -> SessionMessagePage,
    ) : FakePideckApi() {
        val commands = mutableListOf<String>()

        override suspend fun messagesPage(
            device: Device,
            token: String?,
            sessionId: String,
            pageSize: Int,
            before: Long?,
        ): SessionMessagePage = page()

        override fun sessionStream(device: Device, token: String?, sessionId: String): Flow<SseFrame> =
            emptyFlow()

        override suspend fun runtimeState(
            device: Device,
            token: String?,
            target: RuntimeTarget,
        ): RuntimeSnapshot = throw PideckException(code = 500, message = "轮询失败")

        override suspend fun runtimeCommand(
            device: Device,
            token: String?,
            target: RuntimeTarget,
            action: String,
            extra: JsonObject,
        ): String {
            commands += action
            return "{}"
        }
    }

    private fun viewModel(
        api: FakePideckApi,
        platform: FakePlatformActions = FakePlatformActions(),
        store: SessionStateStore = SessionStateStore(),
    ) = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("deviceId" to "d1", "sessionId" to "s1")),
        connections = ConnectionResolver(FakeDirectory(flowOf(listOf(device)))),
        api = api,
        stateStore = store,
        platform = platform,
    )

    private val history = SessionMessagePage(
        messages = listOf(
            ChatMessage(id = "m1", role = "user", text = "你好", timestamp = 10L),
            ChatMessage(id = "m2", role = "assistant", text = "你好，有什么需要我做的？", timestamp = 20L),
        ),
        total = 2,
    )

    @Test
    fun `init loads history and remembers the stop point`() {
        val platform = FakePlatformActions()
        val vm = viewModel(RecordingApi { history }, platform)
        tick()

        assertEquals(2, vm.items.size)
        assertEquals(false, vm.sending)
        assertEquals(false, vm.loading)
        assertNull(vm.error)
        // 冷启动回来要停在这条会话上
        assertEquals("d1" to "s1", platform.remembered)
    }

    @Test
    fun `history failure shows an error instead of an empty thread`() {
        val api = RecordingApi { throw PideckException(code = 500, message = "boom") }
        val vm = viewModel(api)
        tick()

        assertTrue("历史读不到要有错误文案", !vm.error.isNullOrBlank())
        assertEquals(false, vm.loading)
    }

    @Test
    fun `stop uses the abort command`() {
        val store = SessionStateStore()
        store.put(
            "d1",
            PideckState(
                runtimes = listOf(
                    RuntimeInfo(
                        sessionId = "s1",
                        agentId = "agent-1",
                        runtimeGeneration = 7,
                        status = "running",
                    ),
                ),
            ),
        )
        val api = RecordingApi { history }
        val vm = viewModel(api, store = store)
        tick()

        vm.stop()
        tick()

        // 只能断这一轮：runtime/stop 是卸运行时，下次发消息要重新激活几十秒
        assertEquals(listOf("abort"), api.commands)
    }

    @Test
    fun `stop without a runtime sends nothing`() {
        val api = RecordingApi { history }
        val vm = viewModel(api)
        tick()

        vm.stop()
        tick()

        assertTrue(api.commands.isEmpty())
    }

    @Test
    fun `busy send mode is persisted`() {
        val platform = FakePlatformActions()
        val vm = viewModel(RecordingApi { history }, platform)
        tick()

        vm.rememberBusySendMode(BusySendMode.FOLLOW_UP)
        tick()

        assertEquals(BusySendMode.FOLLOW_UP, platform.savedBusyMode)
        assertEquals(BusySendMode.FOLLOW_UP, vm.busySendMode)
    }
}
