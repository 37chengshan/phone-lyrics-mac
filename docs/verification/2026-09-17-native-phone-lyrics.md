# 原生手机歌词镜像 — 验证报告

日期:2026-09-17
执行环境:macOS(Apple Silicon,Swift 6.2.4 / Go 1.26.2 工具链 + go1.24.4 构建工具链 / JDK 17 / Gradle 9.5)

本报告如实区分**已执行并取得证据**的项目与**未执行**的项目。计划 Task 10 要求真机验收,
但本次执行环境没有连接 Android 设备(`adb devices` 为空、无可用模拟器),因此真机矩阵
未执行,见最后一节。**不把未跑的项目写成通过。**

## 1. 自动化闸门(全部通过)

入口:`bash scripts/verify.sh`(遇错即停,只打印每个子系统的汇总行,失败时展开断言级错误)。

| 子系统 | 命令 | 结果 |
| --- | --- | --- |
| 协议夹具 | `python3 -c 'json.load(...)'` × schema + 5 夹具 | 5 份夹具与 schema 均为合法 JSON |
| Swift 自检 | `swift run lyrimuse-selftest` | 29 组 · 4453 条断言 · ALL PASS |
| Swift 构建 | `swift build` | Build complete |
| Go 采集器 | `go test ./...` | ok(30.8s) |
| Android 单元测试 | `gradle testDebugUnitTest` | BUILD SUCCESSFUL(5 个用例 0 失败) |
| Android 调试包 | `gradle assembleDebug` | `apps/android/PhoneLyricsRelay/app/build/outputs/apk/debug/app-debug.apk`(5.6 MB) |

## 2. Mac 打包与运行时验收(已执行)

### 2.1 打包

`bash apps/macos/lyrimuse/build.sh --dest <tmp>` 完整跑通:Swift release 构建、Go collector
构建、`.app` 组装、嵌套二进制与 Sparkle 框架签名、架构校验、collector/App 版本一致性校验。

> 附带修复:本仓由 subtree 导入、**没有任何 git tag**,而 `build.sh` 取版本号的那行在
> `set -euo pipefail` 下会被 `git describe` 的非零退出掐断,导致打包直接失败(退出码 128),
> 它注释里承诺的"拿不到 tag 就退到 0.0.0"从未生效。已修,并在打包产物里验证到
> `版本一致 App=0.0.0 collector=0.0.0`。

### 2.2 打包产物真实启动(实际运行,非仅构建)

启动打包好的 `.app`,进程、collector 子进程、media-control 适配器均正常拉起,
`lsof` 确认监听 `*:8765`。

| 端点 | 请求 | 实测 |
| --- | --- | --- |
| `GET /api/v1/health` | 无鉴权 | `{"ok":true}` HTTP 200 |
| `GET /api/v1/info` | 无鉴权 | `{"protocolVersion":1,"service":"phone-lyrics"}` HTTP 200 |
| `POST /api/v1/playback` | 无令牌 | HTTP 401 |
| `POST /api/v1/playback` | 坏 payload + 合法令牌 | HTTP 400 |
| `GET /api/v1/未知` | — | HTTP 404 |

### 2.3 配对与播放(端到端,真实 UI 触发)

1. 在真实设置窗口点「生成配对码」→ 界面显示 6 位码及五分钟有效说明。
2. 用该码 `POST /api/v1/pair` → 返回 64 字符令牌(`deviceId`/`protocolVersion` 齐全)。
3. 同码重放 → HTTP 401(一次性消费生效)。
4. 带令牌推送协议夹具 → HTTP 200,且共享快照 `~/.config/lyrimuse/phone-now-playing.json`
   写出了正确内容(晴天 / 周杰伦 / 叶惠美 / 位置按锚点外推)。
5. 相同序列号重放 → 被乱序保护丢弃(快照序号不前进),符合 `sessionId + sequence` 全序约定。
6. 「已配对设备」区出现设备名与「移除」按钮。

