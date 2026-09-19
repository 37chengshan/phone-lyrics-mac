import Foundation

/// 手机请求响应里那份歌词的**线程安全暂存**(2026-09-19)。
///
/// ## 为什么需要它
///
/// 歌词数据住在 PlaybackCoordinator(主 actor)上,而取用它的地方是 NWListener 那条
/// 专门的 HTTP 队列 —— 中间隔着一个 actor 边界。第一版直接在 provider 闭包里写了
/// MainActor.assumeIsolated,那是个**会崩的写法**:该闭包在 HTTP 队列上被调用,
/// assumeIsolated 的前提不成立,Swift 6 下会直接触发 preflight 断言。
///
/// DispatchQueue.main.sync 也能拿到数据,但它把每秒一次的响应时间绑在主线程的空闲程度上,
/// 而且是在持有路由锁的情况下等 —— 一次主线程上的重排(歌词窗口动画、统计页刷新)就能让
/// 心跳变慢。这里换成"主 actor 侧写、HTTP 队列侧读"的单向数据流:主 actor 每次相关值变化时
/// 写一份小字典,读的一侧只做一次加锁取值,不等任何人。
///
/// 代价是"最多晚一个 runloop 拍"—— 手机下一秒的心跳就会拿到最新行,而歌词本来就是一秒一行
/// 的节奏,这个延迟看不出来。
public final class PhoneLyricSnapshot: @unchecked Sendable {
    private let lock = NSLock()
    private var payload: [String: Any]?

    public init() {}

    /// 主 actor 侧调用:更新当前该给手机的歌词。nil = 此刻没有可给的(没在播 / 没歌词)。
    public func update(_ payload: [String: Any]?) {
        lock.lock()
        self.payload = payload
        lock.unlock()
    }

    /// HTTP 队列侧调用:取一份当前值。取不到返回 nil,由调用方决定"保留上一帧"。
    public var current: [String: Any]? {
        lock.lock()
        defer { lock.unlock() }
        return payload
    }
}


/// 载荷构造(纯函数,自检直接覆盖)。
///
/// 抽成纯函数而不是写在 PhonePlaybackService 的刷新方法里,是为了让"哪些字段该出现、
/// 空值怎么处理"这件事能被测试钉住 —— 这一段决定了手机屏幕上到底有没有字。
public enum PhoneLyricPayload {
    /// 组装给手机的歌词。
    ///
    /// ⚠️ **空值一律不放进去**,而不是放空串。手机那边看的是"有没有 current 这个键":
    /// 放空串会让它把界面清掉,而正确的行为是保留上一帧(见 PhoneHTTPRouter.lyricProvider
    /// 的注释)。整份都空时返回 nil,同理 —— "没歌词"与"歌词是空的"是两件事。
    public static func make(
        hasLyricsContent: Bool,
        currentLine: String?,
        translation: String?,
        romanization: String?,
        nextLine: String?,
        title: String,
        artist: String
    ) -> [String: Any]? {
        // 没有歌词内容就什么都不给。手机据此保留上一帧 —— 换歌时先经过这一态,
        // 让它停在上一首的最后一行,而不是闪一下空白。
        guard hasLyricsContent else { return nil }
        var payload: [String: Any] = [:]
        if let currentLine, !currentLine.isEmpty { payload["current"] = currentLine }
        if let translation, !translation.isEmpty { payload["translation"] = translation }
        if let romanization, !romanization.isEmpty { payload["romanization"] = romanization }
        if let nextLine, !nextLine.isEmpty { payload["next"] = nextLine }
        if !title.isEmpty { payload["title"] = title }
        if !artist.isEmpty { payload["artist"] = artist }
        // ⚠️ 只有 title/artist 而**没有 current** 时也要给 nil。手机把 current 当作
        // "能不能显示歌词"的判据(见 RelayApiClient.parseLyric):给一份没有 current 的载荷,
        // 那边会整份丢掉,等于白发一趟。
        if payload["current"] == nil { return nil }
        return payload
    }
}
