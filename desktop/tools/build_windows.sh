#!/usr/bin/env bash
# 用 zig 交叉编译 Windows 便携单文件 exe（无控制台窗口）
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tools/gen_assets.py

ZIG_BIN="${ZIG:-/home/xiuufa/tools/zig/zig}"
if [ ! -x "$ZIG_BIN" ]; then
  echo "找不到 zig：请设置 ZIG=/path/to/zig 或解压 zig 到 /home/xiuufa/tools/zig" >&2
  exit 1
fi
mkdir -p dist
OUT="dist/LuoboKuaichuan.exe"
python3 tools/make_icon.py
RES_ARGS=()
if "$ZIG_BIN" rc /i generated /fo dist/app.res tools/app.rc >/dev/null 2>&1; then
  RES_ARGS=(dist/app.res)
  echo "[build] 已嵌入 exe 图标"
else
  echo "[build] 警告：资源编译失败，exe 将没有自定义图标"
fi

"$ZIG_BIN" c++ -target x86_64-windows-gnu -static -Wl,--subsystem,windows -std=c++17 -O2 -pthread \
  -D_WIN32_WINNT=0x0A00 -DWINVER=0x0A00 \
  -Ithird_party -Igenerated -Isrc \
  src/*.cpp third_party/qrcodegen.cpp generated/web_assets.cpp "${RES_ARGS[@]}" -lws2_32 -liphlpapi -o "$OUT"
ls -lh "$OUT"
