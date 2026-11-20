#!/usr/bin/env bash
# 编译 Linux 单文件二进制（优先用 zig 静态 musl，缺省用系统 g++）
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tools/gen_assets.py

ZIG_BIN="${ZIG:-/home/xiuufa/tools/zig/zig}"
mkdir -p dist
OUT="dist/luobo-linux-x86_64"
SOURCES=(src/*.cpp third_party/qrcodegen.cpp generated/web_assets.cpp)

if [ -x "$ZIG_BIN" ]; then
  echo "[build] 使用 zig 静态 musl 交叉编译"
  "$ZIG_BIN" c++ -target x86_64-linux-musl -static -std=c++17 -O2 -pthread \
    -Ithird_party -Igenerated -Isrc "${SOURCES[@]}" -o "$OUT"
else
  echo "[build] 未找到 zig，使用系统 g++（动态链接，仅适合本机/AppImage 打包）"
  g++ -std=c++17 -O2 -pthread -static-libstdc++ -static-libgcc \
    -Ithird_party -Igenerated -Isrc "${SOURCES[@]}" -o "$OUT"
fi
strip "$OUT" 2>/dev/null || true
ls -lh "$OUT"
