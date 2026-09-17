# 手机歌词镜像 · Phone Lyrics Mac

手机用 QQ 音乐放歌,Mac 上用原生应用显示歌词 —— 桌面悬浮歌词、逐字同步、菜单栏与灵动岛。

Mac 不运行任何音乐播放器,只通过局域网接收手机的播放状态。**单向同步:Mac 只显示,不控制手机。**

## 它是什么

本仓库是 [Lyrimuse](https://github.com/Yudaotor/lyrimuse) 的修改版(见 `NOTICE`),保留了它的
多源歌词解析与评分、逐字歌词、翻译、罗马音、繁简转换、歌词管理、主题与菜单栏界面,把**播放源**
换成 Android 手机上的 QQ 音乐。

```text
Android QQ 音乐
   │ MediaSession 回调 + 每秒心跳
   ▼
PhoneLyricsRelay(Android)
   │ 局域网 HTTP + 设备令牌
   ▼
Phone Transport Server ── 配对 / Bonjour 发现 / 诊断
   │ 校验过的 PlaybackEnvelope
   ▼
PhonePlaybackSource
   ├─ Lyrimuse 歌词解析与缓存(Go collector)
   ├─ 桌面悬浮歌词 / 歌词窗口
   └─ 菜单栏 / 灵动岛 / 设置
```

## 快速开始

前置:macOS 14+、Android 8.0+、两台设备在同一局域网。

### 1. 构建并启动 Mac 应用

```bash
bash apps/macos/lyrimuse/build.sh --no-restart     # 构建 + 装到 /Applications
```

只想在临时目录里试跑、不碰 `/Applications`:

```bash
bash apps/macos/lyrimuse/build.sh --dest /tmp/Lyrimuse.app
open /tmp/Lyrimuse.app
```

### 2. 构建并安装 Android 中继

```bash
cd apps/android/PhoneLyricsRelay
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 配对

1. Mac 打开「设置 → 手机连接」,点「生成配对码」,得到 6 位码(5 分钟内有效,只能用一次)。
2. Android 端从发现的列表里选这台 Mac,输入该码。
3. 成功后设备令牌存进两端的安全存储,以后同一局域网自动重连。
4. Android 端授予**通知使用权**(读 QQ 音乐 MediaSession 必需),并关闭电池优化。

### 4. 播放

手机上用 QQ 音乐放歌,Mac 即自动显示歌词。

自动发现不可用时,在 Mac 的「手动连接备用地址」里能看到本机局域网地址,填进 Android 端的手动
入口即可 —— 端口默认 `8765`。

## 命令

```bash
bash scripts/verify.sh                          # 全部自动化闸门(见下)

cd apps/macos/lyrimuse && swift run lyrimuse-selftest   # Swift 自检
cd apps/macos/lyrimuse && swift build                   # Swift 构建
cd apps/macos/lyrimuse-collector && go test ./...       # 歌词采集器
cd apps/android/PhoneLyricsRelay && gradle testDebugUnitTest
```

## 目录

```text
apps/macos/lyrimuse/            Swift 应用(来自 Lyrimuse 上游)
apps/macos/lyrimuse-collector/  Go 歌词解析与缓存
apps/android/PhoneLyricsRelay/  Android 伴侣应用
packages/protocol/              协议 schema 与两端共用的夹具
docs/upstream/                  上游基线与同步步骤
docs/verification/              验收证据与未执行项
legacy/electron/                早期 Electron 原型(不参与发行)
```

## 协议

`packages/protocol/schema/playback-envelope-v1.schema.json` 是线上格式的唯一真源,Swift 与 Kotlin
两侧共用 `packages/protocol/fixtures/` 里的同一批夹具。要点:

- `sessionId + sequence` 决定全序,同会话内非递增序号被丢弃;中继重启换 `sessionId`。
- 事件为 `trackChanged|play|pause|seek|stop|heartbeat`,状态为 `playing|paused|stopped`。
- 数值有范围校验,来源包名必须匹配 `^com\.tencent\.qqmusic`。
- 播放请求带 `Authorization: Bearer <token>`;令牌、请求头与完整配对码不进入任何日志。

## 验收

`bash scripts/verify.sh` 顺序跑协议夹具校验、Swift 自检与构建、Go 测试、Android 单元测试与调试包
构建,遇错即停并指出是哪个子系统。全部通过约 15 秒。

**真机验收仍需人工执行**:需要一台连着同一局域网的 Android 手机与一次人眼观察,自动化脚本无法
替代。已执行的运行时证据与未执行项都记在 `docs/verification/2026-09-17-native-phone-lyrics.md`。

## 许可

GPL-3.0-or-later。本仓库是 Lyrimuse 的修改版,上游归属、基线提交与改动清单见 `NOTICE`;
完整许可见 `LICENSE`,上游随附的第三方许可见 `apps/macos/THIRD_PARTY_LICENSES`。


