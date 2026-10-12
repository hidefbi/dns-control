#!/usr/bin/env python3
"""Render the DNS Control launcher icon.

Design: three nodes D (DNS), S (Shield/Secure) and N (Network) joined by a
triangle, with a small smiling face in the centre. Drawn at 4x and reduced
with Lanczos resampling, so edges stay smooth at every density.

Run from the repository root:

    python3 design/render_icons.py

Outputs
  app/src/main/res/drawable-nodpi/ic_dnsc_foreground.png   432x432 (108dp adaptive foreground)
  app/src/main/res/drawable-nodpi/ic_dnsc_monochrome.png   432x432 (monochrome, Android 13+)
  app/src/main/res/mipmap-*/ic_launcher[_round].png        legacy launcher icons
  app/src/main/res/drawable*/ic_launcher.png               same artwork as the mipmaps

Requires Pillow and DejaVuSans-Bold.ttf.
"""

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

# Colours. BG matches values/ic_dnsc_colors.xml (#0D2E7E).
BG = (13, 46, 126, 255)
WHITE = (255, 255, 255, 255)
LINE = (255, 255, 255, 210)
NAVY = (13, 46, 126, 255)
TRANSPARENT = (0, 0, 0, 0)

CANVAS = 432          # 108dp at xxxhdpi
SS = 4                # supersampling factor
W = CANVAS * SS

# Geometry in 1x (432px) units. The adaptive-icon safe zone is a 264px circle
# centred on (216, 216); every element stays inside it.
CX, CY = 216, 220     # centre of the triangle
TRI_R = 88            # node distance from the centre
NODE_R = 38           # node radius
LINE_W = 10
FACE_R = 48           # dome radius
FACE_STROKE = 12


def polar(r: float, deg: float) -> tuple[float, float]:
    """Point at radius r and angle deg (0 = right, 90 = down in image space)."""
    a = math.radians(deg)
    return CX + r * math.cos(a), CY + r * math.sin(a)


def s(v: float) -> int:
    """Scale a 1x length to supersampled pixels."""
    return round(v * SS)


def point(p: tuple[float, float]) -> tuple[int, int]:
    return s(p[0]), s(p[1])


def draw_icon(mono: bool) -> Image.Image:
    """Return the foreground artwork at CANVAS size.

    mono=False: coloured artwork on transparency.
    mono=True:  white silhouette on transparency, letters cut out.
    """
    img = Image.new("RGBA", (W, W), TRANSPARENT)
    d = ImageDraw.Draw(img)

    nodes = {
        "D": polar(TRI_R, -90),   # top
        "S": polar(TRI_R, 150),   # bottom-left
        "N": polar(TRI_R, 30),    # bottom-right
    }
    line_colour = WHITE if mono else LINE
    names = list(nodes)
    for i in range(3):
        a, b = nodes[names[i]], nodes[names[(i + 1) % 3]]
        d.line([point(a), point(b)], fill=line_colour, width=s(LINE_W))

    for p in nodes.values():
        x, y = point(p)
        r = s(NODE_R)
        d.ellipse([x - r, y - r, x + r, y + r], fill=WHITE)

    font = ImageFont.truetype(FONT_PATH, s(56))
    for name, p in nodes.items():
        x, y = point(p)
        d.text((x, y), name, font=font, fill=TRANSPARENT if mono else NAVY, anchor="mm")

    # Centre face: a thick dome with closed "< >" eyes and a "^" nose.
    face_cx, face_cy = CX, CY + 16
    r = s(FACE_R)
    d.arc(
        [s(face_cx) - r, s(face_cy) - r, s(face_cx) + r, s(face_cy) + r],
        start=180,
        end=360,
        fill=WHITE,
        width=s(FACE_STROKE),
    )
    w = s(7)
    eye_y = face_cy - 22
    d.line([(s(face_cx - 16), s(eye_y - 10)), (s(face_cx - 28), s(eye_y)), (s(face_cx - 16), s(eye_y + 10))],
           fill=WHITE, width=w, joint="curve")
    d.line([(s(face_cx + 16), s(eye_y - 10)), (s(face_cx + 28), s(eye_y)), (s(face_cx + 16), s(eye_y + 10))],
           fill=WHITE, width=w, joint="curve")
    nose_y = face_cy + 8
    d.line([(s(face_cx - 10), s(nose_y + 9)), (s(face_cx), s(nose_y)), (s(face_cx + 10), s(nose_y + 9))],
           fill=WHITE, width=w, joint="curve")

    return img.resize((CANVAS, CANVAS), Image.LANCZOS)


def legacy(fg: Image.Image, size: int, round_mask: bool) -> Image.Image:
    # Legacy icons have no adaptive padding, so crop to the 264px safe zone
    # in the centre and scale it up to fill the square.
    bg = Image.new("RGBA", fg.size, BG)
    crop = Image.alpha_composite(bg, fg).crop((84, 84, 348, 348))
    out = crop.resize((size, size), Image.LANCZOS)
    if round_mask:
        mask = Image.new("L", (size * SS, size * SS), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size * SS - 1, size * SS - 1], fill=255)
        mask = mask.resize((size, size), Image.LANCZOS)
        out.putalpha(mask)
    return out


def main() -> None:
    fg = draw_icon(mono=False)
    mono = draw_icon(mono=True)

    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    fg.save(nodpi / "ic_dnsc_foreground.png", optimize=True)
    mono.save(nodpi / "ic_dnsc_monochrome.png", optimize=True)

    mipmap_sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, size in mipmap_sizes.items():
        out_dir = RES / f"mipmap-{density}"
        out_dir.mkdir(parents=True, exist_ok=True)
        legacy(fg, size, round_mask=False).save(out_dir / "ic_launcher.png", optimize=True)
        legacy(fg, size, round_mask=True).save(out_dir / "ic_launcher_round.png", optimize=True)

    # Drawable copies of the launcher icon (kept in sync with the mipmaps).
    drawable_dirs = {
        "drawable-mdpi": 48,
        "drawable-hdpi": 72,
        "drawable-xhdpi": 96,
        "drawable-xxhdpi": 144,
        "drawable-xxxhdpi": 192,
        "drawable-v24": 144,
        "drawable": 144,
    }
    for folder, size in drawable_dirs.items():
        out_dir = RES / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        legacy(fg, size, round_mask=False).save(out_dir / "ic_launcher.png", optimize=True)

    print("rendered icons into", RES)


if __name__ == "__main__":
    main()
