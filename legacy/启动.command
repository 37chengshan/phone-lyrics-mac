#!/bin/bash
cd "$(dirname "$0")"
if [[ ! -x node_modules/electron/dist/Electron.app/Contents/MacOS/Electron ]]; then
  echo "首次运行：安装依赖…"
  npm install
fi
exec ./node_modules/.bin/electron .
