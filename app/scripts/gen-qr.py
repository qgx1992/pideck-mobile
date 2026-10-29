#!/usr/bin/env python3
"""重新生成局域网下载页的二维码。

下载页 apk/index.html 里的二维码是内联 SVG（不依赖外部图），内容就是页面地址，
手机扫一下直接打开下载页——所以改端口/换机器后必须重新生成，否则扫出来还是旧地址。

    python app/scripts/gen-qr.py --url http://10.10.0.172:8080/
    python app/scripts/gen-qr.py                 # 自动取本机局域网 IP + 8080

同时写出 apk/qr.svg（单独引用这张图的场景）并替换 index.html 里的 <div class="qr"> 块。
"""

import argparse
import ipaddress
import re
import socket
import sys
from pathlib import Path

import qrcode
import qrcode.image.svg

# Windows 控制台默认 GBK，输出 ✓ 之类会直接抛 UnicodeEncodeError
sys.stdout.reconfigure(encoding="utf-8")

APK_DIR = Path(__file__).resolve().parents[2] / "apk"
INDEX = APK_DIR / "index.html"
QR_FILE = APK_DIR / "qr.svg"


def local_ip() -> str:
    """取默认出口网卡的 IP：不真的发包（UDP connect 只查路由表）。"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    finally:
        sock.close()


def build_svg(url: str) -> str:
    image = qrcode.make(url, image_factory=qrcode.image.svg.SvgPathImage)
    # qrcode 生成的 svg 自带 xml 头与 mm 尺寸，这里取出 <svg> 本身
    raw = image.to_string().decode("utf-8")
    return raw[raw.index("<svg"):]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=None, help="二维码内容，默认 http://<本机IP>:8080/")
    parser.add_argument("--port", default="8080")
    args = parser.parse_args()

    url = args.url
    if not url:
        url = f"http://{local_ip()}:{args.port}/"
    try:
        ipaddress.ip_address(url.split("//")[-1].split(":")[0].split("/")[0])
    except ValueError:
        print(f"! 二维码地址里的主机名不是 IP（{url}），手机扫码要求同一局域网内的 IP")

    svg = build_svg(url)

    QR_FILE.write_text(f"<?xml version='1.0' encoding='UTF-8'?>\n{svg}\n", encoding="utf-8")

    html = INDEX.read_text(encoding="utf-8")
    pattern = re.compile(r'<div class="qr">.*?</div>', re.S)
    if not pattern.search(html):
        raise SystemExit('✗ index.html 里找不到 <div class="qr"> 块，无法内联二维码')
    INDEX.write_text(pattern.sub(f'<div class="qr">{svg}</div>', html, count=1), encoding="utf-8")

    print(f"✓ 二维码内容：{url}")
    print(f"  {QR_FILE}")
    print(f"  {INDEX}（内联块已替换）")


if __name__ == "__main__":
    main()
