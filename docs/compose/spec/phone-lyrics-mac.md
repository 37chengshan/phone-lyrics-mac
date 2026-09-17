---
feature: phone-lyrics-mac
status: designed
updated: 2026-09-17
branch: feat/phone-lyrics-mac
commits: d9e11dc..29eadb2
---

# Phone Lyrics Mac

> 权威产品文档见 **`docs/PRD-手机歌词镜像.md`**。本文件为 compose 任务合同。

## Report

## [S1] Problem

手机放 QQ 音乐、Mac 学习时不听歌，需要 Mac 桌面实时歌词。自研 UI 不达标；应 **搬用成熟桌面歌词底座**，只实现「手机 Now Playing → 歌词管线」。

## [S2] Design

- **底座**: vendor `lyrimuse`（菜单栏、悬浮窗、搜词、滚动）
- **核心**: `PhoneNowPlayingSource` 监听 `POST /api/now-playing`，注入底座播放器列表
- **手机**: `android/PhoneLyricsRelay` MediaSession 中继
- **断线**: >8s 清词；换歌防串
- 详见 PRD §5–§7

## [S3] Out of Scope

iOS、播放控制、云同步、Windows、重写歌词 UI

## Tasks

- [ ] T1: vendor lyrimuse 源码并确认可构建 — acceptance: 本机 QQ 音乐出词 (covers: S2)
- [ ] T2: Phone HTTP Provider 接入底座播放器 — acceptance: curl 推送后悬浮窗跟词 (covers: S2; depends: T1)
- [ ] T3: 设置页「手机」+ 断线清理 — acceptance: US3–US5 (covers: S2; depends: T2)
- [ ] T4: Android APK 联调 — acceptance: 真机热点全链路 (covers: S2; depends: T2)
- [ ] T5: 废弃 Electron 产品 UI，仅保留调试/中继 — acceptance: README 指向 PRD 路径 A (covers: S2; depends: T1)
