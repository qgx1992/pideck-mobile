package dev.pideck.nativeapp.ui.sessions

import androidx.lifecycle.SavedStateHandle
import dev.pideck.nativeapp.data.ConnectionResolver
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.DeviceDirectory
import dev.pideck.nativeapp.data.FakePideckApi
import dev.pideck.nativeapp.data.FakePlatformActions
import dev.pideck.nativeapp.data.PideckException
import dev.pideck.nativeapp.data.PideckState
import dev.pideck.nativeapp.data.Project
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.data.SessionActivity
import dev.pideck.nativeapp.data.SessionStateStore
import dev.pideck.nativeapp.data.StateCache
import dev.pideck.nativeapp.data.StateFetch
import dev.pideck.nativeapp.ui.common.LoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「全部会话」页的行为测试（0.5.43 把 `Context` / `DeviceRepository` 从构造参数里摘掉之后才可写）。
 *
 * 盯的三件事：拉到之后进入 Ready 且分组**按项目**拼好、失败是 Failed 而不是空白、
 * 设备不在时刷新要给出可操作文案（以前 `device ?: return` 会让页面永远停在 Idle）。
 */
class SessionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        StateCache.resetForTest()
        SessionActivity.reset()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

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

    private val work = Project(id = "p1", name = "PiDeck-Mobile", path = "D:\\A-my project\\PiDeck-Mobile")
    private val chat = Project(id = "p2", name = "闲聊灵感", path = "D:\\A-my project\\闲聊灵感", kind = "chat")

    private fun session(id: String, projectId: String, title: String, updatedAt: Long, parent: String? = null) =
        Session(
            id = id,
            projectId = projectId,
            title = title,
            updatedAt = updatedAt,
            parentSessionId = parent,
        )

    private fun viewModel(
        api: FakePideckApi,
        devices: Flow<List<Device>> = flowOf(listOf(device)),
        platform: FakePlatformActions = FakePlatformActions(),
    ): Pair<SessionsViewModel, FakePlatformActions> {
        val vm = SessionsViewModel(
            savedStateHandle = SavedStateHandle(mapOf("deviceId" to "d1")),
            connections = ConnectionResolver(FakeDirectory(devices)),
            api = api,
            stateStore = SessionStateStore(),
            platform = platform,
        )
        return vm to platform
    }

    private fun apiReturning(state: PideckState) = object : FakePideckApi() {
        override suspend fun state(device: Device, token: String?, etag: String?): StateFetch =
            StateFetch.Fresh(state, "etag-1")
    }

    private val sample = PideckState(
        projects = listOf(work, chat),
        sessions = listOf(
            session("s1", "p1", "修复 bug", 100L),
            session("s2", "p1", "历史跳转", 300L),
            session("s3", "p2", "聊天气泡", 200L),
            session("child", "p1", "子代理", 400L, parent = "s2"),
        ),
    )

    @Test
    fun `refresh fills groups and goes Ready`() = runTest(dispatcher) {
        val (vm, platform) = viewModel(apiReturning(sample))
        advanceUntilIdle()

        assertEquals(LoadState.Ready, vm.loadState)
        assertEquals(false, vm.loading)
        assertNull(vm.error)
        assertEquals("本机", vm.deviceName)
        assertEquals(setOf("p1", "p2"), vm.groups.map { it.projectId }.toSet())
        // 组内只放顶层会话（子代理会话不进列表），按更新时间倒序
        assertEquals(listOf("s2", "s1"), vm.groups.first { it.projectId == "p1" }.sessions.map { it.id })
        // 刷新成功会顺手记「这台设备刚用过」
        assertEquals("d1", platform.touchedDevice)
    }

    @Test
    fun `running sessions start the watcher`() = runTest(dispatcher) {
        val withRuntime = sample.copy(
            runtimes = listOf(
                dev.pideck.nativeapp.data.RuntimeInfo(
                    sessionId = "s1",
                    agentId = "a1",
                    runtimeGeneration = 1,
                    projectId = "p1",
                    cwd = "D:\\App",
                    status = "running",
                ),
            ),
        )
        val (_, platform) = viewModel(apiReturning(withRuntime))
        advanceUntilIdle()

        assertEquals(1, platform.watchRunsCount)
    }

    @Test
    fun `failed refresh lands on Failed`() = runTest(dispatcher) {
        val api = object : FakePideckApi() {
            override suspend fun state(device: Device, token: String?, etag: String?): StateFetch =
                throw PideckException(code = 500, message = "boom")
        }
        val (vm, _) = viewModel(api)
        advanceUntilIdle()

        assertTrue("应为 Failed，实际 ${vm.loadState}", vm.loadState is LoadState.Failed)
        assertTrue("错误文案要能显示", !vm.error.isNullOrBlank())
    }

    @Test
    fun `missing device is a failure not a silent idle`() = runTest(dispatcher) {
        val (vm, _) = viewModel(apiReturning(sample), devices = flowOf(emptyList()))
        advanceUntilIdle()

        val state = vm.loadState
        assertTrue("设备不在也要落失败态，实际是 $state", state is LoadState.Failed)
        assertEquals("设备已不存在，回设备页重新添加", (state as LoadState.Failed).message)
    }

    @Test
    fun `search filters sessions and hides empty projects`() = runTest(dispatcher) {
        val (vm, _) = viewModel(apiReturning(sample))
        advanceUntilIdle()

        vm.search("历史")
        assertEquals(listOf("s2"), vm.visibleGroups.flatMap { it.sessions }.map { it.id })
        assertEquals(1, vm.visibleGroups.size)

        vm.search("这不存在的标题")
        assertTrue(vm.visibleGroups.isEmpty())
    }
}
