#!/usr/bin/env python3
"""Renders the LAN Beam icon to PNG without any imaging library.

Geometry is the same as res/drawable/ic_launcher_{background,foreground}.xml (108-unit adaptive
icon canvas). Produces the legacy launcher PNGs (Android 7.x, pre-adaptive), the 512 px Play Store
icon and a 1024x500 feature graphic.

    python3 scripts/make_icons.py
"""
import math
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")
STORE = os.path.join(ROOT, "store")

C0 = (0x43, 0x38, 0xCA)  # indigo
C1 = (0x8B, 0x5C, 0xF6)  # violet
STROKE = 6.5
SEGMENTS = [  # (x1, y1, x2, y2) in 108-unit space
    (45, 69, 45, 40), (36, 49, 45, 40), (45, 40, 54, 49),
    (63, 39, 63, 68), (54, 59, 63, 68), (63, 68, 72, 59),
]
RING = (54, 54, 27, 2.5, 0.30)  # cx, cy, r, width, alpha


def seg_dist(px, py, x1, y1, x2, y2):
    dx, dy = x2 - x1, y2 - y1
    t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))


def cov(d, px_size):
    """Anti-aliased coverage for signed distance d (negative inside), in icon units."""
    return max(0.0, min(1.0, 0.5 - d / px_size))


def rounded_rect_sdf(x, y, w, h, r):
    qx = abs(x - w / 2) - (w / 2 - r)
    qy = abs(y - h / 2) - (h / 2 - r)
    return math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - r


def write_png(path, w, h, rows):
    raw = b"".join(b"\x00" + bytes(r) for r in rows)
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def glyph_alpha(u, v, unit):
    """White coverage of ring+arrows at icon coordinate (u, v) in 108 space."""
    a = 0.0
    cx, cy, r, rw, ra = RING
    a = max(a, ra * cov(abs(math.hypot(u - cx, v - cy) - r) - rw / 2, unit))
    d = min(seg_dist(u, v, *s) for s in SEGMENTS) - STROKE / 2
    return max(a, cov(d, unit))


def render_icon(size, shape):
    """shape: 'square' (rounded, legacy), 'round' (circle, legacy) or 'full' (Play Store, full bleed)."""
    # Legacy icons show the adaptive 72-unit visible area (18..90) scaled to the image.
    span, off = (72.0, 18.0) if shape != "full" else (108.0 * 0.78, 108.0 * 0.11)
    unit = span / size
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            u = off + (x + 0.5) * unit
            v = off + (y + 0.5) * unit
            if shape == "round":
                mask = cov(math.hypot(x + 0.5 - size / 2, y + 0.5 - size / 2) - size / 2 + 0.5, 1)
            elif shape == "square":
                mask = cov(rounded_rect_sdf(x + 0.5, y + 0.5, size, size, size * 0.22), 1)
            else:
                mask = 1.0
            t = ((x + y) / (2.0 * size))
            bg = [C0[i] + (C1[i] - C0[i]) * t for i in range(3)]
            g = glyph_alpha(u, v, unit)
            rgb = [int(bg[i] * (1 - g) + 255 * g + 0.5) for i in range(3)]
            row += rgb + [int(255 * mask + 0.5)]
        rows.append(row)
    return rows


def feature_graphic(w=1024, h=500):
    rows = []
    s = 300.0  # glyph box in px
    ox, oy = (w - s) / 2, (h - s) / 2
    unit = 72.0 / s
    for y in range(h):
        row = []
        for x in range(w):
            t = (x / w) * 0.7 + (y / h) * 0.3
            bg = [C0[i] + (C1[i] - C0[i]) * t for i in range(3)]
            # faint concentric waves behind the glyph
            d = math.hypot(x - w / 2, y - h / 2)
            wave = 0.06 * cov(abs((d % 70) - 35) - 2, 1.5) if d > 170 else 0
            g = 0.0
            if ox <= x < ox + s and oy <= y < oy + s:
                g = glyph_alpha(18 + (x - ox + 0.5) * unit, 18 + (y - oy + 0.5) * unit, unit)
            g = max(g, wave)
            row += [int(bg[i] * (1 - g) + 255 * g + 0.5) for i in range(3)] + [255]
        rows.append(row)
    return rows


def main():
    for folder, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        d = os.path.join(RES, "mipmap-" + folder)
        for old in ("ic_launcher.webp", "ic_launcher_round.webp"):
            p = os.path.join(d, old)
            if os.path.exists(p):
                os.remove(p)
        write_png(os.path.join(d, "ic_launcher.png"), px, px, render_icon(px, "square"))
        write_png(os.path.join(d, "ic_launcher_round.png"), px, px, render_icon(px, "round"))
    write_png(os.path.join(STORE, "icon-512.png"), 512, 512, render_icon(512, "full"))
    write_png(os.path.join(STORE, "feature-graphic-1024x500.png"), 1024, 500, feature_graphic())
    print("icons written")


if __name__ == "__main__":
    main()
