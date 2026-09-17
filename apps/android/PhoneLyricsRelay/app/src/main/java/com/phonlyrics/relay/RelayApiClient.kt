package com.phonlyrics.relay

import java.net.HttpURLConnection
import java.net.URL

/// 一次发送的结果。`code` 是 HTTP 状态码,-1 表示连不上、0 表示没有令牌压根没发。
/// 带上它是因为 401(令牌失效)和 -1(网络不通)要引到完全不同的处置上,见 sendDetailed 的注释。
data class SendOutcome(val ok: Boolean, val latencyMs: Long, val code: Int) {
    /// 配对失效(令牌被 Mac 撤销、或 Mac 删掉配对后重装过)。用户要回 Mac 重新配对。
    val authRejected: Boolean get() = code == 401
    /// 压根没发出去(Mac 地址不可达 / 不在同一网络)。
    val unreachable: Boolean get() = code == -1
}

class RelayApiClient(@Volatile var host: String, @Volatile var port: Int, @Volatile var token: String) {
    fun health(): Boolean = request("GET", "/api/v1/health", null, false) in 200..299
    fun send(envelope: PlaybackEnvelope): Boolean {
        if (token.isBlank()) return false
        return request("POST", "/api/v1/playback", envelope.toJson(), true) in 200..299
    }

    /// 带耗时的发送。返回 (是否成功, 这次往返的毫秒数)。
    ///
    /// 跟 `send` 的区别只有"把耗时量出来"(2026-09-17,给状态页的仪表盘用)——抽成另一个方法
    /// 而不是改 `send` 的签名:`send` 是 `RelayEventQueue` 的 sender 契约(单元测试直接构造它),
    /// 改签名会连带改掉那条既有测试;而耗时只有服务那条路径关心。
    fun sendTimed(envelope: PlaybackEnvelope): Pair<Boolean, Long> {
        if (token.isBlank()) return false to 0L
        val started = System.nanoTime()
        val ok = request("POST", "/api/v1/playback", envelope.toJson(), true) in 200..299
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        return ok to elapsedMs
    }

    /// 把状态码也带出来的发送(2026-09-17)。
    ///
    /// 为什么要多回一个码:**401 和"网络不通"是两种完全不同的故障**,用户要做的事也完全不同
    /// —— 前者要回 Mac 重新配对,后者要检查 Wi-Fi。只回一个布尔的话,界面上只能笼统说"失败",
    /// 用户不知道该修哪儿,而这正是"手机的逻辑看着有问题"的一部分。
    ///
    /// 状态码 -1 = 连不上(见 request 的 catch),0 = 压根没发(没有令牌)。
    fun sendDetailed(envelope: PlaybackEnvelope): SendOutcome {
        if (token.isBlank()) return SendOutcome(false, 0L, 0)
        val started = System.nanoTime()
        val code = request("POST", "/api/v1/playback", envelope.toJson(), true)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        return SendOutcome(code in 200..299, elapsedMs, code)
    }

    private fun request(method: String, path: String, body: String?, authenticated: Boolean): Int = try {
        val connection = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 1_500
        connection.readTimeout = 1_500
        connection.setRequestProperty("Accept", "application/json")
        if (authenticated) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        connection.disconnect()
        code
    } catch (_: Exception) { -1 }
}
