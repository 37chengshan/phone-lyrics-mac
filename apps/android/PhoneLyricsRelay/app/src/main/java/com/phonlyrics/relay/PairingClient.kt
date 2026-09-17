package com.phonlyrics.relay

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PairingResult(val token: String, val deviceId: String)

class PairingClient(private val context: Context) {
    fun pair(host: String, port: Int, code: String, deviceId: String, deviceName: String): Result<PairingResult> = runCatching {
        require(code.matches(Regex("\\d{6}"))) { "配对码必须是 6 位数字" }
        val body = JSONObject().put("code", code).put("deviceId", deviceId).put("deviceName", deviceName).toString()
        val connection = URL("http://$host:$port/api/v1/pair").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val codeValue = connection.responseCode
        val response = (if (codeValue in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (codeValue !in 200..299) error("配对失败 HTTP $codeValue")
        val json = JSONObject(response)
        val token = json.getString("token")
        require(token.length == 64) { "Mac 返回了无效令牌" }
        SecureTokenStore(context).save(token)
        PairingResult(token, json.getString("deviceId"))
    }
}
