package com.phonlyrics.relay

import android.Manifest
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

class MainActivity : AppCompatActivity() {
    private var running = false
    private var selectedMac: DiscoveredMac? = null
    private lateinit var discovery: MacDiscoveryManager
    private val macIp by lazy { findViewById<EditText>(R.id.macIp) }
    private val macPort by lazy { findViewById<EditText>(R.id.macPort) }
    private val pairingCode by lazy { findViewById<EditText>(R.id.pairingCode) }
    private val toggle by lazy { findViewById<Button>(R.id.toggle) }
    private val statusPill by lazy { findViewById<TextView>(R.id.statusPill) }
    private val discoveryStatus by lazy { findViewById<TextView>(R.id.discoveryStatus) }
    private val detail by lazy { findViewById<TextView>(R.id.detail) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createChannel()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        macIp.setText(prefs.getString("ip", ""))
        macPort.setText(prefs.getInt("port", 8765).toString())
        running = prefs.getBoolean("running", false)

        discovery = MacDiscoveryManager(this, { mac -> runOnUiThread {
            selectedMac = mac
            macIp.setText(mac.host)
            macPort.setText(mac.port.toString())
            discoveryStatus.text = "已发现 ${mac.name} · ${mac.host}:${mac.port}"
            if (mac.pairing) discoveryStatus.append(" · 等待配对")
        } }, { message -> runOnUiThread { discoveryStatus.text = "$message，请手动填写地址" } })
        discovery.start()

        findViewById<Button>(R.id.btnPair).setOnClickListener { pair() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else Toast.makeText(this, "电池优化已关闭", Toast.LENGTH_SHORT).show()
        }
        toggle.setOnClickListener { if (running) stopRelay() else startRelay() }
        refreshUi()
    }

    override fun onResume() { super.onResume(); refreshUi() }
    override fun onDestroy() { discovery.stop(); super.onDestroy() }

    private fun pair() {
        val host = macIp.text.toString().trim()
        val port = macPort.text.toString().toIntOrNull() ?: 8765
        val code = pairingCode.text.toString().trim()
        if (host.isEmpty() || !code.matches(Regex("\\d{6}"))) {
            toast("请选择 Mac 并输入 6 位配对码"); return
        }
        detail.text = "正在配对…"
        Thread {
            val prefs = getSharedPreferences("relay", MODE_PRIVATE)
            val deviceId = prefs.getString("deviceId", null) ?: Settings.Secure.getString(
                contentResolver, Settings.Secure.ANDROID_ID).orEmpty().ifBlank { java.util.UUID.randomUUID().toString() }
            prefs.edit().putString("deviceId", deviceId).apply()
            val result = PairingClient(this).pair(host, port, code, deviceId, Build.MODEL.ifBlank { "Android" })
            runOnUiThread {
                if (result.isSuccess) {
                    prefs.edit().putString("ip", host).putInt("port", port)
                        .putString("macStableId", selectedMac?.stableId).apply()
                    pairingCode.setText("")
                    detail.text = "配对成功，以后会自动重连"
                    toast("配对成功")
                } else {
                    detail.text = result.exceptionOrNull()?.message ?: "配对失败"
                    toast("配对失败")
                }
                refreshUi()
            }
        }.start()
    }

    private fun startRelay() {
        val host = macIp.text.toString().trim()
        val port = macPort.text.toString().toIntOrNull() ?: 8765
        if (host.isEmpty()) { toast("请先自动发现或手动填写 Mac 地址"); return }
        if (SecureTokenStore(this).load().isNullOrBlank()) { toast("请先与 Mac 配对"); return }
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        prefs.edit().putString("ip", host).putInt("port", port).putBoolean("running", true).apply()
        startForegroundService(Intent(this, RelayService::class.java).apply {
            putExtra(RelayService.EXTRA_IP, host); putExtra(RelayService.EXTRA_PORT, port)
        })
        running = true
        refreshUi()
    }

    private fun stopRelay() {
        stopService(Intent(this, RelayService::class.java))
        getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        running = false
        refreshUi()
    }

    private fun refreshUi() {
        val enabled = isNotificationListenerEnabled()
        val paired = !SecureTokenStore(this).load().isNullOrBlank()
        statusPill.text = when { running -> "运行中"; paired -> "已配对"; else -> "未配对" }
        statusPill.setTextColor(if (running || paired) 0xFF3DD68C.toInt() else 0xFFFF7B72.toInt())
        toggle.text = if (running) "停止同步" else "开始同步"
        detail.text = buildString {
            append(if (paired) "Mac 令牌：已安全保存" else "Mac 令牌：未配对")
            append("\n")
            append(if (enabled) "通知使用权：已开启" else "通知使用权：未开启（读不到 QQ 音乐）")
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, NotificationListener::class.java)
        return Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(cn.flattenToString()) == true
    }

    private fun testConnection() {
        val client = RelayApiClient(macIp.text.toString().trim(), macPort.text.toString().toIntOrNull() ?: 8765, "")
        detail.text = "测试中…"
        Thread {
            val ok = client.health()
            runOnUiThread { detail.text = if (ok) "连接成功" else "连接失败"; toast(detail.text.toString()) }
        }.start()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("relay", "手机歌词同步", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
