"""Generates the app icons in assets/ from the master logo image.

Usage:
    python3 -I scripts/make-icons.py [source_image]

If source_image is omitted, regenerates adaptive-foreground.png and
adaptive-monochrome.png from assets/icon.png.
"""
import os
import sys
from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets")
S = 1024


def extract_monochrome(im: Image.Image) -> Image.Image:
    """Extract a crisp white-on-transparent silhouette of the mint-emerald chevron + node."""
    w, h = im.size
    px = im.load()
    alpha = Image.new("L", (w, h), 0)
    apx = alpha.load()

    # The centered glyph sits comfortably inside the Android 66% adaptive icon safe zone
    # (x in [350..673], y in [289..736]). Restricting extraction to a generous bounding box
    # guarantees zero stray background pixels outside the emblem.
    x0, x1 = int(w * 0.3125), int(w * 0.6885)
    y0, y1 = int(h * 0.2539), int(h * 0.7471)
    for y in range(y0, y1):
        for x in range(x0, x1):
            r, g, _b, _a = px[x, y]
            # Background gradient has g - r <= 10 and g <= 48; glyph has strong green chroma
            # or bright specular highlight. Smoothstep produces clean anti-aliased edges.
            s = max((g - r - 11.0) / 18.0, (g - 52.0) / 24.0)
            if s <= 0.0:
                a = 0
            elif s >= 1.0:
                a = 255
            else:
                s = s * s * (3.0 - 2.0 * s)
                a = int(round(s * 255.0))
            apx[x, y] = a

    mono = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    mono.putalpha(alpha)
    return mono


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    default_src = os.path.join(OUT, "icon.png")
    src_path = sys.argv[1] if len(sys.argv) > 1 else default_src

    im = Image.open(src_path).convert("RGBA")
    if im.size != (S, S):
        im = im.resize((S, S), Image.LANCZOS)

    if os.path.abspath(src_path) != os.path.abspath(default_src):
        im.save(default_src, format="PNG", optimize=True)
    im.save(os.path.join(OUT, "adaptive-foreground.png"), format="PNG", optimize=True)
    extract_monochrome(im).save(os.path.join(OUT, "adaptive-monochrome.png"), format="PNG", optimize=True)
    print("icons written to", os.path.abspath(OUT))


if __name__ == "__main__":
    main()
