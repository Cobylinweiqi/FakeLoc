#!/usr/bin/env python3
"""Rebuild the launcher icon layers from one source artwork.

The adaptive icon is two layers, and the source art is a single square image.
Handing that square image straight to `<foreground>` would work, but the
launcher crops both layers to a shape whose visible circle is only 66dp of the
108dp canvas — so the artwork would be silently zoomed by 1.6x and the pin
would end up touching the mask edge. This script instead does the split a
designer would do by hand:

  background  the colour field alone, bleeding to all four edges
  foreground  the pin, scaled and centred so the dashed ring encloses the
              artwork rather than running into the mask

Both layers are derived from the same source, so the seam between the feathered
foreground edge and the background is the same gradient on both sides and stays
invisible.

Usage:
    python3 tools/make-icons.py tools/icon-master-pixel.png  # the committed master
    python3 tools/make-icons.py tools/icon-master.jpg        # the previous master
    python3 tools/make-icons.py <any-1024-square.png>        # a new artwork

Writes drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher_{background,foreground}.webp.
Requires Pillow (`python3 -m pip install pillow`); no other dependency, because
this has to be re-runnable on a machine that has no ImageMagick.
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image, ImageFilter, features

# Where the artwork's dashed ring sits, in source pixels, and how wide it is.
# Measured once from the 1024px master (see the commit that added this script)
# so that the composition below is reproducible instead of eyeballed.
SRC_SIZE = 1024
# Where the artwork's content sits, in source pixels. Measured once from the
# 1024px pixel-art master (tools/icon-master-pixel.png) by scanning the
# non-background bbox — the whole composition, scattered signal dots included:
# x 169-834, y 195-828. The script scales the artwork about this centre, so a
# re-mastered artwork only needs these two lines re-measured.
SRC_CONTENT_CENTER = (501.5, 512.0)
SRC_CONTENT_RADIUS = 332.0

# The whole composition (rings, pin tail and signal dots — the largest radius
# above) should occupy this fraction of the 108dp canvas as a diameter.
#
# 0.45, not the 0.35 the previous artwork used. That artwork's ring was only a
# frame around the pin, so the pin alone was sized (0.35); this one is a full
# circular composition — dashed ring plus scattered dots — and the *ring* is
# what the eye reads as the icon's edge. At 0.45 the composition is 74% of the
# 66dp visible circle (48.6dp against 66dp), matching the two-thirds-to-
# three-quarters proportion an app icon is supposed to have, and the scattered
# dots keep ~9dp of clearance on every mask. Same clearance discipline as the
# 0.35 note below, sized to this artwork's geometry.
RING_SPAN = 0.45

DENSITIES = {
    "mdpi": 1,
    "hdpi": 1.5,
    "xhdpi": 2,
    "xxhdpi": 3,
    "xxxhdpi": 4,
}

# 108dp is the adaptive-icon canvas; these are that in pixels per density.
CANVAS_DP = 108

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

# The layers are photographic gradients, which PNG stores about as badly as it
# can — the two xxxhdpi layers came to 303 KB as PNG against 30 KB as lossy
# WebP. MinSdk is 29, so alpha-carrying WebP has been available since long
# before the floor, and `@drawable/ic_launcher_foreground` resolves regardless
# of the extension. Quality 92 is the point where a 48dp icon and a 432px
# preview are both indistinguishable from the PNG.
WEBP_QUALITY = 92


def write(image: Image.Image, directory: str, name: str) -> None:
    if features.check("webp"):
        path = os.path.join(directory, name + ".webp")
        image.save(path, "WEBP", quality=WEBP_QUALITY, method=6)
        return
    # Pillow here was built without libwebp. A 10x larger icon is better than a
    # build that silently ships no icon at all.
    print("warning: WebP unavailable, falling back to PNG", file=sys.stderr)
    image.save(os.path.join(directory, name + ".png"), optimize=True)


def build_background(source: Image.Image, px: int) -> Image.Image:
    """The colour field with the pin blurred away.

    A blur this strong (nine per cent of the canvas) turns the artwork into a
    soft wash in the middle, which is behind the foreground anyway. What
    matters is that the edges keep the artwork's own palette — for the pixel
    master that is a near-uniform paper-grey — so a launcher that shows more
    than the visible circle still blends with the foreground layer rather than
    meeting a flat fallback colour.
    """
    radius = max(2.0, px * 0.09)
    blurred = source.filter(ImageFilter.GaussianBlur(radius=radius * SRC_SIZE / px))
    return blurred.resize((px, px), Image.LANCZOS)


def build_foreground(source: Image.Image, px: int, zoom: float) -> Image.Image:
    """The artwork scaled about the ring centre, with the outer edge feathered.

    Feathering starts at 0.42 of the canvas width, i.e. outside the 66dp
    visible circle, so the mask never reaches a semi-transparent pixel — but on
    a square or rounded-square mask the layer dissolves into the background
    instead of ending on a hard edge.
    """
    scale = zoom * px / SRC_SIZE
    drawn = source.resize(
        (max(1, round(SRC_SIZE * scale)), max(1, round(SRC_SIZE * scale))),
        Image.LANCZOS,
    ).convert("RGBA")

    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    cx, cy = SRC_CONTENT_CENTER
    canvas.paste(drawn, (round(px / 2 - cx * scale), round(px / 2 - cy * scale)))

    # Radial alpha ramp, computed on a quarter-size mask and scaled up: at these
    # radii the ramp is so gradual that the interpolation error is invisible,
    # and px*px per-pixel Python would be slow at xxxhdpi.
    ramp_px = max(16, px // 4)
    nonzero, fade_end = 0.42, 0.52
    mask = Image.new("L", (ramp_px, ramp_px), 0)
    load = mask.load()
    half = ramp_px / 2
    inner, outer = nonzero * ramp_px, fade_end * ramp_px
    for y in range(ramp_px):
        dy = y + 0.5 - half
        for x in range(ramp_px):
            d = ((x + 0.5 - half) ** 2 + dy * dy) ** 0.5
            if d <= inner:
                load[x, y] = 255
            elif d >= outer:
                load[x, y] = 0
            else:
                load[x, y] = round(255 * (outer - d) / (outer - inner))
    mask = mask.resize((px, px), Image.BILINEAR)

    canvas.putalpha(mask)
    return canvas


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source")
    parser.add_argument(
        "--zoom",
        type=float,
        default=RING_SPAN * SRC_SIZE / (2 * SRC_CONTENT_RADIUS),
        help="artwork scale relative to a full-bleed mapping (default: derived from RING_SPAN)",
    )
    args = parser.parse_args()

    source = Image.open(args.source).convert("RGB")
    if source.size != (SRC_SIZE, SRC_SIZE):
        source = source.resize((SRC_SIZE, SRC_SIZE), Image.LANCZOS)

    for name, factor in DENSITIES.items():
        px = round(CANVAS_DP * factor)
        out_dir = os.path.join(RES, "drawable-" + name)
        os.makedirs(out_dir, exist_ok=True)
        write(build_background(source, px), out_dir, "ic_launcher_background")
        write(build_foreground(source, px, args.zoom), out_dir, "ic_launcher_foreground")
        print(f"{name:8s} {px:3d}px  zoom={args.zoom:.3f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
