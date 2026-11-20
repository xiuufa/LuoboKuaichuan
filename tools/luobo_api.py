#!/usr/bin/env python3
"""萝卜快传 HTTP API 客户端（纯标准库，无第三方依赖）。

用途：
- 给 sun 插件 / PC 端 Python 脚本直接调用（与 Android APK 主机通信）
- 也作为后续 PC EXE、Linux Image 的协议参考实现
- 命令行调试：python3 tools/luobo_api.py info|list|send-text|send-file|download|delete|clear|qr

协议文档见 docs/API.md。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_CHUNK = 1024 * 1024
DEFAULT_TIMEOUT = 30


class LuoboError(Exception):
    """接口返回非 2xx 或网络错误。"""

    def __init__(self, message: str, status: int = 0, payload=None):
        super().__init__(message)
        self.status = status
        self.payload = payload


class OffsetMismatch(LuoboError):
    """上传分片 offset 与服务端不一致，按 self.offset 续传即可。"""

    def __init__(self, offset: int, size: int):
        super().__init__(f"offset mismatch: server offset={offset}, size={size}", 409)
        self.offset = offset
        self.size = size


def normalize_base_url(raw: str) -> str:
    """补全 scheme、去掉末尾斜杠。"""
    value = (raw or "").strip()
    if not value:
        raise LuoboError("地址为空")
    if "://" not in value:
        value = "http://" + value
    return value.rstrip("/")


class LuoboClient:
    """一个主机（萝卜快传服务端）的 HTTP 客户端。"""

    def __init__(self, base_url: str, timeout: float = DEFAULT_TIMEOUT,
                 sender: str | None = None, sender_id: str | None = None):
        self.base_url = normalize_base_url(base_url)
        self.timeout = timeout
        self.sender = sender or "python"
        self.sender_id = sender_id or "py"

    # ------------------------------------------------------------------ 基础

    def _request(self, method: str, path: str, *, data: bytes | None = None,
                 headers: dict | None = None) -> tuple[int, dict, bytes]:
        url = self.base_url + path
        req = urllib.request.Request(url, data=data, method=method)
        for key, value in (headers or {}).items():
            req.add_header(key, value)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.status, dict(resp.headers), resp.read()
        except urllib.error.HTTPError as error:
            body = error.read()
            status = error.code
            payload = None
            try:
                payload = json.loads(body.decode("utf-8"))
            except Exception:
                pass
            if status == 409 and isinstance(payload, dict) and "offset" in payload:
                raise OffsetMismatch(int(payload["offset"]), int(payload.get("size", 0)))
            message = payload.get("error") if isinstance(payload, dict) else body[:200].decode("utf-8", "replace")
            raise LuoboError(message or f"HTTP {status}", status, payload) from None
        except urllib.error.URLError as error:
            raise LuoboError(f"无法连接 {url}: {error.reason}") from None

    def _json(self, method: str, path: str, body: dict | None = None,
              headers: dict | None = None) -> dict:
        data = None
        merged_headers = dict(headers or {})
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            merged_headers.setdefault("Content-Type", "application/json; charset=utf-8")
        _, _, raw = self._request(method, path, data=data, headers=merged_headers)
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    # ------------------------------------------------------------------ 探测

    def info(self) -> dict:
        """GET /api/info：主机能力信息。"""
        return self._json("GET", "/api/info")

    @classmethod
    def probe(cls, base_url: str, timeout: float = 3.0) -> dict | None:
        """探测地址是否为萝卜快传主机；是则返回 info，否则 None。"""
        try:
            client = cls(base_url, timeout=timeout)
            info = client.info()
            return info if info.get("app") == "LuoboKuaichuan" else None
        except Exception:
            return None

    # ------------------------------------------------------------- 传输板

    def list_items(self, since: int | None = None) -> dict:
        """GET /api/list：返回 {version, itemCount, items, unchanged, ...}。"""
        path = "/api/list"
        if since is not None:
            path += f"?since={int(since)}"
        return self._json("GET", path)

    def send_text(self, text: str, sender: str | None = None,
                  sender_id: str | None = None) -> dict:
        """POST /api/text：发送一条文本，返回 {ok, item, version}。"""
        return self._json("POST", "/api/text", {
            "text": text,
            "sender": sender or self.sender,
            "senderId": sender_id or self.sender_id,
        })

    # ------------------------------------------------------------- 上传

    def init_upload(self, name: str, size: int, mime: str | None = None,
                    sender: str | None = None, sender_id: str | None = None) -> dict:
        """POST /api/upload/init。"""
        return self._json("POST", "/api/upload/init", {
            "name": name,
            "size": int(size),
            "mime": mime or mimetypes.guess_type(name)[0] or "application/octet-stream",
            "sender": sender or self.sender,
            "senderId": sender_id or self.sender_id,
        })

    def put_chunk(self, upload_id: str, offset: int, data: bytes) -> dict:
        """PUT /api/upload/{id}?offset=N 上传一片原始字节。"""
        path = f"/api/upload/{urllib.parse.quote(upload_id)}?offset={int(offset)}"
        _, _, raw = self._request("PUT", path, data=data,
                                  headers={"Content-Type": "application/octet-stream"})
        return json.loads(raw.decode("utf-8")) if raw else {}

    def complete_upload(self, upload_id: str) -> dict:
        """POST /api/upload/{id}/complete。"""
        return self._json("POST", f"/api/upload/{urllib.parse.quote(upload_id)}/complete")

    def cancel_upload(self, upload_id: str) -> dict:
        """DELETE /api/upload/{id}：取消未完成的上传（同时删除主机上的半成品）。"""
        return self._json("DELETE", f"/api/upload/{urllib.parse.quote(upload_id)}")

    def upload_file(self, path: str, *, name: str | None = None, mime: str | None = None,
                    sender: str | None = None, chunk_size: int | None = None,
                    progress=None, should_pause=None,
                    resume: tuple[str, int] | None = None) -> dict:
        """上传本地文件（自动分片、自动处理 409 续传）。

        progress(sent, total)     上传进度回调
        should_pause()            返回 True 时暂停（抛出 Paused 由调用方保留 resume 参数）
        resume=(upload_id, offset) 断点续传：从已有会话的指定偏移继续
        """
        file_name = name or os.path.basename(path)
        total = os.path.getsize(path)
        if progress:
            progress(0, total)

        if resume:
            upload_id, offset = resume
        else:
            result = self.init_upload(file_name, total, mime=mime, sender=sender)
            upload_id = result["uploadId"]
            offset = 0
            if result.get("completed"):
                return result

        chunk = chunk_size or DEFAULT_CHUNK
        with open(path, "rb") as handle:
            while True:
                if should_pause and should_pause():
                    raise LuoboError(f"已暂停：resume=({upload_id!r}, {offset})", 0)
                handle.seek(offset)
                payload = handle.read(chunk)
                if not payload:
                    break
                try:
                    response = self.put_chunk(upload_id, offset, payload)
                except OffsetMismatch as mismatch:
                    offset = mismatch.offset
                    continue
                offset = int(response.get("received", offset + len(payload)))
                if progress:
                    progress(offset, total)
                if response.get("completed"):
                    break

        return self.complete_upload(upload_id)

    # ------------------------------------------------------------- 下载

    def file_url(self, item_id: str, download: bool = False) -> str:
        url = self.base_url + f"/api/file/{urllib.parse.quote(item_id)}"
        return url + ("?download=1" if download else "")

    def download(self, item_id: str, dest: str, byte_range: tuple[int, int] | None = None) -> str:
        """下载条目到 dest，返回文件 sha256（传了 byte_range 时是分片的哈希）。"""
        headers = {}
        mode = "wb"
        if byte_range:
            headers["Range"] = f"bytes={byte_range[0]}-{byte_range[1]}"
        request = urllib.request.Request(self.file_url(item_id), headers=headers)
        digest = hashlib.sha256()
        with urllib.request.urlopen(request, timeout=self.timeout) as resp, open(dest, mode) as handle:
            while True:
                block = resp.read(256 * 1024)
                if not block:
                    break
                handle.write(block)
                digest.update(block)
        return digest.hexdigest()

    # ------------------------------------------------------------- 其它

    def delete_item(self, item_id: str) -> dict:
        return self._json("DELETE", f"/api/item/{urllib.parse.quote(item_id)}")

    def resend_item(self, item_id: str) -> dict:
        """把已有条目再次发送（复制成一条新记录）。"""
        return self._json("POST", f"/api/item/{urllib.parse.quote(item_id)}/resend")

    def delete_item_file(self, item_id: str) -> dict:
        """只删除主机上的原文件，保留记录（记录会变成 failed）。"""
        return self._json("DELETE", f"/api/file/{urllib.parse.quote(item_id)}")

    def clear(self) -> dict:
        return self._json("POST", "/api/clear")

    def shutdown(self) -> dict:
        """请求桌面端退出服务（仅 127.0.0.1 可用）。"""
        return self._json("POST", "/api/shutdown", {})

    def qr_svg(self, data: str) -> bytes:
        path = "/api/qr.svg?data=" + urllib.parse.quote(data, safe="")
        _, _, raw = self._request("GET", path)
        return raw


# ====================================================================== CLI

def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="萝卜快传 HTTP API 客户端 / 调试工具")
    parser.add_argument("base", help="主机地址，例如 http://10.119.106.214:8080")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("info", help="查看主机信息")
    list_parser = sub.add_parser("list", help="查看共享传输板")
    list_parser.add_argument("--since", type=int, default=None)

    text_parser = sub.add_parser("send-text", help="发送文本")
    text_parser.add_argument("text")
    text_parser.add_argument("--sender", default=None)

    file_parser = sub.add_parser("send-file", help="上传文件（分片）")
    file_parser.add_argument("path")
    file_parser.add_argument("--sender", default=None)
    file_parser.add_argument("--chunk", type=int, default=None)

    download_parser = sub.add_parser("download", help="下载文件")
    download_parser.add_argument("item_id")
    download_parser.add_argument("dest")
    download_parser.add_argument("--range", default=None, help="例如 100-199")

    delete_parser = sub.add_parser("delete", help="删除条目")
    delete_parser.add_argument("item_id")

    resend_parser = sub.add_parser("resend", help="再次发送（复制成新记录）")
    resend_parser.add_argument("item_id")

    delfile_parser = sub.add_parser("delete-file", help="只删除原文件、保留记录")
    delfile_parser.add_argument("item_id")

    sub.add_parser("clear", help="清空传输板")
    sub.add_parser("shutdown", help="退出桌面端服务（仅本机可用）")

    qr_parser = sub.add_parser("qr", help="输出二维码 SVG")
    qr_parser.add_argument("--data", default=None)
    qr_parser.add_argument("--out", default=None)
    return parser


def main(argv=None) -> int:
    args = _build_parser().parse_args(argv)
    client = LuoboClient(args.base)

    if args.command == "info":
        print(json.dumps(client.info(), ensure_ascii=False, indent=2))
    elif args.command == "list":
        data = client.list_items(args.since)
        print(f"版本 {data.get('version')}，共 {data.get('itemCount')} 项")
        for item in data.get("items", []):
            print(f"  [{item.get('kind')}] {item.get('name')}  "
                  f"{item.get('size')}B  {item.get('state')}  id={item.get('id')}")
    elif args.command == "send-text":
        result = client.send_text(args.text, sender=args.sender)
        print(json.dumps(result.get("item", {}), ensure_ascii=False, indent=2))
    elif args.command == "send-file":
        started = time.time()

        def on_progress(sent, total):
            percent = 100 if total == 0 else sent * 100 // total
            sys.stderr.write(f"\r上传中 {percent}% ({sent}/{total}) ")
            sys.stderr.flush()

        result = client.upload_file(args.path, sender=args.sender, chunk_size=args.chunk,
                                    progress=on_progress)
        sys.stderr.write(f"\r完成，用时 {time.time() - started:.1f}s\n")
        print(json.dumps(result.get("item", {}), ensure_ascii=False, indent=2))
    elif args.command == "download":
        byte_range = None
        if args.range:
            start, end = args.range.split("-", 1)
            byte_range = (int(start), int(end))
        digest = client.download(args.item_id, args.dest, byte_range)
        print(f"已保存到 {args.dest}，sha256={digest}")
    elif args.command == "resend":
        print(json.dumps(client.resend_item(args.item_id), ensure_ascii=False, indent=2))
    elif args.command == "delete-file":
        print(json.dumps(client.delete_item_file(args.item_id), ensure_ascii=False, indent=2))
    elif args.command == "delete":
        print(json.dumps(client.delete_item(args.item_id), ensure_ascii=False))
    elif args.command == "clear":
        print(json.dumps(client.clear(), ensure_ascii=False))
    elif args.command == "shutdown":
        print(json.dumps(client.shutdown(), ensure_ascii=False))
    elif args.command == "qr":
        data = args.data or client.base_url
        svg = client.qr_svg(data)
        if args.out:
            with open(args.out, "wb") as handle:
                handle.write(svg)
            print(f"已保存 {args.out}")
        else:
            sys.stdout.buffer.write(svg)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
