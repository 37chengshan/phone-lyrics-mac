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
    private enum class Tab { STATUS, LYRICS, STATS, SETTINGS }

    private var running = false
    private var selectedMac: DiscoveredMac? = null
    private var currentTab = Tab.STATUS
    /// 最近一次完整刷新读到的两项"贵"状态(配对、通知使用权)。每秒那条轻量刷新路径复用它们,
    /// 自己不去读 —— 前者要开 AndroidKeyStore 解密、后者是跨进程查询(见 uiTick 的注释)。
    private var lastKnownPaired = false
    private var lastKnownNotifEnabled = false
    private lateinit var discovery: MacDiscoveryManager
    /// 系统级"减弱动态效果"开关。开着时不做淡入/缩放动画 —— 这不是可选的礼貌:设置里有这个
    /// 开关的人,往往是因为动画会引发不适,给他们照常播动画比"界面朴素一点"糟糕得多。
    /// 读系统的 `animator_duration_scale`,它正是开发者选项与无障碍设置共同写的那一个。
    private val reduceMotion: Boolean by lazy {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    /// 每秒把服务发布的曲目状态拉进界面。见 refreshUi 里读 nowPlaying 的那一段。
    private val uiTicker = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiTick = object : Runnable {
        override fun run() {
            // ⚠️ 每秒这一拍**只**刷会变的那几项(曲目、心跳),不整个 refreshUi()。
            //
            // 2026-09-17 审查发现:第一版每秒调的是完整 refreshUi(),而它里面有三件**很贵**
            // 的事 —— isPaired() 要开 AndroidKeyStore 做一次 AES-GCM 解密、isNotificationListener
            // Enabled() 要读 Settings.Secure(跨进程 ContentProvider 查询)、RelayLog.tail() 要读写
            // 文件。每秒在主线程做一遍这些,是给界面加了一次每秒一次的卡顿;而它们的结果其实
            // 只在用户去系统设置改过之后才会变,不需要按秒盯。
            //
            // 拆法:这一拍只做"读 SharedPreferences 里那几个值 + 比较 + 必要时改文字",全是内存
            // 操作;完整刷新仍走 refreshUi()(onResume、以及每次用户操作之后),那条路径本来就
            // 覆盖了"用户刚从系统设置回来"的时机。
            refreshLiveValues()
            uiTicker.postDelayed(this, 1_000)
        }
    }

    private val macIp by lazy { findViewById<EditText>(R.id.macIp) }
    private val macPort by lazy { findViewById<EditText>(R.id.macPort) }
    private val pairingCode by lazy { findViewById<EditText>(R.id.pairingCode) }
    // 2026-09-19:顶部那条常驻状态胶囊撤了 —— 新导航是四页结构,常驻头会跟页面标题抢位置。
    // 它原来的两个信息(连接状态、当前曲目)分别落在状态页的两张卡上;同步开关挪到了右下那颗圆钮。
    private val syncFab by lazy { findViewById<android.widget.ImageButton>(R.id.syncFab) }
    private val discoveryStatus by lazy { findViewById<TextView>(R.id.discoveryStatus) }
    private val pairState by lazy { findViewById<TextView>(R.id.pairState) }
    private val pairResult by lazy { findViewById<TextView>(R.id.pairResult) }
    private val btnUnpair by lazy { findViewById<Button>(R.id.btnUnpair) }
    private val syncState by lazy { findViewById<TextView>(R.id.syncState) }
    private val syncHint by lazy { findViewById<TextView>(R.id.syncHint) }
    private val nowTitle by lazy { findViewById<TextView>(R.id.nowTitle) }
    private val nowArtist by lazy { findViewById<TextView>(R.id.nowArtist) }
    // 链路统计四格(2026-09-17,用户要求"仪表盘")。见 applyDashboard 的注释。
    private val statSent by lazy { findViewById<TextView>(R.id.statSent) }
    private val statFailed by lazy { findViewById<TextView>(R.id.statFailed) }
    private val statLatency by lazy { findViewById<TextView>(R.id.statLatency) }
    private val statLast by lazy { findViewById<TextView>(R.id.statLast) }
    private val statBar by lazy { findViewById<android.widget.ProgressBar>(R.id.statBar) }
    private val statHint by lazy { findViewById<TextView>(R.id.statHint) }
    private val permNotifState by lazy { findViewById<TextView>(R.id.permNotifState) }
    private val permBatteryState by lazy { findViewById<TextView>(R.id.permBatteryState) }
    private val diagText by lazy { findViewById<TextView>(R.id.diagText) }

    // ── 歌词页(2026-09-19) ──
    private val lyricTrack by lazy { findViewById<TextView>(R.id.lyricTrack) }
    private val lyricPrev by lazy { findViewById<TextView>(R.id.lyricPrev) }
    private val lyricCurrent by lazy { findViewById<TextView>(R.id.lyricCurrent) }
    private val lyricNext by lazy { findViewById<TextView>(R.id.lyricNext) }
    private val lyricHint by lazy { findViewById<TextView>(R.id.lyricHint) }

    // ── 统计页(2026-09-19) ──
    private val statsTotal by lazy { findViewById<TextView>(R.id.statsTotal) }
    private val statsTracks by lazy { findViewById<TextView>(R.id.statsTracks) }
    private val statsAvg by lazy { findViewById<TextView>(R.id.statsAvg) }
    private val statsChart by lazy { findViewById<android.widget.LinearLayout>(R.id.statsChart) }
    private val statsChartEmpty by lazy { findViewById<TextView>(R.id.statsChartEmpty) }
    private val statsTopList by lazy { findViewById<android.widget.LinearLayout>(R.id.statsTopList) }
    private val statsTrend by lazy { findViewById<TextView>(R.id.statsTrend) }
    private val statsStreak by lazy { findViewById<TextView>(R.id.statsStreak) }
    private val statsHourChart by lazy { findViewById<android.widget.LinearLayout>(R.id.statsHourChart) }
    private val statsArtistList by lazy { findViewById<android.widget.LinearLayout>(R.id.statsArtistList) }
    private val rangeToday by lazy { findViewById<TextView>(R.id.rangeToday) }
    private val rangeWeek by lazy { findViewById<TextView>(R.id.rangeWeek) }
    private val rangeMonth by lazy { findViewById<TextView>(R.id.rangeMonth) }
    private val rangeAll by lazy { findViewById<TextView>(R.id.rangeAll) }

    /// 统计页当前看的区间(天)。0 = 全部。默认今天。
    private var statsRangeDays = 1

    /// 统计页正在跑的动画(数字滚动、柱子长高、进度条推进)。换区间/重进这一页时要先全部
    /// `cancel` —— 不取消的话旧动画会继续往已经重排过的 View 上写值,表现成数字乱跳。
    private val statsAnimators = mutableListOf<android.animation.Animator>()

    /// 图表上那个数字气泡(同一时刻最多一个)。
    private var chartTip: TextView? = null
    private var chartTipHide: Runnable? = null

    /// 导航胶囊里那块跟着选的滑块。见 updateNavIndicator。
    private val navIndicator by lazy { findViewById<View>(R.id.navIndicator) }
    /// navRow:四格的容器。换算滑块坐标要用它的 left,见 updateNavIndicator。
    private val navRow by lazy { findViewById<View>(R.id.navRow) }
    /// 四格(与 switchTab 里的 items 同序),算滑块位置要用它们的实际宽度。
    private val navCells by lazy {
        listOf(
            findViewById<View>(R.id.navStatus),
            findViewById<View>(R.id.navLyrics),
            findViewById<View>(R.id.navStats),
            findViewById<View>(R.id.navSettings),
        )
    }

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
            // 发现到 Mac 之后自动跳到设置页的下一步(地址与配对都在那一页),省得用户自己找。
            if (!isPaired()) switchTab(Tab.SETTINGS)
        } }, { message -> runOnUiThread {
            discoveryStatus.text = "$message\n可在下面手动填写地址"
        } })
        discovery.start()

        // 导航四项(2026-09-19)。整格可点,不只是图标 —— 胶囊里的格子本来就不大,
        // 只让图标可点会让命中区比看上去小一圈。
        findViewById<View>(R.id.navStatus).setOnClickListener { switchTab(Tab.STATUS) }
        findViewById<View>(R.id.navLyrics).setOnClickListener { switchTab(Tab.LYRICS) }
        findViewById<View>(R.id.navStats).setOnClickListener { switchTab(Tab.STATS) }
        findViewById<View>(R.id.navSettings).setOnClickListener { switchTab(Tab.SETTINGS) }
        findViewById<Button>(R.id.btnPair).setOnClickListener { pair() }
        btnUnpair.setOnClickListener { confirmUnpair() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        // 同步开关:右下那颗悬浮圆钮。点一下开始/停止,图标跟着换(见 applySyncFab)。
        syncFab.setOnClickListener { if (running) stopRelay() else startRelay() }
        // 统计区间切换
        rangeToday.setOnClickListener { selectStatsRange(1) }
        rangeWeek.setOnClickListener { selectStatsRange(7) }
        rangeMonth.setOnClickListener { selectStatsRange(30) }
        rangeAll.setOnClickListener { selectStatsRange(0) }
        switchTab(Tab.STATUS)
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        // 服务把曲目写在 SharedPreferences 里、不会主动推给界面(见 RelayLog.publishNowPlaying
        // 的注释),所以这里自己按秒拉。只在可见时跑 —— 退到后台就该停,不该为了一个没人看的
        // 数字每秒唤醒一次主线程。
        uiTicker.removeCallbacks(uiTick)
        uiTicker.postDelayed(uiTick, 1_000)
    }

    override fun onPause() {
        uiTicker.removeCallbacks(uiTick)
        super.onPause()
    }

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
            Tab.LYRICS to findViewById<View>(R.id.pageLyrics),
            Tab.STATS to findViewById<View>(R.id.pageStats),
            Tab.SETTINGS to findViewById<View>(R.id.pageSettings),
        )
        pages.forEach { (key, page) ->
            val active = key == tab
            if (active && page.visibility != View.VISIBLE) {
                page.alpha = 0f
                page.visibility = View.VISIBLE
                if (reduceMotion) page.alpha = 1f
                else ObjectAnimator.ofFloat(page, View.ALPHA, 0f, 1f).setDuration(140).start()
            } else if (!active) {
                page.visibility = View.GONE
            }
        }

        // 选中态同时改三样:那一格的底、图标着色、标签颜色。
        //
        // ⚠️ 三样都要动。只改文字颜色的话,在深色胶囊里"当前在哪"不够一眼可见 ——
        // 这正是用户反复提的那个问题(Figma 稿里选中的那格有自己的浅底,不只是变色)。
        val items = mapOf(
            Tab.STATUS to Triple(R.id.navStatus, R.id.navStatusIcon, R.id.navStatusLabel),
            Tab.LYRICS to Triple(R.id.navLyrics, R.id.navLyricsIcon, R.id.navLyricsLabel),
            Tab.STATS to Triple(R.id.navStats, R.id.navStatsIcon, R.id.navStatsLabel),
            Tab.SETTINGS to Triple(R.id.navSettings, R.id.navSettingsIcon, R.id.navSettingsLabel),
        )
        items.forEach { (key, ids) ->
            val active = key == tab
            val (cell, icon, label) = ids
            val cellView = findViewById<View>(cell)
            val iconView = findViewById<android.widget.ImageView>(icon)
            val labelView = findViewById<TextView>(label)

            val wasActive = cellView.isSelected
            cellView.isSelected = active
            // 格子的底一律透明(2026-09-19):选中态改由独立的 navIndicator 滑过去表达,
            // 画在格子上就没有可插值的中间态 —— 那正是用户说的"要滑动特效"。
            cellView.background = androidx.core.content.ContextCompat.getDrawable(
                this, R.drawable.nav_item_inactive_bg)
            iconView.imageTintList = android.content.res.ColorStateList.valueOf(
                if (active) 0xFF7AA2FF.toInt() else 0xFF6B7484.toInt())
            labelView.setTextColor(if (active) 0xFFEEF1F5.toInt() else 0xFF6B7484.toInt())

            // 图标与文字跟着滑块一起"弹一下"(1.0 → 1.14 → 1.0):回弹那下才有"按进去了"
            // 的手感。只给**刚被选中**的那一格播 —— 全播的话另外三格会跟着抖,像在抢注意力。
            if (active && !wasActive && !reduceMotion) {
                iconView.scaleX = 1f
                iconView.scaleY = 1f
                iconView.animate()
                    .scaleX(1.14f).scaleY(1.14f).setDuration(110)
                    .withEndAction {
                        iconView.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                    }
                    .start()
                labelView.animate().alpha(0.55f).setDuration(90)
                    .withEndAction { labelView.animate().alpha(1f).setDuration(140).start() }
                    .start()
            }
        }

        updateNavIndicator()

        // 统计页是按需算的:切进去那一下算一次,不必每秒跟着曲目刷新跑。
        if (tab == Tab.STATS) renderStats()
    }


    /// 把滑块挪到当前选中那一格上(2026-09-19,用户要的"滑动特效")。
    ///
    /// x 决定"在哪一格",宽度收一点(76%)让滑块比格子窄、左右留白 —— 看起来像格子里垫着的
    /// 一块,而不是整格被涂满。
    ///
    /// ⚠️ 坐标要算对:滑块与 navRow 同在一个带 padding 的 FrameLayout 里,而 cell.left 是相对
    /// navRow 的 —— 必须加上 navRow 自己的 left 才换算成"相对 navPill"的坐标(这正是 View.x
    /// 的参考系)。漏掉这一项时滑块会整体偏左,在最后一格上尤其明显。
    ///
    /// ⚠️ 还要等布局量完(格子 width > 0)。第一次进来是在 onCreate 里调的,那时宽度还是 0,
    /// 算出来会把滑块摆在坐标原点。所以没量完时挂到 post 里再来一次 —— 这个分支只在启动那
    /// 一次走到,不影响之后切换的手感。
    private fun updateNavIndicator(animate: Boolean = true) {
        val index = when (currentTab) {
            Tab.STATUS -> 0
            Tab.LYRICS -> 1
            Tab.STATS -> 2
            Tab.SETTINGS -> 3
        }
        val cell = navCells[index]
        if (cell.width == 0) {
            cell.post { updateNavIndicator(animate = false) }
            return
        }
        val targetWidth = (cell.width * 0.76f).toInt()
        val targetX = navRow.left + cell.left + (cell.width - targetWidth) / 2f

        val lp = navIndicator.layoutParams
        if (lp.width != targetWidth) {
            lp.width = targetWidth
            navIndicator.layoutParams = lp
        }

        // 第一次(还没量过)直接落位:从屏幕外滑进来会像"有个东西飞过去了"。
        if (!animate || reduceMotion || navIndicator.width == 0) {
            navIndicator.x = targetX
            return
        }
        navIndicator.animate()
            .x(targetX)
            .setDuration(260L)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.2f, 1f))
            .start()
    }

    /// 解除配对(2026-09-17)。清掉令牌并停掉同步 —— 见调用点上方那段注释,原来没有这个出口。
    ///
    /// ⚠️ 要**二次确认**:令牌一清,用户必须回 Mac 上重新生成配对码才能再连,是个不可逆
    /// (准确说是"要重来一遍")的动作,误触的代价不小。
    private fun confirmUnpair() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("解除配对?")
            .setMessage("解除后需要回到 Mac 的「手机连接」页重新生成配对码,再配一次才能继续同步。")
            .setNegativeButton("取消", null)
            .setPositiveButton("解除") { _, _ -> unpair() }
            .show()
    }

    private fun unpair() {
        // 先停同步:否则服务还拿着旧令牌在发,解除完界面看着也还是"运行中"。
        if (running) stopRelay()
        SecureTokenStore(this).clear()
        getSharedPreferences("relay", MODE_PRIVATE).edit()
            .remove("pairedAtMs").remove("macStableId").apply()
        RelayLog.note("unpaired by user")
        pairResult.visibility = View.GONE
        pairingCode.setText("")
        refreshUi()
        toast("已解除配对")
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
                        .putLong("pairedAtMs", System.currentTimeMillis())
                        .putString("macStableId", selectedMac?.stableId).apply()
                    pairingCode.setText("")
                    // ⚠️ 这里**不能**再调 refreshUi():它会重写 pairState,把刚写下的
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
        // 这两条的落点从「连接」页换成「设置」页 —— 地址与配对都搬到那里了(2026-09-19)。
        if (host.isEmpty()) { toast("请先自动发现或手动填写 Mac 地址"); switchTab(Tab.SETTINGS); return }
        if (!isPaired()) { toast("请先与 Mac 配对"); switchTab(Tab.SETTINGS); return }
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
        // 曲目也要清掉:不清的话界面会停在上一次播的那首歌上,看起来像是还在同步。
        RelayLog.clearNowPlaying(this)
        // 歌词同理(2026-09-19)。它是 Mac 每秒回传的,停掉之后不会再有新的来 ——
        // 留着的话歌词页会定在上次那一句,读起来像"还在放"。
        RelayLog.clearLyric(this)
        running = false
        RelayLog.note("relay stopped")
        refreshUi()
    }

    private fun refreshUi() {
        // 先把"贵"的那三项读一次并记下来:每秒那条轻量路径(refreshLiveValues)要复用它们,
        // 自己不去读 —— 理由见 uiTick 上方那段注释。
        lastKnownNotifEnabled = isNotificationListenerEnabled()
        lastKnownPaired = isPaired()
        val notifEnabled = lastKnownNotifEnabled
        val paired = lastKnownPaired
        val batteryOk = isBatteryExempt()
        // "running" 以服务自己的心跳为准,不只信 SharedPreferences —— 进程被杀之后
        // 那个布尔还是 true,界面会一直显示"运行中"而实际早就没了。
        val serviceAlive = RelayLog.isServiceAlive()
        if (running && !serviceAlive) {
            running = false
            getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        }

        applyStatusPill()

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

        // ── 当前曲目(2026-09-17 接上服务发布的实时值) ──
        //
        // 用户实测反馈"状态显示"不够——原来这里只有一句笼统的"同步已开启",看不出到底在同步
        // 什么。曲目标题出现在这几行里,是"整条链路真的通了"最直接的证据:它意味着通知使用权
        // 生效、QQ 音乐被认出来、采集与发送都在跑。
        //
        // 还没读到曲目时给**可操作**的下一步(去开权限 / 去选歌),不是干等 —— 空着一个破折号
        // 用户不知道是该等还是该做什么。
        applyNowPlaying()

        // 配对状态(2026-09-17 补)。
        //
        // ⚠️ "打开就显示已配对"是**对的**,不是 bug:令牌在配对成功那一刻就持久保存了,
        // `adb install -r` 覆盖安装不会清应用数据,所以打开时它本来就在。用户会怀疑这个
        // 状态,是因为界面只写"已配对"两个字 —— 看不出配的是哪台、什么时候配的。
        // 补上这两个信息,这个状态就有依据了。
        val pairedAt = getSharedPreferences("relay", MODE_PRIVATE).getLong("pairedAtMs", 0L)
        val pairedHost = getSharedPreferences("relay", MODE_PRIVATE).getString("ip", "").orEmpty()
        pairState.text = if (paired) {
            val when_ = if (pairedAt > 0L) {
                val days = (System.currentTimeMillis() - pairedAt) / 86_400_000L
                when {
                    days < 1L -> "今天"
                    days < 30L -> "${days} 天前"
                    else -> "${days / 30L} 个月前"
                }
            } else ""
            val where = if (pairedHost.isNotBlank()) " · $pairedHost" else ""
            val tail = if (when_.isBlank()) where else " · $when_$where"
            "已配对$tail"
        } else "尚未配对"
        // 已配对时给一个"解除"的出口。没有它的话,配错了 Mac 或想换一台时,用户在应用里
        // **没有任何办法**重来 —— 只能卸载重装(而且有系统备份的话数据还会回来)。
        btnUnpair.visibility = if (paired) View.VISIBLE else View.GONE
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
        applyDashboard()
    }

    private fun isPaired(): Boolean = !SecureTokenStore(this).load().isNullOrBlank()

    /// 每秒那一拍走的轻量路径:只重算"会随时间变"的部分 —— 服务还活着吗、当前是哪首歌。
    /// 全是读内存里的 SharedPreferences 加字符串比较,不碰 Keystore / 系统设置 / 文件。
    /// 为什么可以省掉其余几项:它们只在用户去系统设置改过之后才变,而那件事发生时 onResume
    /// 会重新跑一次完整刷新(见 uiTick 上方那段注释)。
    private fun refreshLiveValues() {
        val wasRunning = running
        if (running && !RelayLog.isServiceAlive()) {
            running = false
            getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        }
        // 状态翻面了(典型是服务刚被杀掉)就整页刷一次:那一刻连接信息、诊断文字都该跟着变。
        // 这种事一秒最多发生一次,贵一次无所谓;而"服务还活着"这种稳态下就一直走便宜的那条。
        if (wasRunning != running) { refreshUi(); return }
        applyStatusPill()
        applyNowPlaying()
        applyDashboard()
        // 歌词跟着每秒那拍走(2026-09-19):通知栏歌词每句都在变,而这一拍本来就是
        // "拉会随时间变的东西",放这儿正合适。含"值没变就什么都不做"的守卫,
        // 所以常态下它只做一次字符串比较。
        applyLyrics()
    }

    /// 同步状态与那颗悬浮圆钮(2026-09-19)。
    ///
    /// 抽出来是为了让两条刷新路径共用 —— 否则"每秒那条"和"完整那条"迟早各写一份,
    /// 表现成同一个状态下两处显示不一致。
    private fun applyStatusPill() {
        val paired = lastKnownPaired
        syncState.text = when {
            running -> "同步已开启"
            paired -> "就绪,等待开始"
            else -> "同步未启动"
        }
        syncHint.text = when {
            running && !lastKnownNotifEnabled -> "但没有通知使用权,读不到 QQ 音乐 —— 去「设置」页开启"
            running -> "正在同步到 ${macIp.text}:${macPort.text}"
            paired -> "点右下角的按钮开始把播放状态推到 Mac"
            else -> "先到「设置」页和 Mac 配对"
        }
        // 圆钮的图标与描述跟着状态换:播放三角 = 可开始,方块 = 正在跑(点了就停)。
        // contentDescription 也要跟着换,否则用读屏的人听到的永远是"开始同步"。
        syncFab.setImageResource(if (running) R.drawable.ic_sync_stop else R.drawable.ic_sync_start)
        syncFab.contentDescription = if (running) "停止同步" else "开始同步"
    }

    /// 当前曲目那两行。见 refreshUi 里那一段的注释。
    private fun applyNowPlaying() {
        val (title, artist, playing) = RelayLog.nowPlaying(this)
        if (running && title.isNotBlank()) {
            setTextIfChanged(nowTitle, title)
            setTextIfChanged(nowArtist, artist.ifBlank { "未知歌手" } + if (playing) " · 播放中" else " · 已暂停")
        } else {
            setTextIfChanged(nowTitle, "—")
            setTextIfChanged(nowArtist, when {
                !running -> "还没有开始同步"
                !lastKnownNotifEnabled -> "等通知使用权开启后才能读到"
                else -> "等待 QQ 音乐开始播放"
            })
        }
    }

    /// 链路统计面板(2026-09-17,用户要求"仪表盘")。
    ///
    /// 四个数各回答一个具体问题:"已发送"= 送出去过吗、"失败"= 有没有反复失败、
    /// "延迟"= 局域网快不快、"最近"= 现在还在送吗。数据来源见 RelayLog.noteSendResult。
    ///
    /// ⚠️ 没有数据时一律显示 "—" 而不是 0:0 是个**结论**(一条都没成功),而"还没有数据"
    /// 是另一回事。两者混在一起会让刚开同步的用户以为已经失败了。
    private fun applyDashboard() {
        val stats = RelayLog.sendStats(this)
        statSent.text = if (stats.sent > 0) "${stats.sent}" else if (running) "0" else "—"
        statFailed.text = if (stats.failed > 0) "${stats.failed}" else if (running) "0" else "—"
        statFailed.setTextColor(
            if (stats.failed > 0) 0xFFFF7B72.toInt() else 0xFFEEF1F5.toInt())
        statLatency.text = if (stats.lastLatencyMs >= 0) "${stats.lastLatencyMs}ms" else "—"

        // "最近"用相对时间而不是绝对时刻:用户要判断的是"它还活着吗","3 秒前"比"21:58:12"直观。
        // 超过一分钟换成粗粒度,免得一个不断跳秒的数字让人盯着看。
        statLast.text = when {
            !running || stats.lastSentAtMs == 0L -> "—"
            else -> {
                val ago = (System.currentTimeMillis() - stats.lastSentAtMs) / 1000
                when {
                    ago < 1 -> "刚刚"
                    ago < 60 -> "${ago} 秒前"
                    ago < 3600 -> "${ago / 60} 分钟前"
                    else -> "${ago / 3600} 小时前"
                }
            }
        }
        // 超过 15 秒没有成功发送过就是个信号:每秒一条的正常节奏下,那意味着一直在失败。
        statLast.setTextColor(
            if (running && stats.lastSentAtMs > 0L &&
                System.currentTimeMillis() - stats.lastSentAtMs > 15_000L) 0xFFFF7B72.toInt()
            else 0xFFEEF1F5.toInt())

        val rate = stats.successRate
        val barValue = rate?.let { (it * 100).toInt() } ?: 0
        if (statBar.progress != barValue) {
            if (reduceMotion) {
                statBar.progress = barValue
            } else {
                // 平滑推进而不是瞬间跳 —— 这是这一页唯一持续变化的指标,动一下能让
                // "它在正常工作"这件事被看见。
                android.animation.ObjectAnimator.ofInt(statBar, "progress", statBar.progress, barValue)
                    .setDuration(300).start()
            }
        }
        statBar.progressTintList = android.content.res.ColorStateList.valueOf(
            when {
                rate == null -> 0xFF5C6470.toInt()
                rate >= 0.95 -> 0xFF3DD68C.toInt()
                rate >= 0.7 -> 0xFFFFA657.toInt()
                else -> 0xFFFF7B72.toInt()
            })

        // 失败提示要**对症**:"连不上"和"令牌失效"用户要做的事完全不同 —— 前者查网络,
        // 后者必须回 Mac 重新配对,在手机上怎么折腾都没用。笼统说"失败"等于把用户扔在原地。
        val failure = RelayLog.lastFailureKind(this)
        statHint.text = when {
            !running -> "开始同步后这里会显示实时数据"
            rate == null -> "正在等待第一条数据…"
            stats.failed > 0 && failure == "auth" ->
                "Mac 已不再认这个令牌(可能被解除过配对)。回 Mac 重新生成配对码,再配一次"
            stats.failed > 0 && failure == "unreachable" ->
                "连不上 Mac。确认两台设备在同一 Wi-Fi,且 Mac 上的 Lyrimuse 正在运行"
            stats.failed > 0 -> "有 ${stats.failed} 条重试后仍未送达,详见下方诊断记录"
            stats.sent < 3 -> "链路正常,正在采集"
            else -> "链路正常 · 成功率 ${(rate * 100).toInt()}%"
        }
    }

    /// 歌词页(2026-09-19)。
    ///
    /// 数据**全部来自本机**:QQ 音乐开着通知栏歌词时,那一行就在元数据的 TITLE 里
    /// (见 QqMetadataResolver)。这一页不发网络请求、也不依赖 Mac。
    ///
    /// 上一句是推断的:通知栏只给当前这一行,不给上下文。做法是把上一次显示的留在原位
    /// —— 每来新的一句,旧的顺下去。拖进度条回跳时会短暂对不上,下一句一到就追上。
    private fun applyLyrics() {
        val (title, artist, _) = RelayLog.nowPlaying(this)
        val (current, secondary, next) = RelayLog.currentLyricTriple(this)

        lyricTrack.text = when {
            title.isNotBlank() && artist.isNotBlank() -> "$title · $artist"
            title.isNotBlank() -> title
            else -> "还没有在播放"
        }

        // ⚠️ 歌词由 **Mac** 给(2026-09-19 改的架构)。
        //
        // 之前想在手机本地从 QQ 音乐的元数据里捞,那条路不可靠:它取决于 QQ 音乐自己的
        // 通知行为,实测拿到的只有真歌名、没有歌词行。而 Mac 那边本来就有完整的解析引擎
        // (十个源、缓存、逐字、译文、罗马音),歌词搭在它每次响应里回来即可 ——
        // 手机只管显示,不重复实现一遍解析。
        if (current.isBlank()) {
            lyricCurrent.text = if (running) "等待歌词…" else "在手机上用 QQ 音乐放一首歌"
            lyricPrev.text = ""
            lyricNext.text = ""
            lyricHint.text = when {
                !running -> "歌词由 Mac 解析后回传。开始同步后这里会跟着唱。"
                title.isBlank() -> "在手机上用 QQ 音乐放一首歌。"
                else -> "Mac 正在为这首歌找歌词…"
            }
            return
        }

        if (lyricCurrent.text.toString() != current) {
            lyricPrev.text = lyricCurrent.text.let { if (it == current) "" else it }
            lyricCurrent.text = current
            if (!reduceMotion) {
                lyricCurrent.alpha = 0.35f
                lyricCurrent.animate().alpha(1f).setDuration(220).start()
            }
        }
        // 副行放译文或罗马音,跟主行一起换。
        lyricNext.text = when {
            secondary.isNotBlank() -> secondary
            next.isNotBlank() -> next
            else -> ""
        }
        lyricHint.text = "歌词由 Mac 解析"
        // 上一句:把上一次的 current 顺下来 —— 通知栏不给上下文,这是本机能做的最好推断。
        if (lyricPrev.text.isBlank()) lyricPrev.text = ""
    }

    /// 统计区间切换。
    private fun selectStatsRange(days: Int) {
        statsRangeDays = days
        val items = listOf(
            Triple(rangeToday, 1, "今天"),
            Triple(rangeWeek, 7, "7 天"),
            Triple(rangeMonth, 30, "30 天"),
            Triple(rangeAll, 0, "全部"),
        )
        for ((view, value, _) in items) {
            val active = value == days
            view.isSelected = active
            view.setTextColor(if (active) 0xFFEEF1F5.toInt() else 0xFF8B93A1.toInt())
            view.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        renderStats()
    }


    /// 渲染统计页。数据来自本机 ListeningLog —— 见那个文件头注里为什么不从 Mac 要。
    ///
    /// 每次进这一页都重放一遍动画(2026-09-19,用户要求"所有图表都需要有动画"):数字从 0
    /// 滚上去、柱子从底部长起来、进度条推进去。同一份数据反复看会重复播,这是刻意的 ——
    /// 静止的图表看不出"这些数刚算过一遍"。
    private fun renderStats() {
        statsAnimators.forEach { it.cancel() }
        statsAnimators.clear()
        dismissChartTip()

        val summary = ListeningLog.summarize(this, statsRangeDays)
        if (summary.isEmpty) {
            statsTotal.text = "—"
            statsTracks.text = "—"
            statsAvg.text = "—"
            statsTrend.text = "—"
            statsTrend.setTextColor(0xFF5C6470.toInt())
            statsStreak.text = "—"
            statsChart.removeAllViews()
            statsHourChart.removeAllViews()
            statsArtistList.removeAllViews()
            statsTopList.removeAllViews()
            statsChartEmpty.visibility = View.VISIBLE
            return
        }
        statsChartEmpty.visibility = View.GONE

        // 三个数各自滚上去。格式化函数跟非动画路径共用同一个,免得两处口径分叉
        // (比如动画结束时停在 59 分、静态渲染写 1 小时)。
        animateNumber(statsTotal, summary.totalMs, ::formatDuration)
        animateNumber(statsTracks, summary.tracks.size.toLong()) { "$it" }
        animateNumber(statsAvg, summary.averagePerActiveDayMs, ::formatDuration)
        renderTrend(summary)
        renderDailyChart(summary)
        renderTopTracks(summary)
        renderHourChart(summary)
        renderArtistList(summary)
    }

    /// 数字从 0 滚到目标值。
    ///
    /// 只在 reduceMotion 关着时播 —— 开着的人要的是"别动",照常从 0 数上去比直接给结果糟糕得多。
    private fun animateNumber(view: TextView, target: Long, format: (Long) -> String) {
        if (reduceMotion) {
            view.text = format(target)
            return
        }
        val animator = android.animation.ValueAnimator.ofFloat(0f, target.toFloat())
        animator.duration = 620L
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener {
            view.text = format((it.animatedValue as Float).toLong())
        }
        statsAnimators += animator
        animator.start()
    }

    /// 图表柱子在**选中时**浮出来的数字气泡(2026-09-19,用户要求"点击能够看到浮动的具体数字")。
    ///
    /// 气泡挂在那层最外的 root 上、而不是柱子的父容器里:父容器是 LinearLayout、有裁剪与
    /// 布局约束,气泡要能压在别的元素上面,只有 root 那层 FrameLayout 是自由的。坐标按
    /// 屏幕坐标做差算出来,所以柱子在哪一层都能用。
    private fun showChartTip(anchor: View, text: String) {
        val root = findViewById<android.widget.FrameLayout>(R.id.root)
        dismissChartTip()

        val tip = TextView(this).apply {
            this.text = text
            setTextColor(0xFFEEF1F5.toInt())
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.tooltip_bg)
            alpha = 0f
        }
        root.addView(tip)
        chartTip = tip

        // 摆位要等它自己量完 —— 没量之前 width 是 0,居中会偏出去半个气泡。
        tip.post {
            val anchorPos = IntArray(2)
            val rootPos = IntArray(2)
            anchor.getLocationOnScreen(anchorPos)
            root.getLocationOnScreen(rootPos)
            val anchorLeft = anchorPos[0] - rootPos[0]
            val anchorTop = anchorPos[1] - rootPos[1]
            val centered = anchorLeft + (anchor.width - tip.width) / 2f
            val maxX = (root.width - tip.width).coerceAtLeast(0).toFloat()
            tip.x = centered.coerceIn(0f, maxX)
            tip.y = (anchorTop - tip.height - dp(8)).toFloat()
            if (!reduceMotion) {
                tip.translationY = dp(6).toFloat()
                tip.animate().alpha(1f).translationY(0f).setDuration(140).start()
            } else {
                tip.alpha = 1f
            }
        }

        // 自动消失。给足 2.6 秒:数字要能被读完,而点下一根柱子会立刻换掉它,
        // 不存在"来不及看完"的问题。
        val hide = Runnable { dismissChartTip() }
        chartTipHide = hide
        uiTicker.postDelayed(hide, 2_600L)
    }

    private fun dismissChartTip() {
        chartTipHide?.let { uiTicker.removeCallbacks(it) }
        chartTipHide = null
        chartTip?.let { tip ->
            chartTip = null
            val root = findViewById<android.widget.FrameLayout>(R.id.root)
            if (reduceMotion) root.removeView(tip)
            else tip.animate().alpha(0f).setDuration(120)
                .withEndAction { root.removeView(tip) }.start()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /// 环比 + 连续天数。
    ///
    /// ⚠️ 没有可比窗口时显示 "—" 而不是 "0%":"0%" 会被读成"和上一段持平",而事实是
    /// **没法比**(比如看"全部"时根本没有"上一段")。把无说成有是最容易误导的一种显示。
    private fun renderTrend(summary: ListeningStats.Summary) {
        val ratio = summary.changeRatio
        if (ratio == null) {
            statsTrend.text = "—"
            statsTrend.setTextColor(0xFF5C6470.toInt())
        } else {
            val percent = (kotlin.math.abs(ratio) * 100).toInt()
            val sign = if (ratio >= 0) "+" else "−"
            statsTrend.text = "$sign$percent%"
            statsTrend.setTextColor(
                if (ratio >= 0) 0xFF3DD68C.toInt() else 0xFFFFA657.toInt())
            // 百分比也滚上去:环比是这一页唯一的"结论性"数字,让它动一下值得。
            if (!reduceMotion) {
                val animator = android.animation.ValueAnimator.ofFloat(0f, percent.toFloat())
                animator.duration = 620L
                animator.interpolator = android.view.animation.DecelerateInterpolator()
                animator.addUpdateListener {
                    statsTrend.text = "$sign${(it.animatedValue as Float).toInt()}%"
                }
                statsAnimators += animator
                animator.start()
            }
        }
        statsStreak.text = if (summary.streakDays > 0) "${summary.streakDays} 天" else "—"
    }

    /// 24 小时分布。细柱,横轴刻度在布局里。
    ///
    /// 柱子从底部长起来(scaleY 0→1,轴心在底边)+ 逐根延迟,扫过去一眼能看出分布形状;
    /// 点一下浮出"几点 · 多久"。
    private fun renderHourChart(summary: ListeningStats.Summary) {
        statsHourChart.removeAllViews()
        val peak = summary.byHour.maxOrNull() ?: 0L
        if (peak <= 0L) return
        for (hour in 0..23) {
            val ms = summary.byHour[hour]
            val holder = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -1, 1f)
            }
            val bar = View(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    dp(6), (52 * (ms.toDouble() / peak)).toInt().coerceAtLeast(2).let(::dp)).apply {
                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                }
                // 峰值那一小时高亮 —— 一眼看出"我几点听得多",不用逐根比高度。
                setBackgroundColor(
                    if (ms == peak) 0xFF7AA2FF.toInt() else 0x807AA2FF.toInt())
                contentDescription = "$hour 点,${formatDuration(ms)}"
            }
            if (ms > 0L) {
                // 点空白区不算数:0 的柱子也没什么可说的,让它安静地待着。
                bar.isClickable = true
                bar.setOnClickListener {
                    showChartTip(bar, "$hour 点 · ${formatDuration(ms)}")
                }
            }
            holder.addView(bar)
            statsHourChart.addView(holder)
            growFromBottom(bar, delayMs = hour * 16L)
        }
    }

    /// 让一根柱子从底边长起来。轴心设在底边,所以缩放看起来就是"长高"而不是"从中间撑开"。
    ///
    /// 用 scaleY 而不是动画 layoutParams.height:后者每一帧都要请求一次布局(整棵子树的
    /// measure/layout),24 根柱子一起播就是每秒上千次布局;scaleY 走的是渲染层的变换,
    /// 不碰布局。
    private fun growFromBottom(bar: View, delayMs: Long) {
        if (reduceMotion) return
        bar.scaleY = 0f
        bar.pivotY = bar.layoutParams.height.toFloat()
        bar.post {
            bar.pivotY = bar.height.toFloat()
            bar.animate().scaleY(1f).setStartDelay(delayMs).setDuration(420L)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    /// 常听歌手前 8。
    private fun renderArtistList(summary: ListeningStats.Summary) {
        statsArtistList.removeAllViews()
        val top = summary.artists.take(8)
        if (top.isEmpty()) return
        val peak = top.first().ms.coerceAtLeast(1L)
        for ((index, artist) in top.withIndex()) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(if (index == 0) 12 else 10)
                }
                isClickable = true
                setOnClickListener {
                    showChartTip(this, "${artist.key} · ${formatDuration(artist.ms)}")
                }
            }
            // 名次固定宽度,让名字左缘对齐 —— 不等宽的话每行文字会缩进不一。
            row.addView(TextView(this).apply {
                text = "${index + 1}"
                setTextColor(0xFF5C6470.toInt())
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(18), -2)
            })
            row.addView(TextView(this).apply {
                text = artist.key
                setTextColor(0xFFD6DCE6.toInt())
                textSize = 13.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(this).apply {
                text = formatDuration(artist.ms)
                setTextColor(0xFF8B93A1.toInt())
                textSize = 12f
            })
            statsArtistList.addView(row)
            // 细条表示相对量级,压在名字下面。推进去而不是直接给终值 —— 一排进度条同时
            // 走到位,比静悄悄摆在那里更能表达"这是按大小排的"。
            val bar = android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF3DD68C.toInt())
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x14FFFFFF)
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(4)).apply {
                    topMargin = dp(3)
                }
            }
            statsArtistList.addView(bar)
            val target = (1000 * (artist.ms.toDouble() / peak)).toInt()
            val animator = android.animation.ObjectAnimator.ofInt(bar, "progress", 0, target)
            animator.duration = 560L
            animator.startDelay = index * 24L
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            statsAnimators += animator
            if (reduceMotion) bar.progress = target else animator.start()
        }
    }

    /// 每日时长柱状图。用 LinearLayout + 权重画,不引图表库 —— 只有这一处要柱状,
    /// 为它背一个依赖不划算,而且那样也不好跟着主题改色。
    private fun renderDailyChart(summary: ListeningStats.Summary) {
        statsChart.removeAllViews()
        statsChartEmpty.visibility = View.GONE
        if (summary.days.isEmpty()) {
            statsChartEmpty.visibility = View.VISIBLE
            return
        }
        val peak = summary.days.maxOf { it.ms }.coerceAtLeast(1L)
        val dayFormat = java.text.SimpleDateFormat("M 月 d 日", java.util.Locale.CHINA)
        for ((index, day) in summary.days.withIndex()) {
            val holder = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -1, 1f)
            }
            val bar = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    dp(14), (100 * (day.ms.toDouble() / peak)).toInt().coerceAtLeast(3).let(::dp)).apply {
                    gravity = android.view.Gravity.BOTTOM
                }
                setBackgroundColor(0xFF7AA2FF.toInt())
            }
            val label = dayFormat.format(java.util.Date(day.dayStartSecs * 1000L))
            val reading = "$label · ${formatDuration(day.ms)}"
            bar.contentDescription = reading
            bar.isClickable = true
            bar.setOnClickListener { showChartTip(bar, reading) }
            holder.addView(bar)
            statsChart.addView(holder)
            growFromBottom(bar, delayMs = index * 22L)
        }
    }

    /// 听得最多列表。取前 5 首 —— 这一页是概览,不是完整榜单。
    private fun renderTopTracks(summary: ListeningStats.Summary) {
        statsTopList.removeAllViews()
        val top = summary.tracks.take(5)
        if (top.isEmpty()) return
        val peak = top.first().totalMs.coerceAtLeast(1L)
        for ((index, track) in top.withIndex()) {
            val title = track.title.ifBlank { "未知曲目" }
            val reading = "$title" + (if (track.artist.isNotBlank()) " · ${track.artist}" else "") +
                " · ${formatDuration(track.totalMs)}"
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(if (index == 0) 12 else 14)
                }
                isClickable = true
                setOnClickListener { showChartTip(this, reading) }
            }
            row.addView(TextView(this).apply {
                text = "${index + 1}. $title" +
                    if (track.artist.isNotBlank()) " — ${track.artist}" else ""
                setTextColor(0xFFD6DCE6.toInt())
                textSize = 13.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val bar = android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF7AA2FF.toInt())
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x14FFFFFF)
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(6)).apply {
                    topMargin = dp(5)
                }
            }
            row.addView(bar)
            row.addView(TextView(this).apply {
                text = formatDuration(track.totalMs)
                setTextColor(0xFF5C6470.toInt())
                textSize = 11f
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(3)
                }
            })
            statsTopList.addView(row)
            val target = (1000 * (track.totalMs.toDouble() / peak)).toInt()
            val animator = android.animation.ObjectAnimator.ofInt(bar, "progress", 0, target)
            animator.duration = 560L
            animator.startDelay = index * 60L
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            statsAnimators += animator
            if (reduceMotion) bar.progress = target else animator.start()
        }
    }

    /// 毫秒 → 人读的时长。小于一小时给"12 分"(不带秒 —— 这一页是概览,秒是噪音)。
    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes <= 0L -> "不足 1 分"
            minutes < 60L -> "$minutes 分"
            else -> "${minutes / 60L} 小时 ${minutes % 60L} 分"
        }
    }

    /// 只在值真变了时才写 TextView,并给一次轻微淡入。
    ///
    /// 每秒那一拍会调到这里(经 refreshLiveValues → applyStatusPill / applyNowPlaying),
    /// 无条件赋值会让每次都触发一次重绘、长标题还会反复走一遍省略号排版 —— 界面上表现为
    /// 文字在微微抖。同时"变了"本身值得一个提示:曲目换了、状态翻了,淡一下比瞬间跳更
    /// 容易被注意到。
    private fun setTextIfChanged(view: TextView, value: String) {
        if (view.text.toString() == value) return
        view.text = value
        if (reduceMotion) return
        view.alpha = 0.55f
        view.animate().alpha(1f).setDuration(180).start()
    }

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
