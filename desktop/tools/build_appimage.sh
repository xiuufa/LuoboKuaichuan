#!/usr/bin/env bash
# 打 Linux AppImage：单文件、双击运行、自动打开浏览器
set -euo pipefail
cd "$(dirname "$0")/.."
bash tools/build_linux.sh
python3 tools/make_icon.py

APPDIR="dist/LuoboKuaichuan.AppDir"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/share/applications" \
         "$APPDIR/usr/share/icons/hicolor/256x256/apps"
cp dist/luobo-linux-x86_64 "$APPDIR/usr/bin/luobo"
cp generated/luobo.png "$APPDIR/usr/share/icons/hicolor/256x256/apps/luobo.png"
cp generated/luobo.png "$APPDIR/luobo.png"
ln -sf luobo.png "$APPDIR/.DirIcon"

cat > "$APPDIR/LuoboKuaichuan.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=萝卜快传
Comment=局域网共享传输板
Exec=luobo
Icon=luobo
Terminal=false
Categories=Utility;Network;FileTransfer;
EOF
cp "$APPDIR/LuoboKuaichuan.desktop" "$APPDIR/usr/share/applications/"

cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/luobo" "$@"
EOF
chmod +x "$APPDIR/AppRun"

OUT="dist/LuoboKuaichuan-x86_64.AppImage"
TOOL="${APPIMAGETOOL:-/home/xiuufa/tools/appimagetool-x86_64.AppImage}"
if [ -x "$TOOL" ]; then
  # 首选官方 appimagetool（会自动补 .desktop/.icon 等元数据）
  ARCH=x86_64 "$TOOL" --appimage-extract-and-run "$APPDIR" "$OUT"
else
  # 没有 appimagetool 时手动拼一个 type-2 AppImage：runtime + squashfs
  RUNTIME="${APPIMAGE_RUNTIME:-/home/xiuufa/tools/runtime-x86_64}"
  if [ ! -f "$RUNTIME" ]; then
    echo "缺少 appimagetool / runtime，请设置 APPIMAGETOOL 或 APPIMAGE_RUNTIME" >&2
    exit 1
  fi
  echo "[build] 手动组装 AppImage（runtime + squashfs）"
  rm -f dist/image.squashfs "$OUT"
  mksquashfs "$APPDIR" dist/image.squashfs -root-owned -noappend -comp zstd -quiet
  cat "$RUNTIME" dist/image.squashfs > "$OUT"
  rm -f dist/image.squashfs
  chmod +x "$OUT"
fi
ls -lh "$OUT"
