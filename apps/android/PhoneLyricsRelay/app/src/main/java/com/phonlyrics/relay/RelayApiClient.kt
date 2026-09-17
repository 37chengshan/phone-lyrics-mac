package com.phonlyrics.relay

import java.net.HttpURLConnection
import java.net.URL

class RelayApiClient(@Volatile var host: String, @Volatile var port: Int, @Volatile var token: String) {
    fun health(): Boolean = request("GET", "/api/v1/health", null, false) in 200..299
    fun send(envelope: PlaybackEnvelope): Boolean {
        if (token.isBlank()) return false
        return request("POST", "/api/v1/playback", envelope.toJson(), true) in 200..299
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
