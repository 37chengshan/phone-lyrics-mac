# PRD：手机歌词镜像（Phone → Mac Desktop Lyrics）

| 项 | 内容 |
|----|------|
| 文档类型 | PRD / Feature Spec |
| 版本 | v1.0 |
| 日期 | 2026-09-17 |
| 状态 | designed |
| 分支 | `feat/phone-lyrics-mac` |
| 一句话 | **搬用成熟 Mac 桌面歌词底座的 UI/窗口/菜单栏，只替换「正在播放」数据源为手机 QQ 音乐推送。** |

---

## 1. 背景与问题

### 1.1 用户场景
- 手机连热点放 **QQ 音乐**（音质更好）
- Mac 只学习，**不想开 Mac 版 QQ 音乐**
- 耳机同时连手机和 Mac，听歌在手机，**Mac 只要看词**
- 要求：菜单栏常驻、桌面悬浮歌词、可拖可缩放可锁定、字号/单双行、实时

### 1.2 为什么不能继续自研 UI
已验证结论（见 `research/mac-desktop-lyrics/`）：
- LyricsX 停更、QQ 歌词 API 失效、Sequoia 裁剪问题多
- 社区成熟方案：**lyrimuse**（本机 MediaRemote + 多源歌词 + 悬浮窗 + 菜单栏）
- 自研 Electron 壳在托盘、拖动、关窗、歌词对齐上反复踩坑，体验全面落后

### 1.3 真正的差异化（本项目唯一核心）
成熟底座读的是 **Mac 本机播放器**。  
我们需要的是：**Android 手机 Now Playing → 局域网 → 当作「虚拟播放器」喂给同一套歌词 UI。**

```
[成熟底座，直接搬]          [本项目核心，必须自研]
悬浮歌词窗 / 菜单栏     +    Android MediaSession 中继
设置 / 歌词源 / 对齐         LAN NowPlaying Provider
拖动 缩放 锁定 字号           协议 / 进度锚点 / 断线清理
```

---

## 2. 目标 / 非目标

### 2.1 目标（P0）
1. Mac 使用 **成熟底座的桌面歌词体验**（菜单栏 + 悬浮窗 + 设置），不重写 UI。
2. 数据源增加 **「手机」播放器**：收到 Android 推送的 title/artist/position 后，走底座原有歌词搜索与滚动。
3. 实时：手机 1s 一推；Mac 本地时钟外推进度；换歌/暂停/停止行为与本机播放器一致。
4. 断线：手机 >8s 无推送 → 歌词窗显示「等待手机」，**绝不残留上一首**。

### 2.2 非目标（P0 不做）
- iOS App
- 控制手机播放（暂停/切歌）
- 账号同步、收藏、播放器控制
- Windows/Linux
- 把 lyrimuse 全部功能重做一遍

### 2.3 目标（P1）
- 自动发现 Mac IP（UDP beacon）
- 演示模式
- 时间轴微调（±ms offset）

---

## 3. 方案选型（已定）

| 层 | 选型 | 理由 |
|----|------|------|
| **底座（搬）** | **lyrimuse** 源码（GPL-3.0） | 活跃、QQ 音乐、悬浮窗、菜单栏、多源歌词、MediaRemote 全套成熟实现 |
| 备选底座 | MxIris/LyricsX fork 或 LyricFever | 许可更松，但 QQ 音乐与活跃度不如 lyrimuse |
| **核心（做）** | 新增 `NetworkPlaybackSource` + Android Relay | 把手机推送伪装成「当前播放器」 |
| 歌词源 | **复用底座**（Kugou/多源等），不自研搜词 | 避免 QQ API 再踩坑 |
| 手机端 | 已有 `android/PhoneLyricsRelay`（MediaSession + 前台服务） | 只推元数据，不传整首 LRC |

**许可注意**：lyrimuse 为 GPL-3.0。个人本机使用无问题；若对外分发修改版，须同样 GPL 开源。PRD 默认 **本机自用**。

---

## 4. 用户故事

