package com.phonlyrics.relay

import java.net.HttpURLConnection
import java.net.URL

/// 一次发送的结果。`code` 是 HTTP 状态码,-1 表示连不上、0 表示没有令牌压根没发。
/// 带上它是因为 401(令牌失效)和 -1(网络不通)要引到完全不同的处置上,见 sendDetailed 的注释。
/// Mac 回给手机的歌词(2026-09-19)。
///
/// ⚠️ 这份数据**不落盘、不进日志**:它只是"此刻屏幕该显示什么",一秒后就被下一条覆盖。
data class LyricPayload(
    val current: String?,
    val next: String?,
    val translation: String?,
    val romanization: String?,
    val title: String?,
    val artist: String?,
) {
    /// 副行:优先译文,没有则罗马音。两者都没有时 nil。
    val secondary: String?
        get() = translation?.takeIf { it.isNotBlank() }
            ?: romanization?.takeIf { it.isNotBlank() }
}

data class SendOutcome(
    val ok: Boolean,
    val latencyMs: Long,
    val code: Int,
    /// Mac 在响应里带回的当前歌词。null = 这次没带(没在播 / 这首歌没词)。
    val lyric: LyricPayload? = null,
) {
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
        // 这次要**读响应体**:Mac 会把当前歌词搭在响应里带回来(2026-09-19),
        // 手机据此显示 —— 歌词解析是 Mac 的活,手机不重复实现一遍。
        val (code, body) = requestWithBody("POST", "/api/v1/playback", envelope.toJson(), true)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        return SendOutcome(code in 200..299, elapsedMs, code, parseLyric(body))
    }

    /// 从响应体里取歌词。解析失败一律当"这次没带",不抛 —— 它是附加信息,
    /// 拿不到不该影响同步本身(那条链路才是本职)。
    ///
    /// ⚠️ 可见性是 internal 而不是 private,只为让单元测试够得着:这是**两端之间的线格式**,
    /// 一边改字段名、另一边还在读旧名,两边各自的测试都会过、线上却一直不显示歌词。
    /// 那种故障查起来最费劲,所以这一层必须能被直接测。
    internal fun parseLyric(body: String?): LyricPayload? {
        if (body.isNullOrBlank()) return null
        return try {
            val json = org.json.JSONObject(body).optJSONObject("lyric") ?: return null
            val current = json.optString("current").takeIf { it.isNotBlank() }
            if (current == null) return null   // 没有当前行就没有可显示的,整份丢掉
            LyricPayload(
                current = current,
                next = json.optString("next").takeIf { it.isNotBlank() },
                translation = json.optString("translation").takeIf { it.isNotBlank() },
                romanization = json.optString("romanization").takeIf { it.isNotBlank() },
                title = json.optString("title").takeIf { it.isNotBlank() },
                artist = json.optString("artist").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) { null }
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

    /// 跟 request 一样,但把响应体也带回来。只有需要读响应的地方才用它 ——
    /// 读体会多一次流分配,心跳那种高频调用不该白花这个成本。
    private fun requestWithBody(method: String, path: String, body: String?,
                                authenticated: Boolean): Pair<Int, String?> = try {
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
        val text = connection.inputStream?.bufferedReader()?.use { it.readText() }
        connection.disconnect()
        code to text
    } catch (_: Exception) { -1 to null }
}
