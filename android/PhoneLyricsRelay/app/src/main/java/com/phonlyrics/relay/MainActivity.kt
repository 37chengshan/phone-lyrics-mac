package com.phonlyrics.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val macIp = findViewById<EditText>(R.id.macIp)
        val macPort = findViewById<EditText>(R.id.macPort)
        val toggle = findViewById<Button>(R.id.toggle)
        val status = findViewById<TextView>(R.id.status)

        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        macIp.setText(prefs.getString("ip", "172.20.10.2"))
        macPort.setText(prefs.getInt("port", 8765).toString())

        createChannel()

        toggle.setOnClickListener {
            if (running) {
                stopService(Intent(this, RelayService::class.java))
                running = false
                toggle.text = "启动中继"
                status.text = "已停止"
                return@setOnClickListener
            }
            val ip = macIp.text.toString().trim()
            val port = macPort.text.toString().toIntOrNull() ?: 8765
            if (ip.isEmpty()) {
                status.text = "请填写 Mac IP"
                return@setOnClickListener
            }
            prefs.edit().putString("ip", ip).putInt("port", port).apply()
            val intent = Intent(this, RelayService::class.java).apply {
                putExtra(RelayService.EXTRA_IP, ip)
                putExtra(RelayService.EXTRA_PORT, port)
            }
            startForegroundService(intent)
            running = true
            toggle.text = "停止中继"
            status.text = "推送中 → http://$ip:$port/api/now-playing"
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    "relay",
                    "歌词中继",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