| ID | 角色 | 故事 | 验收 |
|----|------|------|------|
| US1 | 学习者 | 打开 Mac App，菜单栏有图标，不占 Dock | 启动后仅菜单栏入口 |
| US2 | 学习者 | 手机播 QQ 音乐，Mac 悬浮窗跟着滚词 | 歌名一致，进度误差 <0.5s（稳态） |
| US3 | 学习者 | 拖动/缩放/锁定/关歌词窗 | 交互与底座一致或更好 |
| US4 | 学习者 | 字号、单行/双行在设置里改 | 立即生效并持久化 |
| US5 | 学习者 | 断开手机热点 | 显示等待手机，旧词清空 |
| US6 | 学习者 | 同时用 Mac 本机播放器 | 不与手机源互相抢词（见 §7 冲突策略） |

---

## 5. 功能需求

### 5.1 底座能力（搬运，不重做）
- [ ] 菜单栏 Status Item（template 图标、单实例、常驻）
- [ ] 桌面悬浮歌词窗：置顶、拖动、缩放、锁定、透明度、单/双行、关闭
- [ ] 设置窗：播放器、歌词源、外观、快捷键
- [ ] 歌词搜索 / 多源匹配 / LRC 解析 / 行级滚动
- [ ] 本机播放器（Apple Music/Spotify/QQ 音乐 Mac 等）继续可用

### 5.2 核心新增（本项目）

#### FR-1 手机 NowPlaying Provider
- 监听 `0.0.0.0:8765`（可配）
- `POST /api/now-playing` 接收：

```json
{
  "title": "晴天",
  "artist": "周杰伦",
  "album": "叶惠美",
  "durationMs": 269000,
  "positionMs": 35200,
  "state": "playing",
  "source": "com.tencent.qqmusic",
  "ts": 1726550000000
}
```

- `state`: `playing|paused|stopped`
- 成功后：以 `title+artist` 调用 **底座歌词管线**，进度锚点 = `positionMs + (now - receiveWall)`
- `GET /api/status`：连接状态、最近 payload、LAN IP 列表

#### FR-2 播放器身份
- 在底座播放器列表显示为：**「手机 · QQ音乐」**（或 `Network (Phone)`）
- 包名/来源写入详情，便于排查
- 优先级：若本机 QQ 音乐正在播且手机 8s 内有推送 → **默认手机源**（可设置切换）

#### FR-3 断线与脏数据
- >8s 无推送：状态=离线；歌词区「等待手机」；**清空行缓存**
- 收到新 title+artist：立即丢弃旧歌词，防串歌
- 端口占用：设置页报错 + 菜单栏通知，可改端口重启监听

#### FR-4 Android 中继（已有，按 PRD 收紧）
- 仅优先 `com.tencent.qqmusic*` MediaSession
- 前台服务 + WakeLock + 通知使用权引导 + 电池优化引导
- 1s 推送；通知栏显示「推送中 · 歌名」
- 「测试连接」调 `/api/status`

#### FR-5 设置页（在底座设置里加一节「手机」）
- 连接状态 / 当前曲目 / 包名
- 端口、显示 Mac IP、复制推送地址
- 源优先级：自动 / 仅手机 / 仅本机
- 打开日志

---

## 6. 非功能

| 项 | 要求 |
|----|------|
| 延迟 | 局域网推送 <100ms；行切换相对手机进度 <0.5s |
| CPU | 空闲 <3% |
| 隐私 | 仅局域网，不上传云端 |
| 崩溃 | Provider 异常不得拖垮 UI 线程 |
| 兼容 | macOS 13+ Apple Silicon（主）；Intel 尽力 |

---

## 7. 架构

