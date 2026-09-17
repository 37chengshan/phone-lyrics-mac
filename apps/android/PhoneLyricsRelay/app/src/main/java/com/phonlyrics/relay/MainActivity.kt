package com.phonlyrics.relay

import android.Manifest
import android.animation.ObjectAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private enum class Tab { STATUS, CONNECT, PERMS }

    private var running = false
    private var selectedMac: DiscoveredMac? = null
    private var currentTab = Tab.STATUS
    private lateinit var discovery: MacDiscoveryManager

    private val macIp by lazy { findViewById<EditText>(R.id.macIp) }
    private val macPort by lazy { findViewById<EditText>(R.id.macPort) }
    private val pairingCode by lazy { findViewById<EditText>(R.id.pairingCode) }
    private val toggle by lazy { findViewById<Button>(R.id.toggle) }
    private val statusPill by lazy { findViewById<TextView>(R.id.statusPill) }
    private val headerDetail by lazy { findViewById<TextView>(R.id.headerDetail) }
    private val discoveryStatus by lazy { findViewById<TextView>(R.id.discoveryStatus) }
    private val pairState by lazy { findViewById<TextView>(R.id.pairState) }
    private val pairResult by lazy { findViewById<TextView>(R.id.pairResult) }
    private val syncState by lazy { findViewById<TextView>(R.id.syncState) }
    private val syncHint by lazy { findViewById<TextView>(R.id.syncHint) }
    private val nowTitle by lazy { findViewById<TextView>(R.id.nowTitle) }
    private val nowArtist by lazy { findViewById<TextView>(R.id.nowArtist) }
    private val statusLog by lazy { findViewById<TextView>(R.id.statusLog) }
    private val permNotifState by lazy { findViewById<TextView>(R.id.permNotifState) }
    private val permBatteryState by lazy { findViewById<TextView>(R.id.permBatteryState) }
    private val diagText by lazy { findViewById<TextView>(R.id.diagText) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyEdgeToEdgeInsets()
        RelayLog.install(this)
        createChannel()
        requestNotificationPermissionIfNeeded()

        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        macIp.setText(prefs.getString("ip", ""))
        macPort.setText(prefs.getInt("port", 8765).toString())
        running = prefs.getBoolean("running", false)

        discovery = MacDiscoveryManager(this, { mac -> runOnUiThread {
            selectedMac = mac
            macIp.setText(mac.host)
            macPort.setText(mac.port.toString())
            discoveryStatus.text = "已发现 ${mac.name}\n${mac.host}:${mac.port}" +
                if (mac.pairing) "\nMac 正在等待配对码" else ""
            // 发现到 Mac 之后自动跳到「连接」页的下一步,省得用户自己找。
            if (!isPaired()) switchTab(Tab.CONNECT)
        } }, { message -> runOnUiThread {
            discoveryStatus.text = "$message\n可在下面手动填写地址"
        } })
        discovery.start()

        findViewById<TextView>(R.id.tabStatus).setOnClickListener { switchTab(Tab.STATUS) }
        findViewById<TextView>(R.id.tabConnect).setOnClickListener { switchTab(Tab.CONNECT) }
        findViewById<TextView>(R.id.tabPerms).setOnClickListener { switchTab(Tab.PERMS) }
        findViewById<Button>(R.id.btnPair).setOnClickListener { pair() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        toggle.setOnClickListener { if (running) stopRelay() else startRelay() }
        switchTab(Tab.STATUS)
        refreshUi()
    }

    override fun onResume() { super.onResume(); refreshUi() }

    override fun onDestroy() { discovery.stop(); super.onDestroy() }

    /// 内容让位给状态栏/挖孔/手势条。
    ///
    /// targetSdk 35 在 Android 15 上强制 edge-to-edge,不处理的话顶部内容会画到状态栏底下
    /// —— 深色主题配深色状态栏正好糊成一团(用户实测「UI 被上边栏挡住」)。这里直接用
    /// WindowInsets 给根布局加 padding,刘海/挖孔/三种手势条机型都能正确让位。
    private fun applyEdgeToEdgeInsets() {
        val root = findViewById<View>(R.id.root)
        val basePaddingTop = root.paddingTop
        val basePaddingBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, basePaddingTop + bars.top,
                view.paddingRight, basePaddingBottom + bars.bottom)
            insets
        }
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        val pages = mapOf(
            Tab.STATUS to findViewById<View>(R.id.pageStatus),
            Tab.CONNECT to findViewById<View>(R.id.pageConnect),
            Tab.PERMS to findViewById<View>(R.id.pagePerms),
        )
        val tabs = mapOf(
            Tab.STATUS to findViewById<TextView>(R.id.tabStatus),
            Tab.CONNECT to findViewById<TextView>(R.id.tabConnect),
            Tab.PERMS to findViewById<TextView>(R.id.tabPerms),
        )
        pages.forEach { (key, page) ->
            val active = key == tab
            if (active && page.visibility != View.VISIBLE) {
                page.alpha = 0f
                page.visibility = View.VISIBLE
                ObjectAnimator.ofFloat(page, View.ALPHA, 0f, 1f).setDuration(140).start()
            } else if (!active) {
                page.visibility = View.GONE
            }
        }
        tabs.forEach { (key, label) ->
            val active = key == tab
            label.isSelected = active
            label.setTextColor(if (active) 0xFF0B0D11.toInt() else 0xFF8B93A1.toInt())
        }
    }

    private fun pair() {
        val host = macIp.text.toString().trim()
        val port = macPort.text.toString().toIntOrNull() ?: 8765
        val code = pairingCode.text.toString().trim()
        if (host.isEmpty() || !code.matches(Regex("\\d{6}"))) {
            showPairResult("请先选好 Mac 地址,并输入 Mac 上显示的 6 位配对码", ok = false)
            return
        }
        val button = findViewById<Button>(R.id.btnPair)
        setBusy(button, true, "配对中…")
        showPairResult("正在和 $host:$port 配对…", ok = null)
        Thread {
            val prefs = getSharedPreferences("relay", MODE_PRIVATE)
            val deviceId = prefs.getString("deviceId", null) ?: Settings.Secure.getString(
                contentResolver, Settings.Secure.ANDROID_ID).orEmpty().ifBlank { java.util.UUID.randomUUID().toString() }
            prefs.edit().putString("deviceId", deviceId).apply()
            val result = PairingClient(this).pair(host, port, code, deviceId, Build.MODEL.ifBlank { "Android" })
            runOnUiThread {
                setBusy(button, false, "配对并保存")
                if (result.isSuccess) {
                    prefs.edit().putString("ip", host).putInt("port", port)
                        .putString("macStableId", selectedMac?.stableId).apply()
                    pairingCode.setText("")
                    // ⚠️ 这里**不能**再调 refreshUi():它会重写 pairState / statusLog,把刚写下的
                    // 结果盖掉 —— 上一版就是"配对成功了但界面看着没变化",用户实测报的就是这个。
                    // 现在配对结果写在**自己的**那一行(pairResult),refreshUi 不碰它。
                    showPairResult("配对成功,令牌已存进系统安全存储\n以后同一 Wi-Fi 下会自动重连", ok = true)
                    RelayLog.note("pair ok: $host:$port")
                    refreshUi()
                } else {
                    val reason = result.exceptionOrNull()?.message ?: "配对失败"
                    showPairResult("配对失败:$reason\n确认 Mac 上那个 6 位码还在有效期内(5 分钟),且地址可达", ok = false)
                    RelayLog.note("pair failed: $reason")
                    refreshUi()
                }
            }
        }.start()
    }

    private fun showPairResult(text: String, ok: Boolean?) {
        pairResult.visibility = View.VISIBLE
        pairResult.text = text
        pairResult.setTextColor(when (ok) {
            true -> 0xFF3DD68C.toInt()
            false -> 0xFFFF7B72.toInt()
            null -> 0xFF8B93A1.toInt()
        })
    }

    /// 按钮的"正在忙"态。原版配对和测试都是瞬间没反馈,用户分不清点没点上。
    private fun setBusy(button: Button, busy: Boolean, labelWhenDone: String) {
        button.isEnabled = !busy
        button.text = if (busy) "处理中…" else labelWhenDone
        button.alpha = if (busy) 0.6f else 1f
    }

    private fun startRelay() {
        val host = macIp.text.toString().trim()
        val port = macPort.text.toString().toIntOrNull() ?: 8765
        if (host.isEmpty()) { toast("请先自动发现或手动填写 Mac 地址"); switchTab(Tab.CONNECT); return }
        if (!isPaired()) { toast("请先与 Mac 配对"); switchTab(Tab.CONNECT); return }
        getSharedPreferences("relay", MODE_PRIVATE).edit()
            .putString("ip", host).putInt("port", port).putBoolean("running", true).apply()
        // ⚠️ 服务启动失败(比如权限问题)会抛异常,必须接住:否则 Activity 自己崩,
        // 表现成"点开始同步就闪退"。
        try {
            startForegroundService(Intent(this, RelayService::class.java).apply {
                putExtra(RelayService.EXTRA_IP, host)
                putExtra(RelayService.EXTRA_PORT, port)
            })
            running = true
            RelayLog.note("relay start requested -> $host:$port")
        } catch (error: Exception) {
            running = false
            getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("running", false).apply()
            RelayLog.note("relay start FAILED: ${error.javaClass.simpleName}: ${error.message}")
            toast("启动失败:${error.message ?: error.javaClass.simpleName}")
        }
        refreshUi()
    }

    private fun stopRelay() {
        stopService(Intent(this, RelayService::class.java))
        getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        running = false
        RelayLog.note("relay stopped")
        refreshUi()
    }

    private fun refreshUi() {
        val notifEnabled = isNotificationListenerEnabled()
        val paired = isPaired()
        val batteryOk = isBatteryExempt()
        // "running" 以服务自己的心跳为准,不只信 SharedPreferences —— 进程被杀之后
        // 那个布尔还是 true,界面会一直显示"运行中"而实际早就没了。
        val serviceAlive = RelayLog.isServiceAlive()
        if (running && !serviceAlive) {
            running = false
            getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        }

        statusPill.text = when {
            running -> "运行中"
            paired -> "已配对"
            else -> "未配对"
        }
        statusPill.setTextColor(if (running || paired) 0xFF3DD68C.toInt() else 0xFFFF7B72.toInt())
        headerDetail.text = when {
            running -> "正在同步到 ${macIp.text}:${macPort.text}"
            paired -> "已配对,点「开始同步」开始"
            else -> "尚未连接 Mac"
        }
        toggle.text = if (running) "停止同步" else "开始同步"

        syncState.text = when {
            running -> "同步已开启"
            paired -> "就绪,等待开始"
            else -> "同步未启动"
        }
        syncHint.text = when {
            running && !notifEnabled -> "但没有通知使用权,读不到 QQ 音乐 —— 去「权限」页开启"
            running -> "换首歌看看,Mac 上应该会跟着显示歌词"
            paired -> "点下面的按钮开始把播放状态推到 Mac"
            else -> "先到「连接」页和 Mac 配对"
        }

        pairState.text = if (paired) "已配对" else "尚未配对"
        pairState.setTextColor(if (paired) 0xFF3DD68C.toInt() else 0xFFFF7B72.toInt())
        permNotifState.text = "通知使用权:${if (notifEnabled) "已开启" else "未开启"}"
        permNotifState.setTextColor(if (notifEnabled) 0xFF3DD68C.toInt() else 0xFFFF7B72.toInt())
        permBatteryState.text = "电池优化:${if (batteryOk) "已关闭" else "未关闭"}"
        permBatteryState.setTextColor(if (batteryOk) 0xFF3DD68C.toInt() else 0xFFFF7B72.toInt())

        // ⚠️ buildString 的 receiver 是 StringBuilder,里面写裸 `this` 指的是它、不是 Activity。
        // 先把 context 取出来再拼,避免踩这个。
        val activityContext = this
        diagText.text = buildString {
            append("地址:${macIp.text.ifBlank { "未填" }}:${macPort.text}\n")
            append("配对:${if (paired) "已保存令牌" else "无"}\n")
            append("通知使用权:${if (notifEnabled) "开" else "关"}\n")
            append("电池优化:${if (batteryOk) "已关闭" else "未关闭"}\n")
            append("同步服务:${if (running) "运行中" else "未运行"}\n")
            append("Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})\n\n")
            // 最近事件。闪退之后再打开,这里就能看到原因(见 RelayLog 头注)——
            // 没有这张卡的时候,用户能拿到的唯一线索是"它闪了一下就没了"。
            append("最近记录:\n")
            append(RelayLog.tail(activityContext, 10))
        }
    }

    private fun isPaired(): Boolean = !SecureTokenStore(this).load().isNullOrBlank()

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, NotificationListener::class.java)
        return Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(cn.flattenToString()) == true
    }

    private fun isBatteryExempt(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryExemption() {
        if (isBatteryExempt()) { toast("电池优化已经是关闭的") ; return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun testConnection() {
        val host = macIp.text.toString().trim()
        val port = macPort.text.toString().toIntOrNull() ?: 8765
        if (host.isEmpty()) { toast("请先填写 Mac 地址"); return }
        val button = findViewById<Button>(R.id.btnTest)
        setBusy(button, true, "测试连接")
        discoveryStatus.text = "正在测试 $host:$port …"
        Thread {
            val ok = RelayApiClient(host, port, "").health()
            runOnUiThread {
                setBusy(button, false, "测试连接")
                discoveryStatus.text = if (ok) {
                    "$host:$port 可达(Mac 端在正常监听)"
                } else {
                    "$host:$port 连不上\n确认两台设备同一个 Wi-Fi、Mac 上 Lyrimuse 在运行"
                }
                discoveryStatus.setTextColor(if (ok) 0xFF3DD68C.toInt() else 0xFFFF7B72.toInt())
            }
        }.start()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("relay", "手机歌词同步", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
