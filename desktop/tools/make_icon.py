#!/usr/bin/env python3
"""从安卓端新图标生成桌面端 PNG / ICO（保持三端图标一致）。"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.normpath(os.path.join(
    ROOT, "..", "app", "src", "main", "res", "mipmap-xxxhdpi", "ic_launcher.png"
))
OUT = os.path.join(ROOT, "generated", "luobo.png")
ICO = os.path.join(ROOT, "generated", "luobo.ico")


def main() -> int:
    img = Image.open(SRC).convert("RGBA")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    master = img.resize((256, 256), Image.LANCZOS)
    master.save(OUT)
    master.save(ICO, sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
    print(f"已生成 {OUT} / {ICO}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
