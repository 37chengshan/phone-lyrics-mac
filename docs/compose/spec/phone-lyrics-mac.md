---
feature: phone-lyrics-mac
status: in-progress
updated: 2026-09-17
branch: feat/phone-lyrics-mac
commits: d9e11dc..HEAD
---

# Phone Lyrics Mac

## Report

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

GitHub 已检索：`lyrimuse`/`LyricsX` 等读的是 **本机** 播放器，无「手机→Mac」高度重合项目，自研。

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
- `positionMs`: 推送时刻的播放位置
- Mac 以本地单调时钟从 `positionMs` 外推，直至下一条更新（目标 1s 一推）

#### `GET /api/health`

```json
{ "ok": true, "connected": true, "lastTitle": "...", "uptimeMs": 123 }
```

#### `GET /api/status`

连接状态、当前曲目、歌词是否就绪、本机 LAN 地址列表（供手机填写）。

### Mac 进程结构

```
Electron Main
├─ Tray（菜单栏图标 + 菜单：显示/隐藏歌词、设置、演示、退出）
├─ LyricsOverlayWindow（透明、无边框、置顶、可拖可缩放、锁定时忽略鼠标）
├─ SettingsWindow（连接状态 / 端口 / 字号 / 单双行 / 锁定 / 透明度 / 演示）
├─ HttpServer (:8765)
└─ LyricsService（搜歌 → 取 LRC → 解析 → 与进度对齐）
```

### UI 契约

**歌词悬浮窗**

- 默认单行：当前句；双行：当前句 + 下一句
- 字号 16–48px 可调；字体可选系统默认/苹方/圆体
- 拖动标题区（解锁时）移动；角拖拽改宽；`缩放` 由窗口 resize + 字号联动
- 锁定：`setIgnoreMouseEvents(true, { forward: true })`，不挡点击
- 置顶 + 全屏空间不抢焦点（`visibleOnAllWorkspaces`）

**设置页（主页面，极简）**

1. 手机连接状态（在线/离线、延迟、当前曲目）
2. 连接选项（端口、重启服务、显示 Mac IP、协议说明）
3. 歌词外观（字号、行数 1/2、锁定、透明度、演示模式）

### 歌词对齐

1. 收到 playing → `positionMs` 记为锚点
2. `now = positionMs + (Date.now() - anchorWall)`
3. paused → 冻结当前行
4. LRC 时间戳二分定位当前行；双行模式取 `i` 与 `i+1`
5. 无歌词 → 显示「歌词加载中…」或仅歌曲名

### 错误行为

| 情况 | 行为 |
|------|------|
| 手机 >8s 无更新 | 状态→离线，歌词窗显示「等待手机」半透明 |
| 端口占用 | 设置页报错，托盘通知；可改端口 |
| 歌词 API 失败 | 重试 2 次 → LRCLIB → 显示歌名 |
| 演示模式 | 内置示例 LRC，不依赖网络 |

## [S3] Out of Scope

- iOS App / Shortcuts 自动化（本期不做）
- QQ 账号登录、收藏同步、播放控制
- Windows / Linux 打包
- 逐字卡拉 OK 填色（仅行级高亮；架构预留）
- App Store / 公证分发

## Tasks

- [ ] T1: 仓库骨架 + package.json + 共享 LRC/协议模块 — acceptance: `node` 可解析 LRC 单测通过 (covers: S2)
- [ ] T2: Electron 主进程：托盘 + 悬浮窗 + 设置窗 — acceptance: `npm start` 出托盘与两窗 (covers: S2; depends: T1)
- [ ] T3: HTTP 接收 + 歌词拉取 + 进度对齐 — acceptance: curl 推送后悬浮窗跟行 (covers: S2; depends: T2)
- [ ] T4: 设置页交互（状态/端口/字号/单双行/锁定/演示）— acceptance: 改设置立即反映到悬浮窗 (covers: S2; depends: T2)
- [ ] T5: 根 `index.html` 浏览器演示（同套 UI + 模拟推送）— acceptance: 直接打开可拖可调可看双行 (covers: S2; depends: T1)
- [ ] T6: Android MediaSession 中继 App — acceptance: 源码完整，可安装后 POST 到 Mac (covers: S2; depends: T3)
- [ ] T7: README 接入手册 + 验证 — acceptance: 按文档可完成手机→Mac 链路 (covers: S2; depends: T3,T6)
