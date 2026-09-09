#!/usr/bin/env python3
"""Rasterises the launcher icon (black folder, matrix-green outline).

The adaptive icon in res/mipmap-anydpi-v26 is the source of truth for the shape;
this script reproduces the same geometry for the legacy mipmap PNGs (pre-adaptive
launchers) and the 512x512 icon that Play Console requires for the store listing.

Usage: python3 tools/generate_launcher_icons.py
"""

from __future__ import annotations

import math
import os
import struct
import zlib

# Folder outline in the 108x108 adaptive-icon viewport, matching
# res/drawable/ic_launcher_foreground.xml.
VIEWPORT = 108.0
FOLDER = [(28, 74), (28, 34), (46, 34), (52, 42), (80, 42), (80, 74)]
STROKE_WIDTH = 5.5

BACKGROUND = (0, 0, 0)
OUTLINE = (0x00, 0xFF, 0x41)

# The adaptive foreground keeps the folder inside the 66dp safe zone; legacy and
# store icons have no mask cutting into them, so the shape is scaled up to fill.
LEGACY_ZOOM = 1.35

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
MIPMAP_DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def segments(points: list[tuple[float, float]]) -> list[tuple[float, float, float, float]]:
    closed = points + [points[0]]
    return [
        (closed[i][0], closed[i][1], closed[i + 1][0], closed[i + 1][1])
        for i in range(len(points))
    ]


def distance_to_segment(x: float, y: float, seg: tuple[float, float, float, float]) -> float:
    x1, y1, x2, y2 = seg
    dx, dy = x2 - x1, y2 - y1
    length_sq = dx * dx + dy * dy
    t = 0.0 if length_sq == 0 else max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / length_sq))
    return math.hypot(x - (x1 + t * dx), y - (y1 + t * dy))


def rounded_rect_distance(x: float, y: float, half: float, radius: float) -> float:
    """Signed distance to a rounded square centred in the viewport."""
    px, py = abs(x - VIEWPORT / 2) - (half - radius), abs(y - VIEWPORT / 2) - (half - radius)
    outside = math.hypot(max(px, 0.0), max(py, 0.0))
    return outside + min(max(px, py), 0.0) - radius


def coverage(distance: float, unit: float) -> float:
    """Analytic anti-aliasing: distance is signed, unit is one pixel in viewport units."""
    return max(0.0, min(1.0, 0.5 - distance / unit))


def render(size: int, shape: str, zoom: float) -> bytes:
    unit = VIEWPORT / size
    centre = VIEWPORT / 2
    scaled = [(centre + (x - centre) * zoom, centre + (y - centre) * zoom) for x, y in FOLDER]
    edges = segments(scaled)
    half_stroke = STROKE_WIDTH * zoom / 2
    rows = bytearray()
    for py in range(size):
        rows.append(0)  # PNG filter type: none
        vy = (py + 0.5) * unit
        for px in range(size):
            vx = (px + 0.5) * unit
            if shape == "square":
                background = 1.0
            elif shape == "circle":
                background = coverage(math.hypot(vx - centre, vy - centre) - centre, unit)
            else:
                background = coverage(rounded_rect_distance(vx, vy, centre, centre * 0.44), unit)
            outline = 0.0
            if background > 0.0:
                nearest = min(distance_to_segment(vx, vy, edge) for edge in edges)
                outline = coverage(nearest - half_stroke, unit) * background
            rows.extend(
                round(BACKGROUND[i] * (1 - outline) + OUTLINE[i] * outline) for i in range(3)
            )
            rows.append(round(background * 255))
    return bytes(rows)


def write_png(path: str, size: int, raw: bytes) -> None:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(png)
    print(f"wrote {os.path.relpath(path, ROOT)} ({size}x{size})")


def main() -> None:
    for density, size in MIPMAP_DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        write_png(os.path.join(folder, "ic_launcher.png"), size, render(size, "rounded", LEGACY_ZOOM))
        write_png(
            os.path.join(folder, "ic_launcher_round.png"), size, render(size, "circle", LEGACY_ZOOM)
        )
    store = os.path.join(ROOT, "docs", "store", "play-icon-512.png")
    write_png(store, 512, render(512, "square", LEGACY_ZOOM))
    # Google's OAuth consent screen ("Branding") takes a 120x120 square logo.
    oauth = os.path.join(ROOT, "docs", "store", "oauth-consent-logo-120.png")
    write_png(oauth, 120, render(120, "square", LEGACY_ZOOM))


if __name__ == "__main__":
    main()
