"""
App 图标生成：以桌面端图标（黑底圆角 + 白色 Pi）为基础重新设计。

设计：
  - 深色径向渐变底（中心 #232329 → 边缘 #0c0c0e）
  - 底部一层极淡的品牌蓝光晕，小尺寸下也能和别的深色图标区分
  - 主体为白色 "Pi"（Arial Black），i 的点单独染成品牌蓝，作为唯一记忆点

输出：Android mipmap（legacy / round / adaptive 前景 + 背景）

用法：python scripts/gen-icon.py
"""

from __future__ import annotations

import os

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
OUT = os.path.join(ROOT, "assets-src")

BG_CENTER = (35, 35, 41)
BG_EDGE = (12, 12, 14)
ACCENT = (79, 140, 255)
WHITE = (255, 255, 255)
RADIUS_RATIO = 0.225

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\ariblk.ttf",
    r"C:\Windows\Fonts\seguibl.ttf",
    r"C:\Windows\Fonts\arialbd.ttf",
]

_font_cache: dict[int, ImageFont.FreeTypeFont] = {}


def pick_font(px: int) -> ImageFont.FreeTypeFont:
    px = max(8, int(px))
    if px in _font_cache:
        return _font_cache[px]
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            font = ImageFont.truetype(path, px)
            _font_cache[px] = font
            return font
    raise RuntimeError("找不到可用字体")


def fit_font(text: str, target_width: float) -> ImageFont.FreeTypeFont:
    """二分找出让 text 宽度最接近 target_width 的字号。"""
    probe = ImageDraw.Draw(Image.new("L", (8, 8)))
    lo, hi = 8, 4096
    best = 8
    while lo <= hi:
        mid = (lo + hi) // 2
        width = probe.textlength(text, font=pick_font(mid))
        if width <= target_width:
            best = mid
            lo = mid + 1
        else:
            hi = mid - 1
    return pick_font(best)


def render_pi(canvas: int, extent: float, with_accent: bool = True) -> Image.Image:
    """
    在 canvas×canvas 的透明画布上居中渲染 Pi。

    extent = **最长边**占画布的比例（不是宽度）。
    Pi 的高度明显大于宽度，只按宽度约束会让 adaptive icon 裁切后顶到边。
    """
    layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    limit = canvas * extent

    # 先按宽度试，再按实际高度收紧
    font = fit_font("Pi", limit)
    left, top, right, bottom = draw.textbbox((0, 0), "Pi", font=font)
    width, height = right - left, bottom - top
    if height > limit:
        font = fit_font("Pi", limit * (limit / height))
        left, top, right, bottom = draw.textbbox((0, 0), "Pi", font=font)
    x = (canvas - (right - left)) / 2 - left
    y = (canvas - (bottom - top)) / 2 - top
    draw.text((x, y), "Pi", font=font, fill=WHITE + (255,))

    if not with_accent:
        return layer

    # i 的水平范围：P 之后紧排
    p_width = draw.textlength("P", font=font)
    i_left, _i_top, i_right, _i_bottom = draw.textbbox((0, 0), "i", font=font)
    i_x0 = x + p_width + i_left
    i_x1 = x + p_width + i_right

    # 精确找「点」的垂直范围：沿 i 的中心列扫 alpha，
    # 字体里点和竖条之间有空隙，第一段连续墨迹就是那个点。
    center_x = int((i_x0 + i_x1) / 2)
    alpha = layer.getchannel("A")
    column = [alpha.getpixel((center_x, py)) for py in range(canvas)]

    dot_top = dot_bottom = None
    started = False
    for py, value in enumerate(column):
        if value > 128:
            if not started:
                started = True
                dot_top = py
            dot_bottom = py
        elif started:
            break  # 第一段墨迹结束 = 点的底部

    if dot_top is None or dot_bottom is None:
        return layer

    dot_box = (i_x0, dot_top, i_x1, dot_bottom + 1)

    # 只在字形内部染蓝：矩形 mask × 字形 alpha
    blue_mask = Image.new("L", (canvas, canvas), 0)
    ImageDraw.Draw(blue_mask).rectangle(dot_box, fill=255)
    glyph_alpha = layer.getchannel("A")
    final_mask = ImageChops.multiply(blue_mask, glyph_alpha)

    blue_patch = Image.new("RGBA", (canvas, canvas), ACCENT + (255,))
    layer.paste(blue_patch, (0, 0), final_mask)
    return layer


