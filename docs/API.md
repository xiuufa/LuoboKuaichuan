# 萝卜快传 HTTP API（v1）

萝卜快传的主机端（Android APK、Windows 便携 exe、Linux AppImage）在局域网内提供一个纯 HTTP
的「共享传输板」服务，三端协议完全一致。任何能发 HTTP 请求的客户端——浏览器、另一台手机、PC、Linux、Python / sun
插件——都可以通过下面的接口收发文本与任意格式文件，无需额外 SDK。

- 默认端口：`8080`，基地址：`http://<主机局域网 IP>:8080`
- 所有 `/api/*` 响应均为 UTF-8 JSON；错误统一为 `{"ok": false, "error": "..."}`
- 全部接口支持 CORS（`Access-Control-Allow-Origin: *`），允许方法 `GET, POST, PUT, DELETE, HEAD, OPTIONS`
- 时间戳均为 Unix 毫秒（`Long`）；文件大小单位均为字节（byte）
- 传输板是「共享」的：同一主机上的所有连接设备看到同一份条目列表

## 目录

- [1. 服务发现 / 能力信息](#1-服务发现--能力信息)
- [2. 共享传输板](#2-共享传输板)
- [3. 发送文本](#3-发送文本)
- [4. 上传文件（分片、可断点续传）](#4-上传文件分片可断点续传)
- [5. 下载文件（HTTP Range）](#5-下载文件http-range)
- [6. 删除 / 清空](#6-删除--清空)
- [6.3 退出服务（仅桌面端）](#63-退出服务仅桌面端)
- [7. 二维码](#7-二维码)
- [8. 网页端](#8-网页端)
- [9. 错误码与常见问题](#9-错误码与常见问题)

---

## 1. 服务发现 / 能力信息

```
GET /api/info
```

返回主机能力与传输板版本，用于探测、显示二维码、判断上传上限等。

```json
{
  "ok": true,
  "app": "LuoboKuaichuan",
  "apiVersion": 1,
  "appName": "萝卜快传",
  "versionName": "1.0.0",
  "deviceName": "V2352GA",
  "deviceId": "3645b8db3f7f4488",
  "port": 8080,
  "url": "http://10.119.106.214:8080",
  "urls": ["http://10.119.106.214:8080"],
  "boardVersion": 12,
  "itemCount": 5,
  "maxTextBytes": 1048576,
  "maxUploadBytes": 17179869184,
  "chunkSize": 524288,
  "bigFileThreshold": 20971520,
  "storagePath": "/storage/emulated/0/Luobo",
  "features": ["text", "file", "chunked-upload", "range-download", "qr", "shared-board"],
  "uptimeSeconds": 830
}
```

| 字段 | 说明 |
| --- | --- |
| `boardVersion` | 传输板版本号，条目结构变化时递增（接收进度变化不递增） |
| `itemCount` | 当前条目数 |
| `maxTextBytes` | 单条文本上限（1 MB） |
| `maxUploadBytes` | 单文件上限（默认 16 GB） |
| `chunkSize` | 推荐分片大小（1 MB） |
| `bigFileThreshold` | 「大文件」阈值（20 MB），网页端据此决定删除时是否二次确认 |
| `storagePath` | 主机端文件实际保存目录 |

探测一个地址是否为萝卜快传主机：请求 `GET /api/info`，`ok == true` 且 `app == "LuoboKuaichuan"` 即可。

---

## 2. 共享传输板

```
GET /api/list?since=<version>
```

返回当前传输板条目（最新在前）。`since` 可选：传上次拿到的 `version`，响应里的 `unchanged`
会告诉你有没有变化；**无论是否变化都会返回 `items`**，因为文件接收进度（`received`）不会改变
`version`，客户端需要靠轮询 `items` 展示接收进度。推荐轮询间隔 1.5s。

```json
{
  "ok": true,
  "version": 12,
  "itemCount": 3,
  "serverTime": 1793338318402,
  "unchanged": false,
  "items": [
    {
      "id": "f2726c05552b",
      "kind": "file",
      "name": "report.pdf",
      "text": "",
      "size": 1834202,
      "mime": "application/pdf",
      "sender": "网页访客",
      "senderId": "web-8f3a",
      "createdAt": 1793338318402,
      "state": "ready",
      "received": 1834202,
      "fileName": "report.pdf"
    },
    {
      "id": "e5d8acb74317",
      "kind": "text",
      "name": "网页端发送的第一条文本 🥕 hello",
      "text": "网页端发送的第一条文本 🥕 hello",
      "size": 44,
      "mime": "text/plain; charset=utf-8",
      "sender": "网页访客",
      "senderId": "web-8f3a",
      "createdAt": 1793338318400,
      "state": "ready",
      "received": 44
    }
  ]
}
```

`kind`：

- `text`：文本条目，内容在 `text`，`name` 为文本前 80 字符
- `file`：文件条目，`name` 为文件名，`fileName` 为磁盘上的实际文件名（重名时可能是 `name (1).ext`），`received` 为已接收字节数

`state`：

| 值 | 含义 |
| --- | --- |
| `receiving` | 文件正在接收（可能暂停/中断中），`received < size` |
| `ready` | 已完成，可下载 / 打开 / 复制 |
| `failed` | 异常（例如主机上文件丢失） |

---

## 3. 发送文本

```
POST /api/text
Content-Type: application/json

{
  "text": "要发送的文本",
  "sender": "pc-ubuntu",     // 可选，显示用，默认「未知设备」
  "senderId": "pc-1a2b"      // 可选，设备标识，默认空
}
```

响应：

```json
{ "ok": true, "item": { ...BoardItem... }, "version": 13 }
```

约束：`text` 非空且 UTF-8 不超过 `maxTextBytes`（1 MB），超限返回 `413`，JSON 非法返回 `400`。

---

## 4. 上传文件（分片、可断点续传）

文件上传是三步协议：**init → PUT 分片 × N → complete**。中途暂停、断网、切换网络后，客户端可
凭 `uploadId` 与服务端返回的 `offset` 继续上传，不会从头开始。

### 4.1 初始化

```
POST /api/upload/init
Content-Type: application/json

{ "name": "movie.mkv", "size": 735000000, "mime": "video/x-matroska",
  "sender": "pc-ubuntu", "senderId": "pc-1a2b" }
```

响应：

```json
{
  "ok": true,
  "uploadId": "a97407042f7d",
  "itemId": "a97407042f7d",
  "offset": 0,
  "size": 735000000,
  "chunkSize": 524288,
  "completed": false,
  "item": { ...BoardItem... },
  "version": 14
}
```

- `uploadId == itemId`（实现上同值）
- `size` 为 0 时直接创建完成条目，`completed == true`，无需再 PUT
- 主机在传输板上立刻出现 `state=receiving` 的条目，其它设备可实时看到进度

### 4.2 上传分片

```
PUT /api/upload/{uploadId}?offset=<已写入字节数>
Content-Length: <本片字节数>
Content-Type: application/octet-stream

<原始二进制>
```

响应：

```json
{ "ok": true, "received": 524288, "size": 735000000, "completed": false, "version": 14 }
```

- `offset` 必须等于服务端当前已接收字节数，否则返回 `409`：

  ```json
  { "ok": false, "error": "offset mismatch", "offset": 524288, "size": 735000000 }
  ```

  客户端应按 `offset` 重新定位（`seek`）后重传该片。
- 缺少 `Content-Length` 返回 `411`；本片超过剩余大小返回 `413`
- 收满 `size` 字节后服务端自动完成上传（也可显式调用 complete 保证幂等）
- 建议分片大小取 `chunkSize`（1 MB）

### 4.3 完成

```
POST /api/upload/{uploadId}/complete
```

```json
{ "ok": true, "completed": true, "item": { ...BoardItem state=ready... }, "version": 15 }
```

- 已完成的会话重复调用会幂等返回 `ok`
- 会话已失效或未收满返回 `409`

### 4.4 取消

```
DELETE /api/upload/{uploadId}
```

```json
{ "ok": true, "version": 15 }
```

删除未完成的条目与主机上的半成品文件。

### 上传示例（curl）

```bash
# 1) init
INIT=$(curl -s -X POST http://127.0.0.1:8080/api/upload/init \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo.bin","size":1048576,"mime":"application/octet-stream","sender":"pc"}')
UPLOAD_ID=$(echo "$INIT" | python3 -c 'import json,sys;print(json.load(sys.stdin)["uploadId"])')

# 2) 逐片 PUT（示例用 1MB；实际请循环到文件末尾）
split -b 524288 -d demo.bin /tmp/demo.part.
OFFSET=0
for f in /tmp/demo.part.*; do
  curl -s -X PUT "http://127.0.0.1:8080/api/upload/$UPLOAD_ID?offset=$OFFSET" \
    -H 'Content-Type: application/octet-stream' --data-binary @"$f"
  OFFSET=$((OFFSET + $(stat -c%s "$f")))
done

# 3) complete
curl -s -X POST "http://127.0.0.1:8080/api/upload/$UPLOAD_ID/complete"
```

---

## 5. 下载文件（HTTP Range）

```
GET /api/file/{itemId}
GET /api/file/{itemId}?download=1     # 强制以附件下载（Content-Disposition: attachment）
```

- 支持标准 HTTP Range：`Range: bytes=0-1023` → `206 Partial Content` + `Content-Range: bytes 0-1023/size`
- 越界 / 非法区间 → `416` + `Content-Range: bytes */size`
- 不带 Range → `200` 全量；响应含 `Accept-Ranges: bytes`
- 仅 `state=ready` 的条目可下载；`receiving/failed` 返回 `409` / `404`

示例：

```bash
curl -s -o part.bin -H 'Range: bytes=1048576-2097151' http://127.0.0.1:8080/api/file/f2726c05552b
```

> 主机端接收完成的文件会以**原始文件名**保存在 `storagePath`（默认
> `/storage/emulated/0/Luobo`）；重名时自动追加 ` (1)`、` (2)`。

---

## 6. 删除 / 清空

```
DELETE /api/item/{itemId}      # 删除单条（文件条目会同时删除主机上的文件）
POST   /api/clear              # 清空整个传输板
```

响应：

```json
{ "ok": true, "version": 16 }
```

```json
{ "ok": true, "removed": 5, "version": 17 }
```

### 6.1 再次发送（刷新）

把已有条目复制成一条新记录：文本直接复制；文件复制出一份新文件（自动处理重名）。
用于信息条左滑的「再次发送」。

```
POST /api/item/{itemId}/resend
```

响应：

```json
{ "ok": true, "item": { "...": "新的 BoardItem" }, "version": 18 }
```

### 6.2 只删除原文件、保留记录

用于「收到的文件」左滑动作：删掉主机磁盘上的文件，记录保留并标记为失败（`state = "failed"`），
之后仍可删除记录。

```
DELETE /api/file/{itemId}
```

响应：

```json
{ "ok": true, "version": 19 }
```

### 6.3 退出服务（仅桌面端）

桌面端（Windows exe / Linux AppImage）支持从网页右上角「⏻ 退出服务」停止本机服务；
安卓端没有这个能力（前台服务请用通知栏的「停止」）。

```
POST /api/shutdown
```

- 仅接受来自 `127.0.0.1` 的请求（防止局域网其它设备误关服务），否则返回 `403`。
- 是否支持该能力可通过 `/api/info` 的 `features` 判断：包含 `"shutdown"` 即支持。

响应：

```json
{ "ok": true }
```

服务会先回包，再异步停止监听。

---

## 7. 二维码

```
GET /api/qr.svg?data=<url-encoded 内容>
```

返回 `image/svg+xml`，内容为二维码（默认 480×480，含 1 格静区）。网页端用它显示
「扫码加入传输板」的二维码；不传 `data` 时返回 `400`。

```html
<img src="/api/qr.svg?data=http%3A%2F%2F10.119.106.214%3A8080" alt="扫码加入">
```

---

## 8. 网页端

```
GET /                # 网页版界面（移动端 / 桌面端自适应，无任何外网依赖）
GET /style.css
GET /app.js
```

网页端与原生 APP 使用同一套 API：发送文本/文件、分片上传队列（暂停/继续/取消/重试）、
下载、删除（大文件二次确认）、二维码、轮询共享传输板。

---

## 9. 错误码与常见问题

| HTTP 状态 | 场景 |
| --- | --- |
| `400` | JSON 非法、缺少必填字段、`data` 为空 |
| `403` | 主机没有「所有文件访问」权限，无法写入保存目录 |
| `404` | 条目 / 上传会话 / 文件不存在 |
| `405` | 路径存在但方法不支持 |
| `409` | 上传 `offset` 不匹配、会话未收满、条目尚在接收中 |
| `411` | PUT 分片缺少 `Content-Length` |
| `413` | 文本或文件超过上限 |
| `416` | Range 越界 |

常见问题：

1. **手机 / 电脑连不上**：确认双方在同一 WiFi 或热点下；Android 端要打开「共享」开关；
   部分公共 WiFi 开启了 AP 隔离，设备之间无法互访。
2. **上传 403**：在 Android 设置里给「萝卜快传」授予「所有文件访问」权限（或用应用内引导）。
3. **扫码打不开**：二维码里是 `http://局域网IP:8080`，确保扫码设备与主机同网段。
4. **断点续传**：记住 `uploadId` 与 `offset`；服务端返回 `409` 时按其 `offset` 续传即可。
5. **电脑端（sun 插件 / py 脚本）**：直接 import `tools/luobo_api.py` 里的 `LuoboClient`，
   或按本文档自己发 HTTP 请求；PC EXE 与 Linux Image 只需复用同一协议。

---

## 附：Python 最小示例

仓库自带 `tools/luobo_api.py`（纯标准库 urllib，无第三方依赖）：

```python
from luobo_api import LuoboClient

client = LuoboClient("http://10.119.106.214:8080")
print(client.info()["appName"])
client.send_text("来自 Python 的文本", sender="py-script")

# 分片上传（自动处理 409 续传）
client.upload_file("/tmp/demo.bin", sender="py-script")

# 下载 & 删除
client.download("f2726c05552b", "/tmp/out.bin")
client.delete_item("f2726c05552b")
```
