package dev.pideck.nativeapp.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 传输层联调：`POST /api/chat` 是 **POST + text/event-stream**，与 GET 的会话流不是同一条路。
 *
 * 用不存在的会话 id 打一发 —— 服务端正确返回 `error` + `finish` + `[DONE]` 帧，
 * 既验证「POST 也能被 okhttp-sse 解析」，又不真的启动 agent（不烧模型额度）。
 */
class ChatTransportLiveTest {

    private fun api(): OkHttpPideckApi {
        // 测试用 10s 读超时：真机的 SSE client 是「读不超时」，测试里必须能自己结束。
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return OkHttpPideckApi(client)
    }

    @Test
    fun `post chat stream yields error frames for unknown session`() {
        val token = LiveEnv.token()
        assumeTrue("本机没有桌面端设置，跳过", token != null)

        val body = buildJsonObject {
            put("id", "session-that-does-not-exist")
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "ping")
                        },
                    )
                },
            )
        }

        val frames = runBlocking {
            withTimeout(20_000) {
                api().chat(LiveEnv.device(), token, body).take(10).toList()
            }
        }

        val payloads = frames.map { it.data }
        println("live chat frames: ${payloads.map { it.take(90) }}")
        assertTrue("应当至少有 error 帧：$payloads", payloads.any { it.contains("\"type\":\"error\"") })
        assertTrue("应当有结束标记：$payloads", payloads.any { it.contains("finish") || it.contains("[DONE]") })
    }

    @Test
    fun `message page decodes tool metadata`() {
        val token = LiveEnv.token()
        assumeTrue("本机没有桌面端设置，跳过", token != null)

        val api = api()
        val device = LiveEnv.device()
        val state = runBlocking { api.state(device, token) } as StateFetch.Fresh
        // 取一个有消息的会话（最近更新且非 draft）。
        val session = state.state.sessions
            .filter { it.status != "draft" }
            .maxByOrNull { it.updatedAt }
        assumeTrue("没有可用会话，跳过", session != null)

        val page = runBlocking { api.messagesPage(device, token, session!!.id, pageSize = 40) }
        println("live page: messages=${page.messages.size} total=${page.total} nextBefore=${page.nextBefore}")
        assertTrue("消息页不该是空的", page.messages.isNotEmpty())
        assertTrue(
            "至少有 assistant 消息",
            page.messages.any { it.role == "assistant" },
        )
    }
}
