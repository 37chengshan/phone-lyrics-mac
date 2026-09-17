# 手机歌词镜像原生架构设计

日期：2026-09-17

状态：已确认方向，待实施

许可证：GPL-3.0-or-later（继承 Lyrimuse）

## 1. 产品目标

Android 手机使用 QQ 音乐播放音频，Mac 不运行本机音乐播放器。Mac 通过局域网接收手机的曲目、播放状态和精确时间轴，并使用 Lyrimuse 的原生歌词搜索、逐字同步、菜单栏与悬浮歌词界面。

产品只从手机向 Mac 单向同步播放状态。Mac 不暂停、切歌、拖动或以其他方式控制手机。首期面向个人、朋友和同学，以 GPL-3.0-or-later 开源，最低支持 macOS 14 和 Android 8.0（API 26）。

## 2. 范围

### 2.1 P0

- 原生 macOS 菜单栏应用，无 Dock 图标。
- 保留 Lyrimuse 的悬浮歌词、逐字同步、主题、拖动、锁定、单双行和歌词管理能力。
- Android QQ 音乐 MediaSession 是唯一播放数据源。
- 播放、暂停、恢复、拖动进度、切歌和停止事件实时同步。
- 正常局域网下，状态事件在 300ms 内反映到 Mac；稳态歌词误差不超过 500ms。
- Mac 广播服务，Android 自动发现；手动 IP 作为故障兜底。
- 六位一次性配对码换取设备令牌，之后自动重连。
- 手机心跳超时后停止时间轴、清除旧歌词并明确显示离线状态。

### 2.2 非目标

- Mac 控制手机播放。
- Mac 本机播放器、MediaRemote 或 AppleScript 播放源。
- iOS、Windows、Linux、云账号或云中继。
- 手机向 Mac 传输歌词正文或音频。
- 长期保留 Electron 产品界面或 sidecar 进程。

## 3. 代码库策略

采用单仓库原生整合：

```text
apps/
  macos/       Lyrimuse 上游源码及 PhonePlaybackSource 改动
  android/     PhoneLyricsRelay
packages/
  protocol/    JSON Schema、示例与兼容性测试夹具
docs/
  architecture/
  setup/
legacy/
  electron/    迁移期间的参考，不参与最终发行
```

Lyrimuse 以可追踪上游来源的 subtree 方式导入 `apps/macos/`。仓库记录上游 URL、基线提交和同步步骤，保留许可证及版权声明。对外提供修改版二进制时同步提供对应源码。

当前 Android 项目迁入 `apps/android/`。Electron、网页 UI 和旧打包脚本迁入 `legacy/electron/`；原生链路验收完成后从默认构建和 README 快速开始中移除。

## 4. 总体架构

```text
Android QQ 音乐
    │ MediaSession callbacks + heartbeat
    ▼
Android Relay
    │ HTTP/JSON over LAN + device token
    ▼
Phone Transport Server ─── Pairing / Discovery / Diagnostics
    │ validated PlaybackEnvelope
    ▼
PhonePlaybackSource
    │ authoritative PlaybackSnapshot
    ▼
PlaybackCoordinator
    ├── Lyrimuse lyrics resolution/cache
    ├── progress anchor and render tick
    ├── desktop overlay / lyrics window
    └── menu bar / settings
```

`PhonePlaybackSource` 是唯一播放源。Lyrimuse 的展示层和歌词管线只依赖统一播放快照，不直接认识 HTTP、Android 或配对令牌。

## 5. 模块边界

### 5.1 Android Playback Capture

- 只选择包名属于 QQ 音乐的有效 MediaSession。
- 订阅 MediaController 回调，播放状态、元数据和时间轴变化时立即生成事件。
- 使用 `position`、`lastPositionUpdateTime` 和 `playbackSpeed` 推算发送瞬间的位置。
- 每秒发送心跳，事件推送不等待心跳周期。
- 使用单线程有序发送队列，避免现有每个 tick 创建 Thread 导致乱序和堆积。
- 持久化已配对 Mac 的稳定标识、地址和设备令牌。

Android 不搜索歌词，不推送歌词正文，也不判断 Mac 当前歌词行。

### 5.2 Discovery Service

Mac 使用 Bonjour/mDNS 广播 `_phonelyrics._tcp` 服务，TXT 记录只包含协议版本、Mac 显示名和配对状态。Android 展示发现的 Mac，并保留手动 `host:port` 入口。

