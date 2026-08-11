"""
Generates a high-quality ARCore reference image ("origin marker").

Why this exists
---------------
A plain QR code is a poor augmented image: it is repetitive, purely
black/white, and nearly rotationally symmetric, so ARCore's feature matcher
scores it badly and tracking is unreliable.

This builds a marker deliberately engineered for feature tracking:
  * strong ASYMMETRY  - each corner is different, so orientation is unambiguous
  * dense IRREGULAR detail - non-repeating shapes give unique feature points
  * COLOUR and gradients - more signal than flat black/white
  * high CONTRAST edges - what the detector actually keys on
  * a quiet centre panel - readable branding without hurting trackability

Output: app/src/main/assets/origin_marker.png  (square, print at 30 cm)

Run:  python tools/make_marker.py
"""

import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# Deterministic: regenerating gives the identical marker, so a printed copy
# stays valid after a re-run.
random.seed(20260811)

SIZE = 2048  # generous resolution so a 30 cm print stays crisp
OUT_DIR = os.path.join("app", "src", "main", "assets")
OUT_NAME = "origin_marker.png"

# Palette chosen for luminance separation as well as hue: the detector works
# on greyscale gradients, so colours that differ only in hue would not help.
NAVY = (13, 27, 42)
DEEP = (27, 36, 48)
CYAN = (79, 195, 247)
AMBER = (255, 179, 0)
CORAL = (239, 83, 80)
MINT = (102, 217, 175)
CREAM = (245, 240, 230)


def load_font(size, bold=True):
    """Best-effort system font lookup; falls back to Pillow's default."""
    candidates = [
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ]
    for path in candidates:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                pass
    return ImageFont.load_default()


def draw_background(draw):
    """Diagonal colour bands: large-scale structure the tracker can lock onto."""
    draw.rectangle([0, 0, SIZE, SIZE], fill=NAVY)
    band = SIZE // 14
    for i in range(-SIZE, SIZE * 2, band * 2):
        draw.polygon(
            [(i, 0), (i + band, 0), (i + band - SIZE, SIZE), (i - SIZE, SIZE)],
            fill=DEEP,
        )


