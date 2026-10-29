"""
启动页（原生 splash）生成：深色渐变底 + 居中的 Pi 标识。

为什么要做：Capacitor 模板自带的 splash.png 是**纯白**的，
深色主题下会先闪一下白色再进 App，很突兀。

这里复用 gen-icon.py 的 Pi 渲染（白色字形 + 品牌蓝 i 点），
把每个密度/朝向的 splash.png 都重画成与 App 背景一致的样子。

用法：python scripts/gen-splash.py
"""

from __future__ import annotations

import glob
import importlib.util
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

# 复用图标脚本里的渲染函数（文件名带连字符，只能用 importlib 加载）
_spec = importlib.util.spec_from_file_location("gen_icon", os.path.join(ROOT, "scripts", "gen-icon.py"))
if _spec is None or _spec.loader is None:  # pragma: no cover - 环境异常
    raise RuntimeError("加载 gen-icon.py 失败")
gen_icon = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen_icon)

# 启动页底色：比图标底稍深一点，避免和系统状态栏色条争抢注意力
SPLASH_CENTER = (30, 30, 35)
SPLASH_EDGE = (10, 10, 12)


def background(width: int, height: int) -> Image.Image:
    """竖直方向的两端更暗的径向渐变。"""
    small_w, small_h = 120, max(2, int(120 * height / max(1, width)))
    img = Image.new("RGB", (small_w, small_h), SPLASH_EDGE)
    from PIL import ImageDraw

    draw = ImageDraw.Draw(img)
    cx, cy = small_w / 2, small_h * 0.46
    max_r = max(small_w, small_h) * 0.62
    steps = 90
    for i in range(steps, 0, -1):
        t = i / steps
        color = tuple(
            int(SPLASH_CENTER[c] + (SPLASH_EDGE[c] - SPLASH_CENTER[c]) * t) for c in range(3)
        )
        rx, ry = max_r * t, max_r * t * 1.18
        draw.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=color)
    return img.resize((width, height), Image.LANCZOS)


def render(width: int, height: int) -> Image.Image:
    canvas = background(width, height)
    # 标识取短边的 34%：和系统启动图标（Android 12 的 288dp 画布）观感接近
    mark_size = int(min(width, height) * 0.34)
    # render_pi 要求画布是正方形，按目标尺寸渲染再贴中心
    mark = gen_icon.render_pi(mark_size * 2, 0.72)
    mark = mark.resize((mark_size, mark_size), Image.LANCZOS)

    x = (width - mark_size) // 2
    y = (height - mark_size) // 2
    canvas.paste(mark, (x, y), mark)

    # 标识下方一层极淡的品牌蓝光晕，和桌面图标保持同一套视觉语言
    from PIL import ImageDraw, ImageFilter

    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)
    gx, gy = width / 2, y + mark_size * 0.92
    grx, gry = mark_size * 0.85, mark_size * 0.28
    gdraw.ellipse((gx - grx, gy - gry, gx + grx, gy + gry), fill=(79, 140, 255, 30))
    glow = glow.filter(ImageFilter.GaussianBlur(max(4, mark_size * 0.16)))
    canvas.paste(glow, (0, 0), glow)
    return canvas


def main() -> None:
    targets = sorted(glob.glob(os.path.join(RES, "drawable*", "splash.png")))
    if not targets:
        raise SystemExit("没找到 splash.png，先确认 android 平台已生成")

    for path in targets:
        size = Image.open(path).size
        image = render(*size)
        image.save(path, optimize=True)
        rel = os.path.relpath(path, RES)
        print(f"  {rel}: {size[0]}x{size[1]}")


if __name__ == "__main__":
    main()
