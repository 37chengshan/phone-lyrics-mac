#!/usr/bin/env bash
#
# 一条命令跑完本仓所有**自动化**闸门(计划 Task 10 Step 1)。
#
# 设计约定:
#   - 顺序执行、**遇错即停**,并明确打印是哪个子系统挂了 —— 一次跑一堆、最后只报"有失败"
#     会让人回去逐个重跑,白白多花一倍时间。
#   - 只做非破坏性的检查与构建:不装依赖、不动用户配置、不碰 launchd、不签名、不清任何东西。
#   - 不打印环境变量、令牌或任何凭据。这里也确实没有可打的东西 —— 三个子系统都不需要密钥。
#   - 真机验收(Android + QQ 音乐 + 同一局域网)不在这个脚本里,也**不可能**在这里:
#     它要一台连着同一 Wi-Fi 的手机和一次人眼观察。那部分见 docs/verification/ 的验收报告。
#
# 用法: bash scripts/verify.sh
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
MACOS_APP="$REPO_ROOT/apps/macos/lyrimuse"
COLLECTOR="$REPO_ROOT/apps/macos/lyrimuse-collector"
ANDROID="$REPO_ROOT/apps/android/PhoneLyricsRelay"

# Swift 自检会吐四千多行逐条断言。全部摊到屏幕上没有任何价值 —— 人要看的只是"过没过、
# 哪一组挂了"。所以每个子系统的输出都重定向到临时日志,只打它的汇总行;失败时把日志尾部
# 连同断言级错误一并打出来。日志目录在**失败时**另外留一份,成功时什么都不留。
LOG_DIR=$(mktemp -d "${TMPDIR:-/tmp}/phone-lyrics-verify.XXXXXX")
KEEP_LOG="${TMPDIR:-/tmp}/phone-lyrics-verify-last.log"
trap 'rm -rf "$LOG_DIR"' EXIT

step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[32m✓ %s\033[0m\n' "$1"; }
die()  { printf '\n\033[31m✗ %s\033[0m\n' "$1" >&2; exit 1; }

# run <名字> <汇总行正则> <命令...>:成功时只打汇总行,失败时打日志尾部 + 断言级错误。
run() {
    local name=$1 summary=$2; shift 2
    local log="$LOG_DIR/$name.log"
    if ! "$@" >"$log" 2>&1; then
        cp "$log" "$KEEP_LOG" 2>/dev/null || true
        printf '\n\033[31m✗ %s 失败。断言级错误:\033[0m\n' "$name" >&2
        grep -E '^(FAIL|not ok|error:|✗)' "$log" | head -20 >&2 || true
        printf '\033[31m末尾 30 行:\033[0m\n' >&2
        tail -30 "$log" >&2
        printf '\n完整日志已留在: %s\n' "$KEEP_LOG" >&2
        exit 1
    fi
    grep -E "$summary" "$log" | tail -2 || true
}

step "工具链"
printf 'swift: %s\n' "$(swift --version 2>/dev/null | head -1 || echo '缺失')"
printf 'go:    %s\n' "$(go version 2>/dev/null || echo '缺失')"
if [ -x "$ANDROID/gradlew" ]; then GRADLE=("$ANDROID/gradlew"); else GRADLE=(gradle); fi
command -v java >/dev/null || die "缺少 java"
command -v "${GRADLE[0]}" >/dev/null || die "缺少 gradle(也没有 gradlew)"
ok "工具链就位"

# 协议夹具是 Swift 与 Kotlin 共用的同一份,两边都拿它解码。夹具自己坏掉时,两侧会各自报出
# "某个字段缺失",没人会想到是夹具的问题 —— 所以先当普通 JSON 校一遍。
step "协议夹具(packages/protocol)"
FIXTURES="$REPO_ROOT/packages/protocol/fixtures"
SCHEMA="$REPO_ROOT/packages/protocol/schema/playback-envelope-v1.schema.json"
[ -f "$SCHEMA" ] || die "缺少协议 schema: $SCHEMA"
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$SCHEMA" || die "schema 不是合法 JSON"
for f in "$FIXTURES"/*.json; do
    python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$f" || die "夹具不是合法 JSON: $f"
done
ok "schema 与 $(ls "$FIXTURES"/*.json | wc -l | tr -d ' ') 份夹具都是合法 JSON"

step "Swift 自检"
run swift-selftest '组 · [0-9]+ 条断言' bash -c "cd '$MACOS_APP' && swift run lyrimuse-selftest"
ok "Swift 自检通过"

step "Swift 构建"
run swift-build 'Build complete' bash -c "cd '$MACOS_APP' && swift build"
ok "Swift 构建通过"

step "Go 采集器测试"
run go-test '^(ok|FAIL|\?)' bash -c "cd '$COLLECTOR' && go test ./..."
ok "Go 测试通过"

step "Android 单元测试"
run android-test 'BUILD SUCCESSFUL|BUILD FAILED' "${GRADLE[@]}" -p "$ANDROID" --console=plain testDebugUnitTest
ok "Android 单元测试通过"

step "Android 调试包"
run android-apk 'BUILD SUCCESSFUL|BUILD FAILED' "${GRADLE[@]}" -p "$ANDROID" --console=plain assembleDebug
APK="$ANDROID/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "构建成功却没有产物: $APK"
ok "APK: $APK"

printf '\n\033[32m全部自动化闸门通过。\033[0m\n'
printf '真机验收(Android + QQ 音乐 + Mac)仍需人工执行,清单见 docs/verification/。\n'
