package com.phonlyrics.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private var running = false

    private val macIp by lazy { findViewById<EditText>(R.id.macIp) }
    private val macPort by lazy { findViewById<EditText>(R.id.macPort) }
    private val toggle by lazy { findViewById<Button>(R.id.toggle) }
    private val statusPill by lazy { findViewById<TextView>(R.id.statusPill) }
    private val detail by lazy { findViewById<TextView>(R.id.detail) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createChannel()

        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        macIp.setText(prefs.getString("ip", "172.20.10.2"))
        macPort.setText(prefs.getInt("port", 8765).toString())
        running = prefs.getBoolean("running", false)
        refreshUi()

        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                Toast.makeText(this, "电池优化已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        toggle.setOnClickListener {
            if (running) {
                stopService(Intent(this, RelayService::class.java))
                running = false
                prefs.edit().putBoolean("running", false).apply()
                refreshUi()
            } else {
                val ip = macIp.text.toString().trim()
                val port = macPort.text.toString().toIntOrNull() ?: 8765
                if (ip.isEmpty()) {
                    Toast.makeText(this, "请填写 Mac IP", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("ip", ip).putInt("port", port).putBoolean("running", true).apply()
                val intent = Intent(this, RelayService::class.java).apply {
                    putExtra(RelayService.EXTRA_IP, ip)
                    putExtra(RelayService.EXTRA_PORT, port)
                }
                startForegroundService(intent)
                running = true
                refreshUi()
            }
        }
    }

    private fun refreshUi() {
        val enabled = isNotificationListenerEnabled()
        if (running) {
            statusPill.text = "运行中"
            statusPill.setTextColor(0xFF3DD68C.toInt())
            toggle.text = "停止中继"
            detail.text = buildString {
                append("推送 http://${macIp.text}:${macPort.text}/api/now-playing\n")
                append(if (enabled) "通知使用权：已开启" else "通知使用权：未开启（读不到播放信息）")
            }
        } else {
            statusPill.text = "未启动"
            statusPill.setTextColor(0xFFFF7B72.toInt())
            toggle.text = "启动中继"
            detail.text = if (enabled) "通知使用权：已开启" else "请先开启通知使用权，再启动中继"
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, NotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun testConnection() {
        val ip = macIp.text.toString().trim()
        val port = macPort.text.toString().toIntOrNull() ?: 8765
        detail.text = "测试中…"
        Thread {
            val result = try {
                val url = URL("http://$ip:$port/api/status")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                val code = conn.responseCode
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                if (code == 200 && json.optBoolean("ok")) "连接成功" else "连接失败 HTTP $code"
            } catch (e: Exception) {
                "连接失败：${e.message}"
            }
            runOnUiThread {
                detail.text = result
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("relay", "歌词中继", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
