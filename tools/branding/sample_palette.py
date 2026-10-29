#!/usr/bin/env python3
"""Calibrate the mascot theme palette from the artwork itself.

Every colour `ThemeCatalog.mascotLavender` publishes has to be traceable to a
measured region of `docs/assets/branding/source-mascot.jpg`, not to a designer's
memory of it. This script is that measurement plus the derivation that turns the
measurement into a usable theme, and it fails (exit 1) if any published pair
drops under its WCAG minimum. Run it before touching the palette.

Two sampling rules, because the artwork has two kinds of feature:

* flat fields (backdrop, face, hair) -- the *modal* colour of a region, so a
  stray stroke crossing the box cannot drag the reading.
* hairline strokes (the wireframe polyhedra) -- the *bright percentile* of a
  region, because an unfilled outline always leaves the backdrop as the modal
  colour and a stroke core only appears in the top few percent of pixels.

Usage
-----
    python tools/branding/sample_palette.py
    python tools/branding/sample_palette.py --json
"""

from __future__ import annotations

import argparse
import colorsys
import json
from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE = REPO_ROOT / "docs" / "assets" / "branding" / "source-mascot.jpg"

# Flat-field regions as fractions of the artwork. Each box sits clear of the
# silhouette edge so the antialiased rim never contaminates the sample. The hair
# box hugs the left lock (x 0.02-0.09) because every wider box also contains the
# face or the backdrop and stops being modal for hair.
FLAT_REGIONS = {
    "backdrop": (0.92, 0.02, 0.99, 0.08),
    "face": (0.15, 0.42, 0.35, 0.52),
    "hair": (0.02, 0.50, 0.09, 0.70),
}
# Hairline regions, read at a luminance percentile instead of by mode.
STROKE_REGIONS = {"wireframe": (0.55, 0.10, 0.95, 0.42)}
STROKE_PERCENTILE = 99
# The blush is a soft overlay on the cream face, so it is never modal; isolate it
# by warmth (red clearly above blue, green pulled down from cream).
BLUSH_REGION = (0.12, 0.52, 0.45, 0.70)
BLUSH_FILTER = (14, 236)          # minimum red-blue distance, maximum green

# Derived-role transforms. Kept as constants so the derivation is reproducible:
# every published hex below is (measured colour -> these three numbers).
BACKGROUND_LIFT = (1.35, 0.720)   # saturation multiplier, target HSL lightness
SURFACE_LIFT = (1.10, 0.845)
ANSI_HARMONY = 0.18               # how far each functional hue leans to the wireframe
ANSI_BRIGHT_LIFT = 0.34           # how far each bright twin leans to the cream
TEXT_CHROMA = 0.55                # hair -> ink: how far toward black before clamping

TEXT_MINIMUM = 4.5
SURFACE_MINIMUM = 4.5
ACCENT_MINIMUM = 3.0
ANSI_MINIMUM = 4.5
ANSI_DIM_MINIMUM = 3.0            # ANSI 8 is the comment/dim role, not body text


def modal_colour(image: Image.Image, box: tuple[float, float, float, float]) -> tuple[int, int, int]:
    width, height = image.size
    crop = image.crop((
        int(box[0] * width), int(box[1] * height),
        int(box[2] * width), int(box[3] * height),
    ))
    counts = crop.getcolors(maxcolors=1 << 20) or []
    if not counts:
        raise SystemExit(f"region {box} produced no pixels")
    return max(counts, key=lambda item: item[0])[1][:3]


def stroke_colour(image: Image.Image, box: tuple[float, float, float, float]) -> tuple[int, int, int]:
    width, height = image.size
    pixels = []
    for y in range(int(box[1] * height), int(box[3] * height), 2):
        for x in range(int(box[0] * width), int(box[2] * width), 2):
            rgb = image.getpixel((x, y))
            pixels.append((luminance(rgb), rgb))
    pixels.sort()
    index = min(len(pixels) - 1, int(len(pixels) * STROKE_PERCENTILE / 100))
    return pixels[index][1]