发现只解决地址定位，不代表授权。IP 变化后，Android 依据稳定服务标识更新地址，并继续使用已签发的设备令牌。

### 5.3 Pairing Service

- 未配对客户端只能访问健康检查、协议信息和配对端点。
- Mac 生成短时有效的六位配对码。
- Android 提交配对码和设备标识，成功后 Mac 签发至少 256 bit 随机令牌。
- Android 使用系统安全存储保存令牌；Mac 使用 Keychain 保存令牌摘要和设备信息。
- 播放请求使用 `Authorization: Bearer <token>`。
- Mac 可查看、重命名和撤销已配对设备。
- 配对尝试限速；令牌、Authorization header 和完整配对码不得进入日志。

P0 使用局域网 HTTP 加令牌鉴权，目标是阻止同网段设备误推送或简单冒用，不宣称抵御主动中间人。以后可在不改变播放协议的前提下升级本地 TLS。

### 5.4 Phone Transport Server

负责监听、鉴权、限制请求体、解析版本化协议、验证字段、拒绝乱序消息，并把合法消息转换为内部 `PlaybackEnvelope`。服务器运行在独立 actor/队列，不阻塞 UI 主线程。传输层不搜索歌词、不维护 UI，也不决定歌词行。

### 5.5 PhonePlaybackSource

- 将网络事件归一化为 Lyrimuse 播放快照。
- 使用 Mac 单调时钟维护进度锚点。
- 处理暂停、恢复、seek、切歌、停止和超时。
- 对短时抖动有界纠偏，避免歌词来回跳动。
- 发布连接状态和脱敏诊断信息。

歌词管线只以稳定的 `trackId`、标题、歌手、专辑和时长决定是否重新搜索。切歌时先清空旧歌词，再发布新曲目。

### 5.6 Lyrimuse Integration

保留多源歌词、评分、缓存、逐字歌词、翻译、歌词管理、窗口、外观设置、菜单栏和诊断导出。

移除或隐藏本机播放器选择、MediaRemote/AppleScript、本机播放控制、播放器自动启动和相关权限设置。

第一阶段让现有 `PlaybackCoordinator` 消费 `PhonePlaybackSource`。歌词 collector 若依赖本机播放器采集，则改为读取同一份受控手机快照，不建立第二套网络接收器。

## 6. 播放协议

```json
{
  "protocolVersion": 1,
  "sessionId": "relay-start-uuid",
  "sequence": 1842,
  "event": "heartbeat",
  "track": {
    "trackId": "qqmusic-session-derived-id",
    "title": "晴天",
    "artist": "周杰伦",
    "album": "叶惠美",
    "durationMs": 269000
  },
  "playback": {
    "state": "playing",
    "positionMs": 35214,
    "speed": 1.0,
    "capturedAtMonotonicMs": 91827364
  },
  "source": {
    "packageName": "com.tencent.qqmusic",
    "deviceId": "stable-local-device-id"
  }
}
```

- `event` 为 `trackChanged|play|pause|seek|stop|heartbeat`。
- `sessionId + sequence` 决定全序；同一 session 中非递增序号被丢弃。
- Relay 重启生成新 `sessionId`，Mac 清除上一 session 的排序状态。
- `trackId` 优先使用 QQ 音乐媒体 ID；不可得时以规范化元数据和时长生成会话内标识。
- 数值字段有范围校验，最终显示位置钳制到合理范围。
- JSON Schema 和成功/失败样例位于 `packages/protocol/`，Swift 与 Kotlin 共享测试夹具。

## 7. 时间同步算法

Android 在播放中计算：

```text
sendPosition = state.position
             + (elapsedRealtimeNow - state.lastPositionUpdateTime) * state.playbackSpeed
```

暂停或停止时不外推。时间计算只用单调时钟，墙钟只用于日志展示。

Mac 收到播放消息时建立锚点：

```text
anchor.position = received playback.positionMs
anchor.instant  = ContinuousClock.now
```

播放中按本地单调时钟和 speed 外推；暂停时固定位置。心跳到达后，绝对偏差不超过 150ms 时保持当前锚点，150–600ms 时在 1 秒内线性收敛，超过 600ms 时立即重设锚点。`seek` 和切歌无论偏差大小都立即重设。真机测量可在不改变三档语义的前提下收紧阈值；P0 不引入墙钟同步。

状态规则：