def draw_corner_anchors(draw):
    """
    Four DIFFERENT corner glyphs.

    Deliberately not the identical finder patterns a QR code uses: making each
    corner unique removes rotational ambiguity, so the pose ARCore recovers
    cannot be 90 degrees out.
    """
    m = SIZE // 12
    box = SIZE // 6

    # Top-left: concentric squares
    for k, colour in enumerate([CYAN, NAVY, CYAN, NAVY]):
        pad = k * box // 8
        draw.rectangle([m + pad, m + pad, m + box - pad, m + box - pad], fill=colour)

    # Top-right: triangle stack
    x0 = SIZE - m - box
    for k, colour in enumerate([AMBER, NAVY, AMBER]):
        pad = k * box // 6
        draw.polygon(
            [
                (x0 + pad, m + box - pad),
                (x0 + box - pad, m + box - pad),
                (x0 + box // 2, m + pad),
            ],
            fill=colour,
        )

    # Bottom-left: concentric circles
    y0 = SIZE - m - box
    for k, colour in enumerate([CORAL, NAVY, CORAL, NAVY]):
        pad = k * box // 8
        draw.ellipse([m + pad, y0 + pad, m + box - pad, y0 + box - pad], fill=colour)

    # Bottom-right: irregular chevrons
    x0 = SIZE - m - box
    y0 = SIZE - m - box
    for k in range(4):
        off = k * box // 5
        draw.polygon(
            [
                (x0, y0 + off),
                (x0 + box - off, y0 + box),
                (x0 + box - off - box // 10, y0 + box),
                (x0, y0 + off + box // 10),
            ],
            fill=MINT if k % 2 == 0 else CREAM,
        )


def draw_scatter(draw):
    """Non-repeating shapes: the bulk of the unique feature points."""
    palette = [CYAN, AMBER, CORAL, MINT, CREAM]
    inner0, inner1 = SIZE * 0.30, SIZE * 0.70

    placed = 0
    attempts = 0
    while placed < 150 and attempts < 4000:
        attempts += 1
        r = random.randint(SIZE // 90, SIZE // 34)
        x = random.randint(r, SIZE - r)
        y = random.randint(r, SIZE - r)

        # Keep the centre panel clear so the wordmark stays legible.
        if inner0 < x < inner1 and inner0 < y < inner1:
            continue

        colour = random.choice(palette)
        kind = random.random()
        if kind < 0.34:
            draw.ellipse([x - r, y - r, x + r, y + r], fill=colour)
        elif kind < 0.67:
            rot = random.random() * math.tau
            pts = [
                (
                    x + r * math.cos(rot + i * math.tau / 3),
                    y + r * math.sin(rot + i * math.tau / 3),
                )
                for i in range(3)
            ]
            draw.polygon(pts, fill=colour)
        else:
            draw.rectangle(
                [x - r, y - int(r * 0.55), x + r, y + int(r * 0.55)], fill=colour
            )
        placed += 1


def fit_font(draw, text, max_width, start_size, bold=True):
    """Largest font size at which `text` still fits inside `max_width`."""
    size = start_size
    while size > 12:
        font = load_font(size, bold=bold)
        bbox = draw.textbbox((0, 0), text, font=font)
        if (bbox[2] - bbox[0]) <= max_width:
            return font
        size -= 2
    return load_font(12, bold=bold)


def draw_center_panel(img, draw):
    """Quiet panel with the wordmark - branding plus strong text edges."""
    pad = int(SIZE * 0.26)
    panel = [pad, int(SIZE * 0.40), SIZE - pad, int(SIZE * 0.60)]

    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        [panel[0] + 8, panel[1] + 10, panel[2] + 8, panel[3] + 10],
        radius=28,
        fill=(0, 0, 0, 130),
    )
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(12)))

    draw.rounded_rectangle(panel, radius=28, fill=CREAM)
    draw.rounded_rectangle(panel, radius=28, outline=NAVY, width=7)

    title = "SARAB VISION"
    sub = "AR ORIGIN MARKER"

    # Shrink to fit rather than assuming a size: the wordmark must never spill
    # outside the panel, which happens with wide system fonts.
    inner_width = (panel[2] - panel[0]) - int(SIZE * 0.06)
    title_font = fit_font(draw, title, inner_width, int(SIZE * 0.072))
    sub_font = fit_font(draw, sub, inner_width, int(SIZE * 0.030), bold=False)

    tb = draw.textbbox((0, 0), title, font=title_font)
    draw.text(
        ((SIZE - (tb[2] - tb[0])) / 2, panel[1] + int(SIZE * 0.026)),
        title,
        font=title_font,
        fill=NAVY,
    )

    sb = draw.textbbox((0, 0), sub, font=sub_font)
    draw.text(
        ((SIZE - (sb[2] - sb[0])) / 2, panel[1] + int(SIZE * 0.125)),
        sub,
        font=sub_font,
        fill=(90, 105, 120),
    )


def draw_edge_ticks(draw):
    """Irregular edge ticks: extra high-contrast detail near the borders."""
    n = 34
    for i in range(n):
        t = i / n
        length = SIZE // 46 + (i % 5) * SIZE // 300
        colour = [CYAN, AMBER, CORAL, MINT][i % 4]
        x = int(t * SIZE)
        draw.rectangle([x, 0, x + SIZE // 190, length], fill=colour)
        draw.rectangle([x, SIZE - length, x + SIZE // 190, SIZE], fill=colour)
        y = int(t * SIZE)
        draw.rectangle([0, y, length, y + SIZE // 190], fill=colour)
        draw.rectangle([SIZE - length, y, SIZE, y + SIZE // 190], fill=colour)


def main():
    img = Image.new("RGBA", (SIZE, SIZE), NAVY)
    draw = ImageDraw.Draw(img)

    draw_background(draw)
    draw_edge_ticks(draw)
    draw_scatter(draw)
    draw_corner_anchors(draw)
    draw_center_panel(img, draw)

    # Quiet border so the marker is separable from whatever wall it hangs on.
    draw.rectangle([0, 0, SIZE - 1, SIZE - 1], outline=CREAM, width=SIZE // 120)

    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, OUT_NAME)
    img.convert("RGB").save(out, "PNG", optimize=True)

    kb = os.path.getsize(out) / 1024
    print(f"Wrote {out}  ({SIZE}x{SIZE}, {kb:.0f} KB)")
    print("Print SQUARE at 30 cm wide to match ORIGIN_IMAGE_WIDTH_METERS = 0.30")


if __name__ == "__main__":
    main()
