# 手机歌词镜像 Phone Lyrics Mac

手机放 QQ 音乐（音质好），Mac 学习时不开 QQ 音乐——把**正在播放歌词**实时映射到 Mac 桌面悬浮窗，菜单栏常驻。

## 架构

```
Android QQ 音乐
   │  MediaSession（标题/歌手/进度）
   ▼
PhoneLyricsRelay（前台服务，1s POST）
   │  LAN HTTP  http://<mac-ip>:8765/api/now-playing
   ▼
Mac Electron「手机歌词镜像」
   ├─ 托盘常驻
   ├─ 透明置顶歌词窗（单/双行、拖动、缩放、锁定）
   ├─ 设置页：连接状态 / 端口 / 字号 / 排版
   └─ 拉取 LRC（QQ 音乐 → LRCLIB 回退）并按进度对齐
```

GitHub 检索结论：`lyrimuse` / `LyricsX` 等读**本机播放器**，无「手机→Mac」高度重合项目，故自研。

## 快速开始

### 1. Mac 端

```bash
npm install
npm start          # 菜单栏图标 + 设置窗 + 悬浮歌词窗
```

浏览器预览同一套 UI（无托盘/无真实监听）：

```bash
open index.html
# 或 python3 -m http.server 5173
```

### 2. Android 中继

1. 用 Android Studio 打开 `android/PhoneLyricsRelay`
2. 安装到手机（需 **设置 → 通知使用权** 授予「歌词中继」）
3. Mac 与手机同一热点；在 App 填入设置页显示的 IP（热点下常见 `172.20.10.x`）
4. 启动中继 → 手机播 QQ 音乐 → Mac 悬浮窗跟词

### 3. 协议

`POST /api/now-playing`

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

`GET /api/status` → 连接状态与本机 LAN IP。

调试：

```bash
curl -s -X POST http://127.0.0.1:8765/api/now-playing \
  -H 'Content-Type: application/json' \
  -d '{"title":"晴天","artist":"周杰伦","durationMs":269000,"positionMs":20000,"state":"playing"}'
```

## 设置项

| 项 | 说明 |
|----|------|
| 手机连接 | 在线/离线、当前曲目 |
| 端口 | 默认 8765，可改并重启监听 |
| 字号 | 16–48px |
| 排版 | 单行 / 双行（当前句 + 下一句） |
| 锁定 | 锁定后点击穿透，不挡桌面操作 |
| 演示模式 | 内置歌词，无需手机 |

## 目录

```
electron/          Mac 主进程（托盘、悬浮窗、HTTP）
src/overlay/       歌词窗 UI
src/settings/      设置页 UI
shared/lrc.js      LRC 解析（浏览器/Node 共用）
index.html         浏览器演示入口
android/           MediaSession 中继
docs/compose/spec/ 设计与任务
```

## 限制

- iOS 未做（需另做 Shortcuts/通知读取，进度不实时）
- 歌词依赖公开搜索接口，失败会回退 LRCLIB / 演示词
- 首次使用需给 Android 通知使用权，否则读不到 MediaSession
