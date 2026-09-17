---
feature: phone-lyrics-mac
status: delivered
updated: 2026-09-17
branch: feat/phone-lyrics-mac
commits: d9e11dc..0f5d524
---

# Phone Lyrics Mac

## Report

**What was built** — 「手机歌词镜像」：Android MediaSession 中继把 QQ 音乐正在播放（标题/歌手/进度）经 LAN HTTP 推到 Mac；Mac 端 Electron 菜单栏常驻应用显示透明置顶悬浮歌词窗，设置页仅保留连接状态、连接选项（端口/IP）、歌词字号与单/双行/锁定/透明度/演示。根 `index.html` 提供同套 UI 的浏览器演示。无高度重合的现成 GitHub 项目（`lyrimuse`/`LyricsX` 读本机播放器），故自研。

**Verification** — `node scripts/test-lrc.js` PASS 2/2；`node --check electron/*.js` PASS；StatusServer smoke `POST /api/now-playing` PASS；Electron 启动后 `GET :8765/api/status` 与 curl 推送显示 `connected:true` 与 lastPayload；审查后修复 Android manifest 未注册 `NotificationListenerService`、START_STICKY 空 intent 的 `lateinit` 崩溃、歌词失败误显示 demo 词、离线文案、fetch 竞态。

**Journey log**
- GitHub 检索：无「手机→Mac」高度重合项目，不整仓搬运。
- Android `getActiveSessions` 必须在 Manifest 注册 NotificationListenerService，仅写 Kotlin 类不够。
- START_STICKY 重启 intent 为 null，服务端配置须可空持久化。
- 歌词拉取失败应显示歌名，不能塞演示 LRC 污染真实曲目。

## [S1] Problem

手机连热点放 QQ 音乐（音质更好），Mac 只用来学习，不想在 Mac 上再开 QQ 音乐。需要：

- 手机「正在播放」信息实时映射到 Mac
- Mac 菜单栏常驻，桌面透明悬浮歌词窗
- 主界面只保留：连接状态、连接选项、歌词字号/排版
- 歌词窗可拖动、缩放、锁定；单行/双行
- 同一副耳机同时连手机与 Mac，Mac 侧只看词不播歌

## [S2] Design

### 形态决策

| 侧 | 选型 | 理由 |
|----|------|------|
| Mac | Electron 菜单栏应用 | 托盘常驻、透明置顶窗、HTML UI 可同时浏览器预览 |
| 手机 | Android MediaSession 中继 | 系统级读取 QQ 音乐 title/artist/position，无需 QQ 官方接口 |
| 歌词源 | Mac 侧拉取 LRC（QQ 音乐 / LRCLIB 回退） | MediaSession 通常不带歌词；手机只推元数据+进度 |

### 网络协议（LAN，手机热点同网段）

Mac 在 `0.0.0.0:8765` 起 HTTP 服务（端口可配）。

#### `POST /api/now-playing`

```json
{
  "title": "晴天",
  "artist": "周杰伦",
  "album": "叶惠美",
  "durationMs": 269000,
  "positionMs": 35200,
  "state": "playing",
  "source": "qqmusic",
  "ts": 1726550000000
}
```

- `state`: `playing` | `paused` | `stopped`
- Mac 以本地时钟从 `positionMs` 外推，直至下一条更新（Android 1s 一推）

#### `GET /api/health` / `GET /api/status`

健康检查、连接状态、LAN 地址、最近 payload。

### Mac 进程结构

```
Electron Main
├─ Tray（显示/隐藏歌词、锁定、演示、设置、退出）
├─ LyricsOverlayWindow（透明、无边框、置顶、拖动/缩放、锁定穿透）
├─ SettingsWindow（连接状态 / 端口 / 字号 / 单双行 / 锁定 / 透明度 / 演示）
├─ HttpServer (:8765)
└─ LyricsService（搜歌 → 取 LRC → 解析 → 与进度对齐）
```

### UI 契约

**歌词悬浮窗**：默认单行当前句；双行当前句+下一句；字号 16–48px；解锁时 `-webkit-app-region: drag` 可拖；锁定 `setIgnoreMouseEvents(true,{forward:true})`；无歌词/失败显示歌名。

**设置页**：手机连接状态、端口与 Mac IP、字号、单/双行、锁定、透明度、演示模式。

### 歌词对齐

1. 收到 playing → `positionMs` 记为锚点  
2. `now = positionMs + (Date.now() - anchorWall)`  
3. paused → 冻结当前行  
4. LRC 二分定位；双行取 `i` 与 `i+1`  
5. 无歌词 → 显示歌曲名  

## [S3] Out of Scope

- iOS App / Shortcuts
- QQ 账号登录、播放控制
- Windows / Linux 打包
- 逐字卡拉 OK 填色
- App Store / 公证分发

## Tasks

- [x] T1: 仓库骨架 + package.json + 共享 LRC/协议模块 — acceptance: `node` 可解析 LRC 单测通过 (covers: S2)
- [x] T2: Electron 主进程：托盘 + 悬浮窗 + 设置窗 — acceptance: `npm start` 出托盘与两窗 (covers: S2; depends: T1)
- [x] T3: HTTP 接收 + 歌词拉取 + 进度对齐 — acceptance: curl 推送后状态 connected 且有 payload (covers: S2; depends: T2)
- [x] T4: 设置页交互（状态/端口/字号/单双行/锁定/演示）— acceptance: 设置经 IPC 推到悬浮窗 (covers: S2; depends: T2)
- [x] T5: 根 `index.html` 浏览器演示 — acceptance: 直接打开可拖可调可看双行 (covers: S2; depends: T1)
- [x] T6: Android MediaSession 中继 App — acceptance: 源码完整含 manifest 注册 NotificationListener，可安装后 POST 到 Mac (covers: S2; depends: T3)
- [x] T7: README 接入手册 + 验证 — acceptance: 按文档可完成手机→Mac 链路 (covers: S2; depends: T3,T6)
