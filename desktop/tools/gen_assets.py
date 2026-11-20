#!/usr/bin/env python3
"""把 app/src/main/assets/web 下的网页资源生成到 desktop/generated/web_assets.cpp（内嵌进二进制）。"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # desktop/
WEB_DIR = os.path.normpath(os.path.join(ROOT, "..", "app", "src", "main", "assets", "web"))
OUT = os.path.join(ROOT, "generated", "web_assets.cpp")


def main() -> int:
    if not os.path.isdir(WEB_DIR):
        print(f"web 目录不存在: {WEB_DIR}", file=sys.stderr)
        return 1
    names = sorted(
        n for n in os.listdir(WEB_DIR) if os.path.isfile(os.path.join(WEB_DIR, n))
    )
    if not names:
        print("web 目录为空", file=sys.stderr)
        return 1

    blocks = []
    entries = []
    for i, name in enumerate(names):
        with open(os.path.join(WEB_DIR, name), "r", encoding="utf-8") as f:
            content = f.read()
        if ")LUOBOASSET\"" in content:
            print(f"资源 {name} 含分隔符，换分隔符处理", file=sys.stderr)
            return 1
        blocks.append(
            f'static const char* kAsset{i} = R"LUOBOASSET({content})LUOBOASSET";'
        )
        entries.append(f'        {{"{name}", kAsset{i}}},')

    out = [
        "// 自动生成：tools/gen_assets.py，请勿手改",
        '#include "web_assets.h"',
        "",
        "namespace luobo {",
        "namespace web_assets {",
        "",
        *blocks,
        "",
        "const std::unordered_map<std::string, std::string>& files() {",
        "    static const std::unordered_map<std::string, std::string> kFiles = {",
        *entries,
        "    };",
        "    return kFiles;",
        "}",
        "",
        "}  // namespace web_assets",
        "}  // namespace luobo",
        "",
    ]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print(f"已生成 {OUT}（{len(names)} 个资源: {', '.join(names)}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
