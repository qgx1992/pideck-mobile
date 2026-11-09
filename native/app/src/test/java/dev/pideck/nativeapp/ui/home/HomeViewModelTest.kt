package dev.pideck.nativeapp.ui.home

import androidx.lifecycle.SavedStateHandle
import dev.pideck.nativeapp.data.AvailableModel
import dev.pideck.nativeapp.data.ConnectionResolver
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.data.DeviceDirectory
import dev.pideck.nativeapp.data.FakePideckApi
import dev.pideck.nativeapp.data.PideckException
import dev.pideck.nativeapp.data.PideckState
import dev.pideck.nativeapp.data.Project
import dev.pideck.nativeapp.data.Session
import dev.pideck.nativeapp.data.SessionStateStore
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
 * 首页 ViewModel 的行为测试（0.5.42 拆完接口后第一次能这么写）。
 *
 * 盯的都是「界面上看不出来、但用户会骂」的分支：
 * 拉取成功要落到 Ready 并填上工作区；失败要落成 Failed 而不是空白；
 * 设备不在了要给出可操作的一句话；开新会话失败只污染 `actionError`，不能把整屏变成错误态。
 */
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    private val project = Project(
        id = "p1",
        name = "PiDeck-Mobile",
        path = "D:\\A-my project\\PiDeck-Mobile",
    )

    private fun session(id: String, updatedAt: Long, parent: String? = null) = Session(
        id = id,
        projectId = "p1",
        title = "会话 $id",
        updatedAt = updatedAt,
        parentSessionId = parent,
    )

    private fun state(
        projects: List<Project> = listOf(project),
        sessions: List<Session> = listOf(session("s1", 100L), session("s2", 200L)),
    ) = PideckState(projects = projects, sessions = sessions)

    private fun viewModel(
        api: FakePideckApi,
        devices: Flow<List<Device>> = flowOf(listOf(device)),
    ) = HomeViewModel(
        savedStateHandle = SavedStateHandle(mapOf("deviceId" to "d1")),
        connections = ConnectionResolver(FakeDirectory(devices)),
        api = api,
        stateStore = SessionStateStore(),
    )

    /** 默认把 `models` 打桩（init 会顺手拉一次模型清单，不打桩就会抛未打桩异常）。 */
    private fun apiWithState(fetch: suspend () -> StateFetch) = object : FakePideckApi() {
        override suspend fun state(device: Device, token: String?, etag: String?): StateFetch = fetch()

        override suspend fun models(device: Device, token: String?, force: Boolean): List<AvailableModel> =
            emptyList()
    }

    @Test
    fun `load fills projects and recent sessions then goes Ready`() = runTest(dispatcher) {
        val vm = viewModel(apiWithState { StateFetch.Fresh(state(), "etag-1") })
        advanceUntilIdle()

        assertEquals(LoadState.Ready, vm.loadState)
        assertEquals(false, vm.loading)
        assertNull(vm.error)
        assertEquals(listOf("p1"), vm.projects.map { it.id })
        // 最近会话按更新时间倒序
        assertEquals(listOf("s2", "s1"), vm.recent.map { it.id })
        // 没有指定过工作区时自动选中第一个
        assertEquals("p1", vm.selectedProjectId)
        assertEquals("本机", vm.deviceName)
    }

    @Test
    fun `sub sessions never show up in recent list`() = runTest(dispatcher) {
        val vm = viewModel(
            apiWithState {
                StateFetch.Fresh(
                    state(sessions = listOf(session("root", 10L), session("child", 99L, parent = "root"))),
                    null,
                )
            },
        )
        advanceUntilIdle()

        assertEquals(listOf("root"), vm.recent.map { it.id })
    }

    @Test
    fun `failed load is a failure state not a blank page`() = runTest(dispatcher) {
        val vm = viewModel(apiWithState { throw PideckException(code = 500, message = "boom") })
        advanceUntilIdle()

        val failed = vm.loadState
        assertTrue("失败要落成 Failed，实际是 $failed", failed is LoadState.Failed)
        assertEquals(false, vm.loading)
        assertTrue("错误文案要能显示", !vm.error.isNullOrBlank())
    }

    @Test
    fun `deleted device gives an actionable message`() = runTest(dispatcher) {
        val vm = viewModel(apiWithState { StateFetch.Fresh(state(), null) }, devices = flowOf(emptyList()))
        advanceUntilIdle()

        val failed = vm.loadState
        assertTrue(failed is LoadState.Failed)
        assertEquals("设备已不存在，回设备页重新添加", (failed as LoadState.Failed).message)
    }

    @Test
    fun `new session failure only sets action error`() = runTest(dispatcher) {
        val api = object : FakePideckApi() {
            override suspend fun state(device: Device, token: String?, etag: String?): StateFetch =
                StateFetch.Fresh(state(), null)

            override suspend fun models(device: Device, token: String?, force: Boolean): List<AvailableModel> =
                emptyList()

            override suspend fun createSession(
                device: Device,
                token: String?,
                projectId: String,
                backend: String?,
                acpToolId: String?,
                model: dev.pideck.nativeapp.data.SessionModel?,
                thinkingLevel: String?,
            ): String = throw PideckException(code = 500, message = "nope")
        }
        val vm = viewModel(api)
        advanceUntilIdle()
        assertEquals(LoadState.Ready, vm.loadState)

        var created: String? = null
        vm.startSession("p1") { created = it }
        advanceUntilIdle()

        assertEquals(false, vm.creating)
        assertNull("没建成就不该给出 id", created)
        assertTrue("失败原因要露出来", !vm.error.isNullOrBlank())
        // 整屏状态不该被动作失败污染
        assertEquals(LoadState.Ready, vm.loadState)
    }

    @Test
    fun `successful new session hands the id back`() = runTest(dispatcher) {
        val api = object : FakePideckApi() {
            override suspend fun state(device: Device, token: String?, etag: String?): StateFetch =
                StateFetch.Fresh(state(), null)

            override suspend fun models(device: Device, token: String?, force: Boolean): List<AvailableModel> =
                emptyList()

            override suspend fun createSession(
                device: Device,
                token: String?,
                projectId: String,
                backend: String?,
                acpToolId: String?,
                model: dev.pideck.nativeapp.data.SessionModel?,
                thinkingLevel: String?,
            ): String = "new-session"
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        var created: String? = null
        vm.startSession("p1") { created = it }
        advanceUntilIdle()

        assertEquals("new-session", created)
        assertNull(vm.error)
    }
}
