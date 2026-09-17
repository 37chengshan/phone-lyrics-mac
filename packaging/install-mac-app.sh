#!/usr/bin/env bash
# Install double-clickable Mac app into /Applications (symlink-free copy of launcher).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/packaging/mac/PhoneLyrics镜像.app"
DEST="/Applications/手机歌词镜像.app"

chmod +x "$SRC/Contents/MacOS/PhoneLyrics镜像"
if [[ ! -x "$ROOT/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron" ]]; then
  echo "请先执行 npm install"
  exit 1
fi

rm -rf "$DEST"
cp -R "$SRC" "$DEST"
# Rewrite launcher to point at this repo absolute path
cat > "$DEST/Contents/MacOS/PhoneLyrics镜像" <<EOF
#!/bin/bash
set -euo pipefail
ELECTRON="$ROOT/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron"
cd "$ROOT"
exec "\$ELECTRON" "$ROOT"
EOF
chmod +x "$DEST/Contents/MacOS/PhoneLyrics镜像"
echo "已安装: $DEST"
echo "首次打开若被拦截：右键 → 打开"