def radial_background(size: int) -> Image.Image:
    small = 160
    img = Image.new("RGB", (small, small), BG_EDGE)
    draw = ImageDraw.Draw(img)
    center = small / 2
    for radius in range(int(center), 0, -1):
        t = radius / center
        color = tuple(int(BG_CENTER[i] + (BG_EDGE[i] - BG_CENTER[i]) * t) for i in range(3))
        draw.ellipse((center - radius, center - radius, center + radius, center + radius), fill=color)
    return img.resize((size, size), Image.LANCZOS)


def accent_glow(size: int) -> Image.Image:
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    cx, cy = size * 0.5, size * 0.78
    rx, ry = size * 0.42, size * 0.21
    draw.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=ACCENT + (26,))
    return layer.filter(ImageFilter.GaussianBlur(size * 0.13))


def rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask


def circle_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def build(size: int, *, shape: str = "rounded", extent: float = 0.62, ss: int = 2) -> Image.Image:
    big = size * ss
    base = radial_background(big).convert("RGBA")
    base.alpha_composite(accent_glow(big))
    base.alpha_composite(render_pi(big, extent))

    if shape == "circle":
        base.putalpha(circle_mask(big))
    elif shape == "rounded":
        base.putalpha(rounded_mask(big, int(size * RADIUS_RATIO * ss)))
    return base.resize((size, size), Image.LANCZOS)


def build_foreground(size: int, ss: int = 2) -> Image.Image:
    """Adaptive icon 前景：透明底，字形收进安全区（约 58%）。"""
    big = size * ss
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    # 安全区只保留 72/108 ≈ 66%，所以字形最长边按 0.46 给，
    # 裁切后视觉占比约 70%（再大就会顶到边）
    canvas.alpha_composite(render_pi(big, 0.46))
    return canvas.resize((size, size), Image.LANCZOS)


def main() -> None:
    NL = chr(10)
    os.makedirs(OUT, exist_ok=True)

    build(1024, ss=2).save(os.path.join(OUT, "app-icon-1024.png"))
    build(512, ss=2).save(os.path.join(OUT, "app-icon-512.png"))
    build(192, ss=4).save(os.path.join(OUT, "app-icon-192.png"))
    print("源图: app-icon-1024 / 512 / 192")

    legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, px in legacy.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        if not os.path.isdir(folder):
            continue
        build(px, ss=8).save(os.path.join(folder, "ic_launcher.png"))
        build(px, shape="circle", ss=8).save(os.path.join(folder, "ic_launcher_round.png"))

    foreground = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
    for density, px in foreground.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        if not os.path.isdir(folder):
            continue
        build_foreground(px, ss=2).save(os.path.join(folder, "ic_launcher_foreground.png"))

    # adaptive icon 的 background 层用 values/ic_launcher_background.xml 的颜色，
    # 这里同步写一份，保证颜色和图标底色一致
    color_xml = os.path.join(RES, "values", "ic_launcher_background.xml")
    if os.path.isdir(os.path.dirname(color_xml)):
        hex_color = "#%02x%02x%02x" % BG_CENTER
        lines = [
            '<?xml version="1.0" encoding="utf-8"?>',
            "<resources>",
            '    <color name="ic_launcher_background">' + hex_color + "</color>",
            "</resources>",
        ]
        with open(color_xml, "w", encoding="utf-8") as fh:
            fh.write(chr(10).join(lines) + chr(10))
    print("Android mipmap 已更新（legacy / round / foreground + 背景色）")


if __name__ == "__main__":
    main()