- `play`：建立锚点并开始外推。
- `pause`：立即冻结到消息位置。
- `seek`：无条件跳到消息位置。
- `trackChanged`：先清空歌词和时间轴，再加载新曲目。
- `stop`：清除播放状态，显示“等待播放”。
- 3 秒无心跳：显示连接不稳定，可继续短时外推。
- 8 秒无心跳：标记离线、停止外推、清空旧歌词。

## 8. 用户体验

首次使用流程：

1. Mac 启动并广播服务。
2. 用户在 Mac 设置中打开“连接手机”，看到六位配对码。
3. Android 自动列出 Mac；用户选择并输入配对码。
4. 配对成功后保存设备，以后在同一局域网自动重连。
5. 用户授权 Android 通知使用权并关闭电池优化。
6. 手机播放 QQ 音乐，Mac 自动显示歌词。

Mac 明确区分等待配对、已连接未播放、播放、暂停、连接不稳定、离线、鉴权失败和端口占用。诊断只显示最近合法事件时间、协议版本、包名和歌词状态，不显示令牌。

歌词匹配复用 Lyrimuse 多源评分与管理。自动匹配失败时，用户可在 Mac 手动搜索、选择或编辑，结果进入现有缓存；手机不承担歌词匹配职责。

## 9. 错误处理

- Android 无通知权限：阻止启动并给出设置入口。
- 没有 QQ 音乐会话：保持连接，显示“等待 QQ 音乐”，不回退其他 App。
- 网络失败：指数退避并保留自动发现；新播放事件触发立即重连。
- HTTP 超时：取消并串行重试，不创建无界线程。
- 无效或过大 payload：返回明确 4xx，记录脱敏原因。
- 协议不兼容：双方显示升级提示，不静默误运行。
- 端口占用：Mac 显示错误并允许换端口，Bonjour 发布实际端口。
- 歌词搜索失败：保留歌曲与状态，提供手动搜索，不显示上一首歌词。
- collector 异常：不得停止网络服务器或破坏配对状态。

## 10. 测试与验收

自动化覆盖：

- Kotlin：位置外推、QQ 会话筛选、事件、序号、重试和鉴权头。
- Swift：协议解码、验证、乱序丢弃、状态机、锚点、纠偏、超时和切歌清理。
- Swift/Kotlin 对同一组 JSON 夹具得到兼容结果。
- 模拟时钟驱动 play/pause/resume/seek/track change/disconnect。
- 错误配对码、过期码、撤销令牌、缺失令牌、限速和日志脱敏。
- 保留受影响的 Lyrimuse 上游测试。

P0 必须进行 Android 真机 + QQ 音乐 + macOS 14 联调，包括同一 Wi-Fi、手机热点、IP 变化、短暂断网、播放、暂停、恢复、拖动、切歌、锁屏、后台和系统杀进程。记录手机事件与 Mac UI 响应时间，验证 300ms 状态目标和 500ms 稳态歌词目标。悬浮窗、跨 Space、设置持久化和干净机器首次配对也必须实际运行验证，不能只看编译结果。

## 11. 迁移阶段

1. 导入并原样构建 Lyrimuse 基线。
2. 建立共享协议、模拟 PhonePlaybackSource 和状态机测试。
3. 实现 Mac 传输、发现、配对并接入 PlaybackCoordinator。
4. 改造 Android 为事件驱动捕获、有序传输、发现和配对。
5. 让歌词 collector 消费统一手机快照，移除本机播放源依赖。
6. 完成设置、诊断和错误状态。
7. 真机全链路验收与性能测量。
8. 将 Electron 迁入 legacy，更新 README、许可证、构建和发行说明。

每个阶段保持可构建、可测试并以独立提交完成。在原生链路通过真机验收前，不删除现有 Electron 原型。

## 12. 完成定义

- 新用户可在 macOS 14+ 和 Android 上完成安装与一次配对。
- Android QQ 音乐是唯一播放源，没有本机播放器入口或回退。
- 播放、暂停、恢复、seek、切歌和停止均正确反映。
- 乱序、短暂抖动和断线不会恢复旧状态或残留旧歌词。
- 自动发现、令牌验证、撤销和手动 IP 兜底可用。
- 达到状态响应与稳态误差目标。
- Mac 原生交互通过实际运行验证。
- 仓库包含 GPL 源码、上游归属、构建步骤和可复现发布说明。