def blush_colour(image: Image.Image, box: tuple[float, float, float, float]) -> tuple[int, int, int]:
    width, height = image.size
    counts = {}
    minimum_distance, maximum_green = BLUSH_FILTER
    for y in range(int(box[1] * height), int(box[3] * height), 2):
        for x in range(int(box[0] * width), int(box[2] * width), 2):
            r, g, b = image.getpixel((x, y))
            if r - b >= minimum_distance and g <= maximum_green:
                bin_ = (r // 2 * 2, g // 2 * 2, b // 2 * 2)
                counts[bin_] = counts.get(bin_, 0) + 1
    if not counts:
        raise SystemExit("blush filter matched no pixel")
    return max(counts.items(), key=lambda item: item[1])[0]


def luminance(rgb: tuple[int, int, int]) -> float:
    def channel(value: int) -> float:
        c = value / 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    return 0.2126 * channel(rgb[0]) + 0.7152 * channel(rgb[1]) + 0.0722 * channel(rgb[2])


def contrast(first: tuple[int, int, int], second: tuple[int, int, int]) -> float:
    a, b = luminance(first), luminance(second)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)


def mix(first: tuple[int, int, int], second: tuple[int, int, int], fraction: float) -> tuple[int, int, int]:
    return tuple(round(a * (1 - fraction) + b * fraction) for a, b in zip(first, second))


def lift(rgb: tuple[int, int, int], saturation: float, lightness: float) -> tuple[int, int, int]:
    hue, _, sat = colorsys.rgb_to_hls(*[c / 255 for c in rgb])
    return tuple(round(c * 255) for c in colorsys.hls_to_rgb(hue, lightness, min(1.0, sat * saturation)))


def ensure_contrast(foreground: tuple[int, int, int], background: tuple[int, int, int],
                    minimum: float, toward: tuple[int, int, int] = (253, 241, 219)) -> tuple[int, int, int]:
    """Blend toward `toward` in 1% steps until the pair clears `minimum`."""
    for step in range(101):
        candidate = mix(foreground, toward, step / 100)
        if contrast(candidate, background) >= minimum:
            return candidate
    return toward


def hexof(rgb: tuple[int, int, int]) -> str:
    return "#%02X%02X%02X" % rgb


def derive(sampled: dict[str, tuple[int, int, int]]) -> dict[str, tuple[int, int, int]]:
    backdrop = sampled["backdrop"]
    face = sampled["face"]
    hair = sampled["hair"]
    wire = sampled["wireframe"]
    blush = sampled["blush"]

    background = lift(wire, *BACKGROUND_LIFT)
    surface = lift(wire, *SURFACE_LIFT)
    text = ensure_contrast(mix(hair, (0, 0, 0), TEXT_CHROMA), background, TEXT_MINIMUM, (0, 0, 0))
    # textDim must clear both the page and the lighter card, so clamp against the
    # page first and let the card inherit the stricter result.
    text_dim = ensure_contrast(mix(hair, background, 0.30), background, TEXT_MINIMUM, (0, 0, 0))
    accent = ensure_contrast(mix(wire, hair, 0.55), background, ACCENT_MINIMUM, (0, 0, 0))

    ansi_canonical = {
        "red": (206, 92, 88), "green": (122, 158, 112), "yellow": (212, 172, 98),
        "blue": (112, 142, 198), "magenta": (182, 118, 190), "cyan": (108, 174, 176),
        "white": (216, 210, 222),
    }
    ansi: dict[str, tuple[int, int, int]] = {"black": mix(hair, backdrop, 0.30)}
    for name, colour in ansi_canonical.items():
        ansi[name] = ensure_contrast(mix(colour, wire, ANSI_HARMONY), backdrop, ANSI_MINIMUM)
    for name, colour in list(ansi.items()):
        ansi["bright_" + name] = mix(colour, face, ANSI_BRIGHT_LIFT)
    # Dim gray carries comments and disabled text, so it has its own floor: the
    # cream lift above alone leaves it just under the 3:1 readability line.
    ansi["bright_black"] = ensure_contrast(mix(hair, face, 0.55), backdrop, ANSI_DIM_MINIMUM)

    roles = {
        "background": background,
        "surface": surface,
        "surfaceTranslucent": (background[0], background[1], background[2]),
        "primary": hair,
        "accent": accent,
        "text": text,
        "textDim": text_dim,
        "onPrimary": face,
        "outline": mix(wire, text, 0.35),
        "glow": accent,
        "terminalBackground": backdrop,
        "terminalForeground": face,
        "blush": blush,
    }
    roles.update({"ansi_" + name: colour for name, colour in ansi.items()})
    return roles


