# Brief: Mac 桌面 QQ 音乐歌词 — 成熟方案与落地路径

**Date**: 2026-09-17
**Depth**: standard
**Decision**: 在 Mac 上可靠显示桌面悬浮歌词（含菜单栏常驻），优先「直接可用的成熟项目」，其次才是最小自研补丁（手机→Mac 推送）。

## Refined question
1. 2026 年 macOS 上成熟、可直接安装的桌面歌词项目有哪些？是否支持 QQ 音乐、菜单栏、悬浮窗？
2. 它们如何拿到「正在播放」与歌词（MediaRemote / AppleScript / 网络源）？有何已知坑？
3. 用户场景「手机 QQ 音乐放歌、Mac 只看词」在开源界如何做？有无现成中继？
4. 菜单栏（status item）在 Electron/原生里为何会「没有图标」？正确做法？
5. 最短落地路径：直接装 lyrimuse/LyricsX vs 继续自研 Electron？

## Out of scope
- App Store 上架、公证
- Windows/Linux
- 逐字卡拉 OK 完整实现细节

## Assumptions
- 本机是 Apple Silicon Mac（Darwin arm64），已有 QQMusic.app
- 用户网络可访问 GitHub Releases
- 优先零编译安装（dmg/zip），源码构建仅作备选

## Angles
- F1: 成熟 Mac 桌面歌词项目（lyrimuse / LyricsX / LyricFever 等）功能对照与安装包
- F2: 正在播放数据源（MediaRemote/media-control/AppleScript）与 QQ 音乐兼容性
- F3: 手机→Mac 歌词/Now Playing 中继的开源做法
- F4: macOS 菜单栏图标在 Electron 的常见失败原因与修复
- F5: 实践反馈：GitHub Issues 里 QQ 音乐/悬浮窗/托盘的真实问题
