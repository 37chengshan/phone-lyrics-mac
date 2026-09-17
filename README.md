# Phone Lyrics Mac · 手机歌词镜像

手机放 QQ 音乐,Mac 显示歌词 —— 桌面悬浮、逐字同步、菜单栏与灵动岛。

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-macOS%2014%2B-lightgrey.svg)](#环境要求)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](#环境要求)

Mac 上不运行任何音乐播放器。手机通过局域网把播放状态推过来,Mac 只负责显示 ——
**单向同步:Mac 只显示,不控制手机。**

---

## 为什么做这个

手机端的音乐 App 音质和曲库都更好,但学习或工作时不想拿起手机看歌词。现有方案要么让 Mac
自己播歌(占用音质与曲库都更差的桌面端),要么只能显示纯文本歌词。

这个项目把两者接起来:手机继续放,Mac 显示同步歌词。

## 功能

**歌词显示**

- 桌面悬浮歌词:逐字染色、翻译、罗马音、简繁转换、单双行、可拖动可锁定、点击穿透
- 菜单栏歌词:滚动显示,悬停可展开
- 灵动岛歌词(带刘海的 Mac)
- 歌词窗口:完整可滚动歌词列表

**歌词来源**

- 内置十个歌词源:网易云音乐、QQ 音乐、酷狗音乐、酷我音乐、咪咕音乐、Deezer、Musixmatch、LRCLIB、AMLL、LyricFind
- 多源评分与择优,可用性测试,手动搜索与选择
- 本地歌词库管理、备份与恢复

**其他**

- 外观:配色主题、自定义颜色、字体字号、背景材质
- 收听记录:可选同步到 Last.fm / ListenBrainz

## 它是怎么工作的

```text
Android QQ 音乐
   │ MediaSession 回调 + 每秒心跳
   ▼
PhoneLyricsRelay(Android 伴侣应用)
   │ 局域网 HTTP + 设备令牌
   ▼
Phone Transport Server  ──  配对 / Bonjour 发现 / 诊断
   │ 校验过的播放事件
   ▼
PhonePlaybackSource
   ├─ 歌词解析与缓存(Go 采集器)
   ├─ 桌面悬浮歌词 / 歌词窗口
   └─ 菜单栏 / 灵动岛 / 设置
```

## 环境要求

| | 要求 |
| --- | --- |
| Mac | macOS 14(Sonoma)或更高,Apple Silicon |
| Android | Android 8.0(API 26)或更高 |
| 网络 | 两台设备在**同一个局域网** |

## 快速开始

### 1. 构建 Mac 端

```bash
git clone https://github.com/37chengshan/phone-lyrics-mac.git
cd phone-lyrics-mac
bash apps/macos/lyrimuse/build.sh --no-restart
```

构建完成后 App 会装到 `/Applications/Lyrimuse.app`,在菜单栏出现图标(不占 Dock)。

只想在临时目录试跑、不碰 `/Applications`:

```bash
bash apps/macos/lyrimuse/build.sh --dest /tmp/Lyrimuse.app
open /tmp/Lyrimuse.app
```

### 2. 构建 Android 端

```bash
cd apps/android/PhoneLyricsRelay
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 授予权限

打开 Android 上的「歌词中继」,到**权限**页把两项都点亮:

1. **通知使用权** —— 没有它读不到 QQ 音乐在放什么
2. **电池优化** —— 不关的话后台跑一会儿会被系统杀掉

### 4. 配对

1. Mac:菜单栏图标 → 设置 →「手机连接」→「生成配对码」,得到 6 位数字(5 分钟内有效)
2. Android:「连接」页 → 先点「测试连接」确认地址可达
3. 填入那 6 位码 →「配对并保存」

配对成功后设备令牌存进两端的安全存储,以后同一局域网自动重连。

> **自动发现不可用时**:Mac 设置页的「手动连接备用地址」会列出本机局域网地址,填进 Android
> 的手动入口即可 —— 端口默认 `8765`。

### 5. 开始

Android 点「开始同步」,然后用 QQ 音乐放歌,Mac 就会显示歌词。

## 命令

```bash
bash scripts/verify.sh                                   # 全部自动化闸门(约 15 秒)

cd apps/macos/lyrimuse && swift run lyrimuse-selftest    # Swift 自检
cd apps/macos/lyrimuse && swift build                    # Swift 构建
cd apps/macos/lyrimuse-collector && go test ./...        # 歌词采集器
cd apps/android/PhoneLyricsRelay && gradle testDebugUnitTest
```

## 目录

```text
apps/macos/lyrimuse/            Swift 应用(源自 Lyrimuse)
apps/macos/lyrimuse-collector/  Go 歌词解析与缓存
apps/android/PhoneLyricsRelay/  Android 伴侣应用
packages/protocol/              协议 schema 与两端共用的测试夹具
docs/upstream/                  上游基线与同步步骤
docs/verification/              验收证据(含未执行项)
legacy/electron/                早期 Electron 原型(不参与发行)
```

## 播放协议

`packages/protocol/schema/playback-envelope-v1.schema.json` 是线上格式的唯一真源,Swift 与 Kotlin
两侧共用 `packages/protocol/fixtures/` 里的同一批夹具。

- `sessionId + sequence` 决定全序;中继重启换 `sessionId`
- 事件:`trackChanged` / `play` / `pause` / `seek` / `stop` / `heartbeat`
- 状态:`playing` / `paused` / `stopped`
- 播放请求带 `Authorization: Bearer <token>`;令牌、请求头与配对码不写入任何日志

## 验证

`bash scripts/verify.sh` 顺序执行协议夹具校验、Swift 自检与构建、Go 测试、Android 单元测试与
调试包构建,遇错即停并指出是哪个子系统。全部通过约 15 秒。

**真机验收仍需人工执行** —— 需要一台连着同一局域网的 Android 手机与一次人眼观察,自动化
脚本无法替代。已执行的运行时证据与尚未执行的项目都记在
[验收报告](docs/verification/2026-09-17-native-phone-lyrics.md)里。

## 已知限制

- 仅支持 **Android 上的 QQ 音乐**作为播放源,不回退到其他 App、也不支持 iOS
- Mac 不会控制手机播放(没有播放/暂停/上一首/下一首,拖进度条无效)—— 这是设计如此
- 依赖局域网,不支持跨网络或云中继
- 词匹配依赖公开接口,冷门曲目可能找不到歌词;可用 **歌词管理**手动搜索或编辑
- 手机在后台被系统回收时需要手动重开(已内置保活措施,但厂商 ROM 策略各异)

## 相关文档

- [上游基线与同步步骤](docs/upstream/lyrimuse.md)
- [验收报告](docs/verification/2026-09-17-native-phone-lyrics.md)
- [架构设计](docs/superpowers/specs/2026-09-17-phone-lyrics-native-architecture-design.md)

## 致谢

本项目的 Mac 端基于 [Lyrimuse](https://github.com/Yudaotor/lyrimuse) 修改,保留了它的歌词解析、
评分、字体与外观等绝大部分实现。上游是一个出色的纯本机桌面歌词应用,推荐一试。

## 许可与版权说明

本项目以 **GPL-3.0-or-later** 授权。

它是一个基于 Lyrimuse 的衍生作品,按 GPL-3.0 的要求:

- 保留上游的许可证、版权声明与第三方许可清单(见 [`apps/macos/THIRD_PARTY_LICENSES`](apps/macos/THIRD_PARTY_LICENSES))
- 修改内容与完整源码在本仓库公开
- 分发的二进制对应本仓库源码

上游项目:Lyrimuse — https://github.com/Yudaotor/lyrimuse(基线提交 `e6bdf6a`)。
本项目所做的改动见 [NOTICE](NOTICE)。

## License and Copyright

This project is licensed under **GPL-3.0-or-later**.

It is a modified version of Lyrimuse. Per GPL-3.0, the upstream license, copyright notices and
third-party license list are retained (see [`apps/macos/THIRD_PARTY_LICENSES`](apps/macos/THIRD_PARTY_LICENSES)),
the changes are documented in [NOTICE](NOTICE), and the complete corresponding source is published
in this repository.

Upstream: Lyrimuse — https://github.com/Yudaotor/lyrimuse (baseline commit `e6bdf6a`).