def checks(roles: dict[str, tuple[int, int, int]]) -> list[tuple[str, float, float]]:
    """Every published pair, with the minimum it has to clear."""
    pairs = [
        ("text / background", roles["text"], roles["background"], TEXT_MINIMUM),
        ("text / surface", roles["text"], roles["surface"], TEXT_MINIMUM),
        ("textDim / background", roles["textDim"], roles["background"], TEXT_MINIMUM),
        ("textDim / surface", roles["textDim"], roles["surface"], TEXT_MINIMUM),
        ("onPrimary / primary", roles["onPrimary"], roles["primary"], TEXT_MINIMUM),
        ("onPrimary / terminal", roles["terminalForeground"], roles["terminalBackground"], TEXT_MINIMUM),
        ("accent / background", roles["accent"], roles["background"], ACCENT_MINIMUM),
        ("primary / background", roles["primary"], roles["background"], ACCENT_MINIMUM),
        ("outline / surface", roles["outline"], roles["surface"], ACCENT_MINIMUM),
    ]
    for name in ("red", "green", "yellow", "blue", "magenta", "cyan", "white"):
        pairs.append((f"ansi_{name} / terminal", roles["ansi_" + name], roles["terminalBackground"], ANSI_MINIMUM))
        pairs.append((f"ansi_bright_{name} / terminal", roles["ansi_bright_" + name],
                      roles["terminalBackground"], ANSI_MINIMUM))
    pairs.append(("ansi_black / terminal", roles["ansi_black"], roles["terminalBackground"], 1.0))
    pairs.append(("ansi_bright_black / terminal", roles["ansi_bright_black"],
                  roles["terminalBackground"], ANSI_DIM_MINIMUM))
    return pairs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    parser.add_argument("--source", default=str(SOURCE))
    args = parser.parse_args()

    image = Image.open(args.source).convert("RGB")
    sampled = {name: modal_colour(image, box) for name, box in FLAT_REGIONS.items()}
    sampled.update({name: stroke_colour(image, box) for name, box in STROKE_REGIONS.items()})
    sampled["blush"] = blush_colour(image, BLUSH_REGION)
    roles = derive(sampled)
    pairs = checks(roles)
    failures = [name for name, foreground, background, minimum in pairs
                if contrast(foreground, background) < minimum]

    if args.json:
        print(json.dumps({
            "sampled": {k: hexof(v) for k, v in sampled.items()},
            "roles": {k: hexof(v) for k, v in roles.items()},
            "contrast": [{"pair": n, "ratio": round(contrast(f, b), 2), "minimum": m}
                         for n, f, b, m in pairs],
            "failures": failures,
        }, indent=2, ensure_ascii=False))
        return 1 if failures else 0

    print(f"source: {args.source}")
    print("\n-- measured --")
    for name, rgb in sampled.items():
        print(f"  {name:12s} {hexof(rgb)}")

    print("\n-- theme roles --")
    for name, rgb in roles.items():
        print(f"  {name:22s} {hexof(rgb)}")

    print("\n-- contrast (WCAG 2.1) --")
    for name, foreground, background, minimum in pairs:
        ratio = contrast(foreground, background)
        print(f"  {'ok  ' if ratio >= minimum else 'FAIL'} {name:34s} {ratio:5.2f}:1  (min {minimum})")
    if failures:
        print(f"\n{len(failures)} pair(s) under minimum: {', '.join(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