```
┌─────────────────────────────────────────────┐
│  Mac App（基于 lyrimuse 底座）                 │
│  ┌─────────────┐  ┌──────────────────────┐  │
│  │ StatusBar   │  │ Floating Lyrics UI   │  │
│  │ + Settings  │  │ (拖/缩/锁/字号/行数)  │  │
│  └──────┬──────┘  └──────────▲───────────┘  │
│         │                    │              │
│  ┌──────▼────────────────────┴───────────┐  │
│  │ Lyrics Pipeline（底座：搜词/LRC/滚动）  │  │
│  └──────▲────────────────────▲───────────┘  │
│         │                    │              │
│  ┌──────┴──────┐      ┌──────┴───────────┐  │
│  │ Local MR    │      │ Phone Provider   │  │
│  │ MediaRemote │      │ HTTP :8765       │  │
│  └─────────────┘      └──────▲───────────┘  │
└──────────────────────────────┼──────────────┘
                               │ LAN JSON
                        ┌──────┴───────────┐
                        │ Android Relay    │
                        │ MediaSession     │
                        │ (QQ Music)       │
                        └──────────────────┘
```

### 实现路径（推荐）
1. **Fork/ vendor lyrimuse 源码**到 `vendor/lyrimuse/`（或 `apps/LyrimusePhone/`）
2. 新增 `PhoneNowPlayingSource`（Swift）：内嵌 HTTP 或 sidecar Node 进程
3. 在播放器选择与歌词刷新处插入 Network 源
4. 设置 UI 增加「手机」页（SwiftUI，样式跟底座）
5. Android 保持现 APK，按 FR-4 校验

**Sidecar 备选（更快）**：若改 Swift 成本高，保留 Electron 仅作 **HTTP 接收 + 把 now-playing 写成底座能读的接口**；UI 仍用 lyrimuse。不推荐长期双进程。

---

## 8. 里程碑

| 阶段 | 交付 | 验收 |
|------|------|------|
| M0 | 本 PRD 签字 | 用户确认底座=lyrimuse、核心=Phone Provider |
| M1 | vendor 底座可编译运行 | 本机 QQ 音乐出词（验证底座） |
| M2 | Phone HTTP Provider 接入 | curl 推送后悬浮窗跟词 |
| M3 | 设置「手机」页 + 断线策略 | US3–US5 |
| M4 | APK 对齐 + 联调 | 真机热点全链路 |
| M5 | 清理自研 Electron UI | 仓库只留中继/文档/底座 |

---

## 9. 验收清单（P0）

- [ ] 菜单栏常驻，无 Dock 图标（或可隐藏）
- [ ] 悬浮窗可拖、可缩放、可锁定、可关闭
- [ ] 设置可改字号、单/双行
- [ ] 手机播 QQ 音乐 → Mac 歌名一致且跟行
- [ ] 断手机后 8s 内旧词清空
- [ ] 本机播放器与手机源不互相串歌
- [ ] 日志可查最近 payload 与歌词源

---

## 10. 风险

| 风险 | 缓解 |
|------|------|
| GPL 传染 | 仅本机自用；若分发则整包开源 |
| 底座结构复杂、改动面大 | 先 sidecar 接入，再考虑深改 Swift |
| QQ Music MediaSession 被杀 | 通知使用权 + 前台服务 + 电池白名单 |
| 热点 IP 变化 | 设置页实时显示 IP + 测试连接 + UDP 发现 |
| 双源抢播 | 明确优先级策略 FR-2 |

---

## 11. 当前仓库映射

| 路径 | 角色 |
|------|------|
| `/Applications/Lyrimuse.app` | 已安装的成熟底座（本机歌词） |
| `android/PhoneLyricsRelay` | 手机中继（核心输入） |
| `electron/*` | **降级为中继接收/调试**，UI 不再作为产品主线 |
| `docs/compose/spec/phone-lyrics-mac.md` | 与本 PRD 同步的 compose spec |
| `research/mac-desktop-lyrics/` | 选型证据 |

---

## 12. 需要你拍板的一点

实现时采用哪条路径（推荐 A）：

- **A. 深改 lyrimuse 源码**：Phone Provider 进同一体验，UI 完全一致（工作量大，体验最好）
- **B. Sidecar**：lyrimuse 管本机；手机推送用极简悬浮窗（现 Electron 修好托盘后）并存
- **C. 只装 lyrimuse 不用手机链路**：Mac 也开 QQ 音乐（与原需求冲突，不推荐）

默认按 **A** 排期；你回复「确认 A/B/C」后进入 M1 编译底座。
