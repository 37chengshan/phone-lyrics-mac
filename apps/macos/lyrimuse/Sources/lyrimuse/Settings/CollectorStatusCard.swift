import LyrimuseCore
import SwiftUI

/// 「后台采集服务」状态卡(2026-09-17 从 `PlayerSettingsTab` 抽出来)。
///
/// 这个服务是**手机链路里不可少的一环**:它读 `phone-now-playing.json` 那份手机快照,做歌词解析、
/// 翻译、罗马音、封面抓取并写缓存。没有它,手机上照样播、Mac 上什么都不显示。
///
/// 抽出来的原因:它原来只住在「播放器」页里,而那个页面被手机连接页整页替掉之后,这张卡连同它
/// 唯一的「启用」按钮一起变成了搜不到、翻不到的孤儿 —— 服务真挂了的时候用户没有任何入口能救回来。
/// 手机连接页要复用同一张卡,所以搬到独立文件(两边共用一份,不复制:复制出来的那份迟早只改一边)。
///
/// 为什么是"状态图标 + 状态文字 + 动作按钮"而不是一个 Toggle:需要展示"装了但没跑起来"这种中间态
/// (`LaunchdJobState` 三态),纯 Toggle 表达不了。
struct CollectorStatusCard: View {
    /// 只在 `.onAppear` 和每次操作后重新查一次,不是 @Published —— 这个状态由 launchd 管,
    /// App 自己不会主动收到"进程挂了"这类通知,只能被动查。
    @State private var collectorState: LaunchdJobState = .notRegistered
    @State private var isToggling = false
    /// 只在"这次点了启用、结果没启动起来"时为真,切走这一页就清掉,不会把上一次失败的提示
    /// 一直留着误导下一次操作。
    @State private var enableFailed = false
    /// App 本体版本 vs 打包进这份 App 的 collector 版本是否一致(见
    /// `CollectorServiceManager.bundledCollectorVersion` 头注)。nil = 一致或没法判断(两种都不该
    /// 报警),非 nil 才代表真的查到了不一致。只在 `.onAppear` 查一次:要真的 spawn 一次 collector
    /// 子进程,而版本号在一次停留期间不会变。
    @State private var versionMismatch: (appVersion: String, collectorVersion: String)?
    /// 同一时刻最多一次 `launchctl print` 在飞(它是子进程 + `waitUntilExit`)。
    @State private var stateInFlight = false

    var body: some View {
        SettingsCard {
            SettingsRow(
                icon: statusIconName,
                iconTint: statusIconColor,
                title: L10n.t("后台采集服务"),
                // 副标题只留状态,职责说明进「?」。
                subtitle: statusCaption,
                help: L10n.t("读取播放状态、抓歌词和封面")
            ) {
                // 停用入口刻意没有:这个服务停掉之后 App 就是个空壳(读不到播放状态、不解析
                // 歌词、不写缓存),界面上每一处都不再更新,而用户很难把"什么都不动了"跟自己在
                // 设置里点过的一个按钮联系起来。它没有"用户可能想关掉它"的正当场景,不该出现在
                // 设置里。只保留没跑起来时的「启用」——那是个真的能救回来的动作。
                if isToggling {
                    ProgressView().controlSize(.small)
                } else if !collectorState.isRunning {
                    Button(L10n.t("启用")) { enable() }
                }
            }
            // 启用失败时给具体指引,不是只把红叉留在原地——这里能提供的具体行动是导出诊断信息
            // (汇总 App/采集器日志),不是空泛地说"启用失败"。
            if enableFailed {
                CardDivider()
                SettingsNote {
                    Text(L10n.t("启用失败，可能是权限或系统限制导致后台服务没能正常启动，导出诊断信息能看到具体原因，也方便反馈问题"))
                    Button(L10n.t("导出诊断…")) { DiagnosticsExporter.exportInteractively() }
                }
            }
            // ⚠️ 文案 2026-09-02 重写。原文是"…建议重新安装 App",而这是**误导**:版本号是编译期
            // 烧进二进制的,重装同一个安装包一万次也还是同一个版本号,用户照做只会白费力气还更困惑。
            // 现在如实说明:这是打包时的疏漏、不影响功能、不需要用户做任何事。
            if let mismatch = versionMismatch {
                CardDivider()
                SettingsNote {
                    Text(L10n.t("这个版本打包时漏了同步后台采集服务的版本号。不影响功能，采集服务的实际代码跟 App 是同一个版本，不需要你做任何处理"))
                    Text("App \(mismatch.appVersion) · \(L10n.t("采集服务")) \(mismatch.collectorVersion)")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .textSelection(.enabled)
                }
            }
        }
        // ⚠️ 不能只在 onAppear 读一次(2026-08-21 用户报"怎么变成未知了")。build.sh 的重装顺序
        // 恰好制造一个窗口:**先** kickstart App(设置窗口恢复、onAppear 读一次状态)、**再**
        // reload collector 的 job,于是这一次读正好落在 bootout→bootstrap 的中间态上,之后再没人
        // 重读,卡片就永久挂着一个橙色警告和一颗本不该出现的「启用」按钮——而服务其实一直在跑。
        // 修法是让它自愈:每拍重读一次(`launchctl print` 实测 4ms,只在这一页显示着时跑),外加
        // 切回 App 时重读一次。
        .onAppear {
            refreshState()
            refreshVersionCheck()
        }
        .onReceive(Timer.publish(every: 2, on: .main, in: .common).autoconnect()) { _ in
            refreshState()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            refreshState()
        }
    }

