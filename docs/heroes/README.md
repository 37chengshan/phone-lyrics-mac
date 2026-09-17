# README 大图（hero composites）的渲染源

`docs/images/hero-*.png`（三份 README 顶部的四张合成大图）由本目录的 HTML 排版源 +
无头 Chrome 渲染而来。截图素材换了、或界面明显变化要重出图时，改 `docs/images/` 里对应的
原始截图（HTML 按相对路径引用它们），然后重渲：

> **例外：`hero-surfaces.png` / `hero-surfaces.zh-CN.png`**（2026-09-13 起）不再由 `hero-surfaces.html`
> 渲染，而是宣传横幅（真机截图 + HTML/CSS 合成；1920×960 是 3840×2160 主图的 2:1 版）。英文版给
> `README.md` 与 `README.zh-Hant.md`，中文版给 `README.zh-CN.md`。生成器与截图素材在仓库外
> 生成器（`gen_real4.py`）、一键出图脚本（`导出各尺寸.sh`）、真机截图与素材放在作者本地的宣传横幅工具目录里，
> 不进仓库。同一张图的其它尺寸——GitHub 社交预览 1280×640、落地页 og 1200×630、Product Hunt 1270×760——
> 也从那里出。`hero-surfaces.html` 是旧的四格拼贴版，留作回滚参考，
> 重渲时跳过下面第一条命令。

```bash
C="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
H="$(cd "$(dirname "$0")" 2>/dev/null; pwd)/docs/heroes"   # 或直接写仓库绝对路径
# hero-surfaces.png 已改为宣传横幅（见上方例外），下面这条只在要回滚到旧拼贴版时用
# "$C" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1.5 \
#   --window-size=1280,640 --screenshot=docs/images/hero-surfaces.png  "file://$H/hero-surfaces.html"
"$C" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1.5 \
  --window-size=1280,820 --screenshot=docs/images/hero-engine.png    "file://$H/hero-engine.html"
"$C" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1.5 \
  --window-size=1280,900 --screenshot=docs/images/hero-profile.png   "file://$H/hero-profile.html"
"$C" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1.5 \
  --window-size=1280,900 --screenshot=docs/images/hero-customize.png "file://$H/hero-customize.html"
```

要点：

- **画布高度（`--window-size` 第二个数）跟内容量挂钩**——某张图里加减了卡片，先渲一版看
  底部留白/溢出，再微调高度。
- 1.5x 缩放是体积和清晰度的折中（每张约 1.1MB）；别回 2x，四张合计会超 7MB。
- `assets/fresh-overlay-word-highlight.png` 是悬浮歌词的真实透明捕获（逐字染色进行到一半，
  富士山下）。重拍方法见 `.claude/skills/lyrimuse-verify-ui`：播歌 → `check-windows.swift`
  拿 overlay 窗口 ID → `screencapture -x -o -l <id>`，再 `sips -c 286 1150` 居中裁边。
- gh-pages 落地页（yudaotor.github.io/lyrimuse）用的是同一批 `docs/images/` 截图的副本，
  换图时那边 `assets/img/` 也要同步（见 docs/releasing.md「同步对外物料」）。