### 2.4 连接状态机(真实 UI 观察,四种状态全部走到)

| 状态 | 触发方式 | 实测界面 |
| --- | --- | --- |
| 等待手机 | 尚无事件 | 显示「等待手机」 |
| 连接不稳定 | 停发心跳 3–8 秒 | 显示「连接不稳定」+ Wi-Fi 感叹号图标 |
| 手机已离线 | 停发心跳 > 8 秒 | 显示「手机已离线」+ 斜杠图标,旧歌词清除 |
| 手机已连接 | 持续每秒心跳 | 显示「手机已连接」+ 雷达图标 |

### 2.5 手机连接页(真实窗口)

实测存在且正确:手机 QQ 音乐状态卡、自动发现(显示「已通过 Bonjour 发布」)、配对卡、
手动连接备用地址(列出真实局域网地址 + 端口)、已配对设备卡、后台采集服务卡。
**没有**本机播放器入口、Apple Music 自动化权限、MediaRemote 健康检查或播放/下一首/上一首控件。

## 3. 本次验证发现并修复的两个缺陷

### 3.1 手机来源被判为"非关心来源"(严重)

`isKnownPlayerBundleID` 只认 Mac 版 QQ 音乐 `com.tencent.QQMusicMac`,而 Android 端上报的是
`com.tencent.qqmusic`。于是 `isTracked()` 把**每一拍**都判成"不是我关心的来源",歌词永不解析。

这个缺陷的症状极具迷惑性:配对成功、心跳正常、快照内容正确、界面显示手机已连接且曲目正确 ——
唯一表现是歌词不出现,而这条线索指向不了 bundle id 比较。

证据(修复前 → 修复后,同一份手机快照喂给 collector):

| | 修复前 | 修复后 |
| --- | --- | --- |
| `lyrimuse-enrich-cache.json` | 不生成 | 24 KB,1 条,含歌词正文 |
| `lyrics/` 目录 | 不生成 | `周杰伦 - 晴天 - 叶惠美.yrc`(逐字)+ `.lrc`(行级) |
| 解析结果 | 无 | `[00:00.00]晴天 - 周杰伦 (Jay Chou)` … |

同时把手机来源标成 `QQ Music (Android)`,否则会落到未知播放器兜底,把每次收听在
Last.fm / ListenBrainz 的来源统计里都写成别的播放器。两个测试钉住这两半。

### 3.2 `build.sh` 无 tag 时直接失败(中等)

见 2.1 的引用块。已修并验证。

## 4. 未执行的项目(环境不具备)

以下属于计划 Task 10 的范围,但**本次没有执行**,不能算作通过:

- **Android 真机矩阵**:`adb devices` 为空、无可用模拟器,因此播放/暂停/恢复/拖动/切歌/停止、
  锁屏、后台、QQ 音乐重启、中继重启、Wi-Fi 与手机热点切换、IP 变化、错误配对码、撤销令牌、
  手动地址兜底,全部未在真机执行。
- **真实 Android 端 QQ 音乐事件**:2.3 用的是符合协议夹具的模拟事件流,不是 QQ 音乐真实
  MediaSession 回调。
- **同步精度测量**(状态响应 ≤300ms、稳态歌词误差 ≤500ms):未测量,需要真机与秒表级采样。
- **macOS 界面其它状态**:暗色外观、跨 Space、点击穿透与锁定、重启后设置持久化、
  悬浮窗与歌词窗口的完整走查,仅验证了「手机连接」页。

这些项目的执行步骤见计划 Task 10 的第 3、4、5 步。

## 5. 复现方式

```bash
bash scripts/verify.sh
```

打包并本地运行(不安装到 /Applications):

```bash
bash apps/macos/lyrimuse/build.sh --dest /tmp/Lyrimuse.app
open /tmp/Lyrimuse.app
curl -s http://127.0.0.1:8765/api/v1/health
```


