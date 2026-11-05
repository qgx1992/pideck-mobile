#!/usr/bin/env python3
"""局域网 APK 下载页（等价于 `python -m http.server 8080 --directory apk`）。

为什么要这个脚本，而不是直接 `python -m http.server`：

1. `python -m http.server` 默认用单线程 `TCPServer`，一个慢速下载（手机拉 14MB 包）
   会把整站堵住；这里用 `ThreadingHTTPServer`。
2. 开机自启要用 `pythonw.exe`（无控制台窗口），而 pythonw 下 `sys.stdout/sys.stderr`
   是 `None`，`BaseHTTPRequestHandler.log_message` 一写日志就抛 AttributeError，
   请求直接在发响应头之前炸掉。所以这里先接管 stdout/stderr 指向日志文件。
3. 日志不能落在 `apk/` 里 —— 那是无鉴权对外明文开放的目录。

用法：
    pythonw.exe app/scripts/serve-apk.py            # 开机自启 / 后台常驻
    python  app/scripts/serve-apk.py                # 前台跑（Ctrl-C 退出）
    python  app/scripts/serve-apk.py --port 8081    # 换端口

日志：`%LOCALAPPDATA%/PiDeck-DownloadServer/server.log`（Windows）；
其它平台退到 `~/.cache/PiDeck-DownloadServer/server.log`。
"""

from __future__ import annotations

import argparse
import http.server
import os
import re
import sys
from datetime import datetime
from functools import partial
from pathlib import Path
from typing import IO

DEFAULT_PORT = 8080
ROOT = Path(__file__).resolve().parents[2] / "apk"


def log_path() -> Path:
    base = os.environ.get("LOCALAPPDATA")
    folder = Path(base) / "PiDeck-DownloadServer" if base else Path.home() / ".cache" / "PiDeck-DownloadServer"
    folder.mkdir(parents=True, exist_ok=True)
    return folder / "server.log"


def open_log() -> IO[str]:
    """接管 stdout/stderr：pythonw 下它们是 None，不接管会在首个请求上炸掉。"""
    handle = open(log_path(), "a", buffering=1, encoding="utf-8")
    sys.stdout = handle
    sys.stderr = handle
    return handle


class Handler(http.server.SimpleHTTPRequestHandler):
    """默认 handler + 明确的目录（不依赖进程工作目录，开机自启时 cwd 是 system32）。"""

    # HTTP/1.0 下浏览器不走 keep-alive，且 http.server 不支持 Range（返回 200 + 全量），
    # iOS Safari / 微信内置浏览器对 <video> 会直接拒播。这里升到 HTTP/1.1 并自己实现 Range。
    protocol_version = "HTTP/1.1"

    def send_head(self):
        path = self.translate_path(self.path)
        if os.path.isdir(path):
            return super().send_head()
        try:
            f = open(path, "rb")
        except OSError:
            self.send_error(404, "File not found")
            return None
        try:
            st = os.fstat(f.fileno())
            size = st.st_size
            rng = None
            m = re.match(r"bytes=(\d*)-(\d*)$", (self.headers.get("Range") or "").strip())
            if m and size > 0 and "," not in self.headers.get("Range", ""):
                if m.group(1):
                    start = int(m.group(1))
                    end = int(m.group(2)) if m.group(2) else size - 1
                elif m.group(2):  # 尾段 bytes=-N
                    start = max(0, size - int(m.group(2)))
                    end = size - 1
                else:
                    start = end = 0
                end = min(end, size - 1)
                if start > end or start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    f.close()
                    return None
                rng = (start, end)
            self._range = rng
            self.send_response(206 if rng else 200)
            self.send_header("Content-Type", self.guess_type(path))
            self.send_header("Accept-Ranges", "bytes")
            if rng:
                self.send_header("Content-Range", f"bytes {rng[0]}-{rng[1]}/{size}")
                self.send_header("Content-Length", str(rng[1] - rng[0] + 1))
            else:
                self.send_header("Content-Length", str(size))
            self.send_header("Last-Modified", self.date_time_string(st.st_mtime))
            self.end_headers()
            if rng:
                f.seek(rng[0])
            return f
        except Exception:
            f.close()
            raise

    def copyfile(self, source, outputfile) -> None:
        rng = getattr(self, "_range", None)
        if not rng:
            return super().copyfile(source, outputfile)
        remaining = rng[1] - rng[0] + 1
        while remaining > 0:
            chunk = source.read(min(64 * 1024, remaining))
            if not chunk:
                break
            outputfile.write(chunk)
            remaining -= len(chunk)

    def end_headers(self) -> None:
        # 页面 / 校验文件经常原地改：不加这句，手机浏览器会按启发式缓存一直显示旧页面
        if self.path.split("?")[0].endswith((".html", ".txt", "/")):
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def log_message(self, fmt: str, *args: object) -> None:  # noqa: A003 - 覆盖基类签名
        stamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        line = fmt % args
        # 只记非 200 的请求，正常下载刷屏没意义
        status = args[1] if len(args) > 1 else ""
        if str(status).startswith(("200", "206", "304")):
            return
        sys.stderr.write(f"[{stamp}] {self.address_string()} {line}\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Serve apk/ on the LAN (download page).")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PIDECK_APK_PORT", DEFAULT_PORT)))
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--root", default=str(ROOT))
    parser.add_argument("--foreground", action="store_true", help="日志仍写终端（调试用）")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    if not root.is_dir():
        print(f"apk 目录不存在：{root}", file=sys.stderr)
        return 1

    handle = None if args.foreground else open_log()

    def emit(text: str) -> None:
        stream = sys.stderr or sys.stdout
        if stream is not None:
            stream.write(text + "\n")

    handler = partial(Handler, directory=str(root))
    try:
        httpd = http.server.ThreadingHTTPServer((args.bind, args.port), handler)
    except OSError as exc:  # 端口被占：写日志后安静退出，别让开机自启弹窗
        emit(f"{datetime.now():%Y-%m-%d %H:%M:%S} 绑定 {args.bind}:{args.port} 失败：{exc}")
        if handle:
            handle.close()
        return 1

    httpd.daemon_threads = True
    emit(f"{datetime.now():%Y-%m-%d %H:%M:%S} serving {root} on http://{args.bind}:{args.port}/ (pid {os.getpid()})")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        emit("ctrl-c, bye")
    finally:
        httpd.server_close()
        if handle:
            handle.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
