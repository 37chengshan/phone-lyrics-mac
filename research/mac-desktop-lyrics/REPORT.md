# Mac 桌面歌词调研报告

**日期**: 2026-09-17  
**模式**: standard  
**路径**: `research/mac-desktop-lyrics/`

## 结论（先看这个）

1. **Mac 本机桌面歌词：直接用 [lyrimuse](https://github.com/Yudaotor/lyrimuse) v1.7.0**（已装到 `/Applications/Lyrimuse.app`）。支持 QQ 音乐、悬浮窗、菜单栏、多歌词源；比任何自研 Electron 壳成熟得多。[1][2]
2. **不要再试图“抄一个桌面歌词壳”**。LyricsX 已停更，QQ 官方歌词 API 已死，Sonoma/Sequoia 裁剪问题一堆。[3][4]
3. **手机放歌 → Mac 看词** 没有现成完整产品；这是本仓库 Android 中继的唯一合理定位。桌面 UI 不应再自研。[5]
4. **菜单栏图标看不见** 的根因（本仓库）：未 `setTemplateImage(true)`、无单实例锁、双开 Electron、未 `dock.hide()`。[6]

## 已落地动作

| 动作 | 状态 |
|------|------|
| 下载并安装 Lyrimuse v1.7.0 (arm64) | 完成，进程已启动，含 media-control MediaRemote |
| 去 quarantine | 完成 |
| 修复自研 App 托盘（template + 单实例 + dock.hide） | 完成（仅作手机中继备用） |
| 杀掉双开的 Electron 实例 | 完成 |

## 你怎么用（Mac 桌面歌词）

1. 确认菜单栏出现 **lyrimuse** 图标（可能在右侧；图标多时用 Ice/隐藏条管理器腾位置）。
2. 打开 **QQ 音乐 Mac 版** 并播放任意歌。
3. lyrimuse 会自动出悬浮歌词；在其设置里可改字号、单双行、置顶、锁定。

若被 Gatekeeper 拦：`xattr -dr com.apple.quarantine /Applications/Lyrimuse.app`（已执行）。

## 手机场景（仍保留）

- `dist/PhoneLyricsRelay-debug.apk` + 本仓库 HTTP `:8765` 仅用于 **手机 Now Playing 推送**。
- 桌面展示层不要和 lyrimuse 抢：本机 QQ 音乐用 lyrimuse；手机推送时才用「手机歌词镜像」窗。

## 开源问题摘录

- LyricsX 作者停更、替代列表：issue #640 [3]
- QQ 歌词 API 失效 → 优先酷狗/多源：issue #601 [4]
- Sequoia 悬浮歌词裁剪：#634/#636/#639/#642/#646 [3]

## Sources

1. https://github.com/Yudaotor/lyrimuse （read 2026-09-17）
2. https://github.com/Yudaotor/lyrimuse/releases/tag/v1.7.0 （read 2026-09-17）
3. https://github.com/ddddxxx/LyricsX/issues/640
4. https://github.com/ddddxxx/LyricsX/issues/601
5. findings/F3.md（本仓库）
6. findings/F4.md（Electron Tray 配方）

## Open questions

- 手机中继若要嵌进 lyrimuse，需要改 Swift 源码加 Network NowPlaying provider（未做）。
- 本机 QQ 音乐在用户机器上是否已向 MediaRemote 上报（lyrimuse 启动后应能识别；若不能看其播放器健康页）。