    /// 重读一次真实状态。只在真的变了时才赋值 —— 这是每 2 秒一拍的路径,而 `collectorState`
    /// 驱动整张卡片的图标/文案/按钮;无条件赋值会让 SwiftUI 每拍重算一遍。
    ///
    /// ⚠️ `CollectorServiceManager.state` 要起一个 `launchctl print` 子进程并 `waitUntilExit`。
    /// 主线程同步等会掉帧,所以下到后台线程,结果回主 actor 再比较赋值。
    private func refreshState() {
        guard !stateInFlight else { return }
        stateInFlight = true
        Task {
            let latest = await Task.detached(priority: .utility) { CollectorServiceManager.state }.value
            stateInFlight = false
            if latest != collectorState { collectorState = latest }
        }
    }

    /// 查一次"App 本体版本"跟"打包进这份 App 的 collector 版本"是否一致。真的 spawn 一次子进程,
    /// 丢到后台线程跑,不阻塞设置页打开这一下。
    private func refreshVersionCheck() {
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        Task.detached(priority: .utility) {
            guard let collectorVersion = CollectorServiceManager.bundledCollectorVersion(),
                  collectorVersion != appVersion else {
                // nil(拿不到)或版本一致,都不该报警——"没法判断"不等于"有问题"。已经报过警的情况下
                // 重新查到一致(比如刚重新安装完),也要把旧警告收回去,不能一直挂着。
                await MainActor.run { versionMismatch = nil }
                return
            }
            await MainActor.run {
                versionMismatch = (appVersion: appVersion, collectorVersion: collectorVersion)
            }
        }
    }

    private func enable() {
        isToggling = true
        enableFailed = false
        Task {
            let state = await CollectorServiceManager.setEnabledAndWait(true)
            AppSettings.shared.collectorServiceEnabled = true
            collectorState = state
            isToggling = false
            // 只有这一个方向了(见按钮处的注释),没跑起来就是失败,直接标红给指引。
            enableFailed = !state.isRunning
        }
    }

    private var statusCaption: String {
        switch collectorState {
        case .running:
            return L10n.t("运行中")
        case .registeredNotRunning(let code):
            // 装上了却没有进程 —— KeepAlive 的 job 落到这个状态基本就是起不来/崩溃重启循环。
            // 带上退出码,用户反馈时这一个数字就够定位了。
            if let code {
                return String(format: L10n.t("已安装但未运行（上次退出码 %d）"), code)
            }
            return L10n.t("已安装但未运行")
        case .unknown:
            return L10n.t("状态未知")
        case .notRegistered:
            return L10n.t("未运行")
        }
    }

    private var statusIconName: String {
        switch collectorState {
        case .running: return "checkmark.circle.fill"
        case .registeredNotRunning, .unknown: return "exclamationmark.triangle.fill"
        case .notRegistered: return "xmark.circle.fill"
        }
    }

    private var statusIconColor: Color {
        switch collectorState {
        case .running: return .green
        case .registeredNotRunning, .unknown: return .orange
        case .notRegistered: return .red
        }
    }
}
