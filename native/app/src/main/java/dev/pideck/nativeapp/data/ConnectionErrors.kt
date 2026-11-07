package dev.pideck.nativeapp.data

import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 把连接失败翻译成「下一步该干什么」。
 *
 * 这一步不做的话，用户看到的就只是 `state 401` 或 `ConnectException` —— 前者其实只要把
 * 带 token 的链接整条粘进来，后者是桌面端没开局域网 Web 服务。纯函数，可以直接单测。
 */
fun describeConnectionError(e: Throwable): String {
    if (e is PideckException) {
        val code = e.code
        return when {
            code == null -> e.message ?: "请求失败"
            code == 401 || code == 403 -> {
                // 401 是「这台设备的授权没了」，不是网络问题：留一条常驻提示，
                // 回到设备页时能看到该怎么修（否则用户只会反复重进、以为是网络）。
                AuthNotice.show()
                "令牌不对或没填。桌面端「设置 → 连接面板」里复制带 ?token= 的那条完整链接，整条粘进来。"
            }
            code == 404 ->
                "能连上，但这个端口不是 PiDeck 的 Web 服务（404）。确认端口是 8765（下载页才是 8080）。"
            code in 500..599 -> "桌面端自己报错了（HTTP $code），看一眼电脑上 PiDeck 的日志。"
            else -> "桌面端返回 HTTP $code"
        }
    }
    return when (e) {
        is UnknownHostException -> "找不到这个主机。地址写错了，或者手机不在同一个局域网。"
        is ConnectException -> "连不上这个地址。桌面端没开「局域网 Web 服务」，或者端口不对。"
        is SocketTimeoutException -> "连接超时：手机和电脑不在同一网络，连接被防火墙拦了。"
        is SSLHandshakeException -> "证书握手失败：把地址里的 https 改成 http。"
        is SerializationException -> "能连上，但返回的数据格式对不上（桌面端版本不一致？）。"
        is SocketException -> "网络中断：${e.message ?: "未知原因"}"
        else -> "${e::class.java.simpleName}：${e.message ?: "未知错误"}"
    }
}
