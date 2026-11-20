# 桌面端（Windows exe / Linux AppImage）

萝卜快传的桌面版：**单文件、免安装、绿色便携**。双击启动后会在本机起一个 HTTP 服务（默认 8080），
自动打开浏览器进入同一个网页 UI；手机扫码或用浏览器打开桌面端地址，就能和电脑互传文本/文件。
桌面端与安卓端共用同一套 HTTP API、同一块「共享传输板」、同一份网页代码，协议见 [API.md](API.md)。

## 产物

| 平台 | 文件 | 说明 |
| --- | --- | --- |
| Windows | `desktop/dist/LuoboKuaichuan.exe` | 便携单文件（无控制台窗口），双击即用；数据目录为 exe 同目录 `Luobo/` |
| Linux | `desktop/dist/LuoboKuaichuan-x86_64.AppImage` | 单文件 AppImage，`chmod +x` 后双击/命令行运行 |

## 数据目录

- 默认：程序所在目录下的 `Luobo/`（Windows：`...\Luobo`，Linux AppImage：`...\Luobo`）
- 里面的内容：
  - `board.json`：传输板记录（文本、文件条目、接收进度）
  - 收到的文件：以原始文件名落盘（重名自动加「 (1)」）
  - `luobo.log`：运行日志（512 KB 轮转）
  - `.luobo_id`：设备 ID（首次启动生成）
  - `.luobo.port`：本次实际监听的端口（被占用时会自动向后找端口，打开网页时用它）
  - `.luobo.lock`：单实例锁（同一数据目录重复双击时，只打开已运行实例的网页，不会起第二个服务）
- 想换位置：`luobo --data D:\某目录`（AppImage：`./LuoboKuaichuan-x86_64.AppImage --data ~/LuoboData`）

## 使用

1. 双击 `LuoboKuaichuan.exe` / 运行 AppImage；
2. 默认浏览器自动打开 `http://127.0.0.1:8080/`（同一台机器），页面顶部显示局域网地址与二维码；
3. 手机扫码或浏览器打开该地址 → 收发文本/任意文件；
4. 网页端顶栏右上角的 **⏻ 退出服务** 可以停止桌面端服务（该按钮只在桌面端出现，安卓端没有）。

命令行参数：

```
luobo [--port 8080] [--data DIR] [--host 0.0.0.0] [--no-browser]
  -p, --port N      监听端口（默认 8080；被占用时自动在 8080~8099 内找空闲端口）
  -d, --data DIR    数据目录（默认：程序同目录/Luobo）
  -H, --host ADDR   监听地址（默认 0.0.0.0）
      --no-browser  不自动打开浏览器
  -h, --help        帮助
```

## 构建

桌面端是 C++17，第三方依赖都以单头/单源文件形式放在 `desktop/third_party/`（cpp-httplib、nlohmann/json、
nayuki qrcodegen），**没有包管理器依赖**。

网页资源不是单独文件，而是在编译时由脚本内嵌进二进制：

```bash
cd desktop
python3 tools/gen_assets.py     # 把 app/src/main/assets/web 生成 generated/web_assets.cpp
```

### Linux

```bash
cd desktop
bash tools/build_linux.sh       # 有 zig 则静态 musl 二进制；否则用系统 g++
bash tools/build_appimage.sh    # 打 AppImage（appimagetool 或 runtime+squashfs 手动组装）
```

没有 zig 时可以用 pip 快速拿到（国内镜像下载很快，比官网快得多）：

```bash
python3 -m pip download ziglang==0.14.1 -d /tmp/zigpkg
cd /tmp/zigpkg && unzip -q ziglang-*.whl -d ext
export ZIG=/tmp/zigpkg/ext/ziglang/zig
$ZIG version    # 0.14.1
```

### Windows（在 Linux 上交叉编译）

使用 [zig](https://ziglang.org/) 自带的 clang 交叉编译，可产出静态 exe（不需要 Windows 机器）：

```bash
# 下载并解压 zig 0.14.x 到任意目录，例如 /opt/zig
export ZIG=/opt/zig/zig
cd desktop
bash tools/build_windows.sh     # 输出 dist/LuoboKuaichuan.exe
```

Windows 首次运行可能被 SmartScreen 提示「未知发布者」（未签名的小工具常见），选择「更多信息 → 仍要运行」即可。

### AppImage 打包工具

```bash
# 只需要一次
curl -L -o /home/xiuufa/tools/appimagetool-x86_64.AppImage \
  https://gh-proxy.com/https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
chmod +x /home/xiuufa/tools/appimagetool-x86_64.AppImage
# 打包（脚本会自动用 --appimage-extract-and-run，无需 FUSE）
cd desktop && bash tools/build_appimage.sh
```

如果运行 AppImage 时提示 FUSE 相关错误，可改用：

```bash
APPIMAGE_EXTRACT_AND_RUN=1 ./LuoboKuaichuan-x86_64.AppImage
```

或安装 `libfuse2`。没有 appimagetool 时脚本会自动下载 type2 runtime 手动拼装 AppImage
（`mksquashfs` + `cat runtime squashfs`），同样可用。

## 实现要点

- `src/main.cpp`：HTTP 服务（httplib::Server）+ 与安卓端字段完全一致的 API + 静态网页内嵌 + 局域网地址枚举 +
  自动开浏览器 + `--data/--port` + `/api/shutdown`（仅 127.0.0.1，网页上的「退出服务」用它）。
- `src/board.cpp`：共享传输板（与安卓 `BoardRepository` 行为对齐：board.json 原子写、文件完成后改回原始名、
  重名加序号、接收中条目用 `<id>_<文件名>` 临时落盘）。
- `src/qr.cpp`：二维码 SVG 生成（qrcodegen），页面里直接 `<img src="/api/qr.svg?data=...">`。
- 文件下载使用 httplib 的 Range 自动切片能力，`?download=1` 强制附件下载。
- 单实例与端口：`.luobo.lock` 用非阻塞排他锁防止同目录多开；端口被占用时自动向后尝试 20 个端口，
  并把实际端口写进 `.luobo.port`，重复双击会直接打开已运行实例的网页。
- httplib 默认开启 `SO_REUSEPORT`（会让两个进程同时监听同一端口），这里显式改为只用 `SO_REUSEADDR`。
- 全部文件路径使用 `std::filesystem::path`（Windows 端通过 `GetModuleFileNameW` + UTF-8 转换），
  安装/数据目录包含中文、空格也能正常工作。

## 安全边界

与安卓端一致：只面向**同一局域网 / 热点**，没有账号体系与鉴权；任何能访问该端口的人都能收发文件。
请勿把端口直接暴露到公网；退出服务用网页右上角 ⏻ 或直接关掉进程。

## 已验证

- Linux 原生构建、全部 API（文本、1 MB 分片上传、Range 206/416、下载哈希、删除、再次发送、清空、二维码、静态页）；
- 浏览器实测（1280×900）：发文本、上传 200 MB 文件、暂停/继续、退出服务；
- 中文数据目录实测通过（`--data /tmp/中文路径测试`：落盘、board.json、上传均正常）；
- 端口占用自动切换实测（两个不同数据目录同时启动 → 8080/8081 分开监听）；
- 单实例实测（同一数据目录再次启动 → 直接退出并打开已运行实例网页）。
- Windows exe 为交叉编译产物，需在 Windows 上实跑确认（首次启动的防火墙提示选择「允许访问」）。
