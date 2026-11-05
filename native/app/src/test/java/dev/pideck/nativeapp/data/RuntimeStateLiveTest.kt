package dev.pideck.nativeapp.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真桌面端联调：本机 8765 在跑时才会执行，否则跳过。
 * 守住「上下文用量读取不到」这个回归 —— 外壳换形状时这里会先红。
 */
class RuntimeStateLiveTest {

    private val token: String? = LiveEnv.token()
    private val device = LiveEnv.device()
    private val api = OkHttpPideckApi(defaultOkHttpClient())

    @Test
    fun `context usage reads back from the real desktop`() = runBlocking {
        assumeTrue("没有本机桌面端 token，跳过", token != null)
        val fresh = runCatching { api.state(device, token, null) }.getOrNull()
        assumeTrue("桌面端连不上，跳过", fresh is StateFetch.Fresh)
        val state = (fresh as StateFetch.Fresh).state
        val runtime = state.runtimes.firstOrNull { !it.isRunning } ?: state.runtimes.firstOrNull()
        assumeTrue("这台机器上没有运行时，跳过", runtime != null)

        val usage = api.runtimeState(
            device,
            token,
            RuntimeTarget(runtime!!.sessionId, runtime.agentId, runtime.runtimeGeneration.toLong()),
        )
        println("live context: ${usage.detail} (${usage.percentLabel}) tokens=${usage.contextTokens} in=${usage.inputTokens} out=${usage.outputTokens} provider=${usage.provider}")
        assertTrue("上下文窗口应该读得到", usage.known)
        assertTrue("窗口大小应该大于 0", usage.contextWindow > 0)
    }
}
