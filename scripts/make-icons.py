"""Generates the app icons in assets/ (run: python3 -I scripts/make-icons.py)."""
import os
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets")
S = 1024
ACCENT = (124, 156, 255, 255)
ACCENT2 = (95, 211, 179, 255)


def glyph(size, color_screen, color_shield, scale=1.0, mono=False):
    """Monitor with a shield + prompt inside, centered, drawn at 4x then downsampled."""
    k = 4
    W = size * k
    im = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = W / 2
    u = W / 1024 * scale
    # monitor
    mw, mh = 560 * u, 380 * u
    top = c - 250 * u
    lw = int(34 * u)
    d.rounded_rectangle([c - mw / 2, top, c + mw / 2, top + mh], radius=int(56 * u), outline=color_screen, width=lw)
    # stand
    d.rounded_rectangle([c - 26 * u, top + mh, c + 26 * u, top + mh + 70 * u], radius=int(8 * u), fill=color_screen)
    d.rounded_rectangle([c - 150 * u, top + mh + 62 * u, c + 150 * u, top + mh + 96 * u], radius=int(17 * u), fill=color_screen)
    # shield
    sx, sy, sw, sh = c, top + 70 * u, 230 * u, 250 * u
    pts = [(sx, sy), (sx + sw / 2, sy + 44 * u), (sx + sw / 2, sy + 120 * u)]
    # smooth shield path
    import math
    path = [(sx - sw / 2, sy + 44 * u), (sx, sy), (sx + sw / 2, sy + 44 * u), (sx + sw / 2, sy + 120 * u)]
    n = 40
    for i in range(n + 1):
        a = i / n * math.pi / 2
        path.append((sx + sw / 2 * math.cos(a), sy + 120 * u + (sh - 120 * u) * math.sin(a)))
    for i in range(n + 1):
        a = math.pi / 2 + i / n * math.pi / 2
        path.append((sx + sw / 2 * math.cos(a), sy + 120 * u + (sh - 120 * u) * math.sin(a)))
    path.append((sx - sw / 2, sy + 44 * u))
    d.polygon(path, fill=color_shield)
    # prompt ">_" cut out of the shield
    cut = (0, 0, 0, 0) if not mono else (0, 0, 0, 0)
    pw = int(26 * u)
    cy = sy + 128 * u
    d.line([(sx - 62 * u, cy - 42 * u), (sx - 14 * u, cy), (sx - 62 * u, cy + 42 * u)], fill=cut, width=pw, joint="curve")
    d.rounded_rectangle([sx + 4 * u, cy + 30 * u, sx + 68 * u, cy + 30 * u + pw], radius=int(pw / 2), fill=cut)
    return im.resize((size, size), Image.LANCZOS)


def background(size):
    im = Image.new("RGBA", (size, size))
    d = ImageDraw.Draw(im)
    for y in range(size):
        t = y / size
        r = int(17 + (30 - 17) * t); g = int(21 + (37 - 21) * t); b = int(31 + (56 - 31) * t)
        d.line([(0, y), (size, y)], fill=(r, g, b, 255))
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([size * 0.2, size * 0.12, size * 0.8, size * 0.72], fill=(124, 156, 255, 60))
    return Image.alpha_composite(im, glow.filter(ImageFilter.GaussianBlur(size / 10)))


os.makedirs(OUT, exist_ok=True)
fg = glyph(S, (226, 232, 245, 255), ACCENT, scale=0.62)
fg.save(os.path.join(OUT, "adaptive-foreground.png"))
glyph(S, (255, 255, 255, 255), (255, 255, 255, 255), scale=0.62, mono=True).save(os.path.join(OUT, "adaptive-monochrome.png"))
bg = background(S)
icon = Image.alpha_composite(bg, glyph(S, (226, 232, 245, 255), ACCENT, scale=0.8))
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S, S], radius=230, fill=255)
icon.putalpha(mask)
icon.save(os.path.join(OUT, "icon.png"))
print("icons written to", os.path.abspath(OUT))
