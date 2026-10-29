#!/usr/bin/env python3
"""Generate the Orchestella launcher icon set from the mascot artwork.

The script is deliberately self-contained: Pillow only, no network, no numpy.
It is idempotent -- re-running it regenerates every asset byte-identically
(same Pillow build assumed) and rewrites the adaptive-icon XML resources.

Pipeline
--------
1. Background removal: a tolerance flood fill seeded from the four image
   borders.  Seeds are restricted to border pixels that already match the flat
   backdrop colour, because the mascot genuinely overflows the source artwork
   on the left and bottom edges -- a blind corner seed would eat the body.
2. Enclosed regions (the flood cannot reach them) are then classified one by
   one from a ring sampled clear of their own antialiased rim: lavender ring =
   hole in the artwork, cream ring = eye, anything else = keep.  The eyes are
   painted in the *backdrop* colour in the original, so a plain global colour
   threshold would punch them into alpha holes and they would show as white
   slits on any light surface.
3. The 1px rim of the silhouette that faces the transparent backdrop is
   decontaminated (its colour is replaced by the nearest interior colour, plus
   a 1px alpha feather) so the antialiased edge does not read as a dark/grey
   outline once composited on the dark icon background.

Compositions (three, selected by flag)
--------------------------------------
* default   -- the artwork itself is the icon: the source maps 1:1 onto the
               108dp adaptive canvas, the background is its own flat backdrop,
               and the monochrome plane is the mascot's own silhouette.
* --no-crop -- session 25's fit composition: the whole subject inside the
               66dp safe circle, the artwork's off-canvas edges continued by
               literal edge replication, wireframe background field.
* --cover   -- session 24's face-cover crop, kept only to reproduce that
               round's outputs.

No non-uniform resize anywhere.

Usage
-----
    python tools/branding/make_app_icons.py
    python tools/branding/make_app_icons.py --no-crop
    python tools/branding/make_app_icons.py --cover
    python tools/branding/make_app_icons.py --import-source <artwork>.jpg
"""


from __future__ import annotations

import argparse
import math
import shutil
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageStat

RESAMPLE = getattr(Image, "Resampling", Image).LANCZOS

REPO_ROOT = Path(__file__).resolve().parents[2]
RES_DIR = REPO_ROOT / "app" / "src" / "main" / "res"
BRANDING_DIR = REPO_ROOT / "docs" / "assets" / "branding"
SOURCE_COPY = BRANDING_DIR / "source-mascot.jpg"

# Measured from the artwork (see docs/assets/branding/README.md).
BACKGROUND = (36, 36, 36)      # #242424 flat backdrop
CREAM = (253, 241, 219)        # #FDF1DB face + cream ear fill, anchors the crop

# Manhattan distance thresholds (sum of |dR|+|dG|+|dB|).
EDGE_SEED_TOL = 12   # a border pixel this close to the backdrop may seed the flood
FLOOD_TOL = 24       # flood growth tolerance; the body sits at 102, the enclosed
                     # shadow at 17 but it is unreachable from the border anyway
ISLAND_TOL = 40      # "this is backdrop colour" threshold for enclosed islands
MIN_ISLAND_PX = 400  # ignore enclosed specks smaller than this
EYE_RING_TOL = 100   # an enclosed island ringed by cream is an eye, not a hole
MONO_OPEN_PX = 3     # morphological opening for the monochrome silhouette
CREAM_TOL = 60       # face detection tolerance

ADAPTIVE_CANVAS_DP = 108.0
LEGACY_ICON_DP = 48.0
# Legacy icons have no safe-zone padding, so they show the 72dp window of the
# adaptive canvas scaled up to the full icon (the stock Android mapping).  74dp
# keeps the whole crop inside the source artwork, so no edge is invented.
LEGACY_VIEW_DP = 74.0
SAFE_CIRCLE_DP = 66.0
VISIBLE_SQUARE_DP = 72.0

# Face bounding-box height as a fraction of the square crop side.
FACE_HEIGHT_RATIO = 0.54

# 1px outward feather of the silhouette edge, in 8-bit alpha.
EDGE_ALPHA = 96

DENSITIES = (
    ("mdpi", 1.0),
    ("hdpi", 1.5),
    ("xhdpi", 2.0),
    ("xxhdpi", 3.0),
    ("xxxhdpi", 4.0),
)


# Android Studio's icon template additionally draws a 66dp "safe circle" and
# recommends keeping key artwork inside it, because every mask shape it ships
# (72dp circle, 72dp squircle, rounded square, teardrop) contains that circle.
# A circle of diameter 66dp is inscribed in all of them: the squircle's minimum
# radius (along its axes) is 36dp and the round mask's is 36dp, both > 33dp.
# So "inside the 66dp circle" == "no mask shape can clip it", and that is the
# number the fit uses.
SAFE_RADIUS_DP = SAFE_CIRCLE_DP / 2.0        # 33dp

# Wireframe: the lavender strokes are pulled out by nearest-palette
# classification and the solid body is erased.  The right-hand polyhedron is
# used as itself -- no mirroring, no 90-degree cross.  An earlier revision
# mirrored it into a band and crossed two bands, which filled the icon with a
# symmetric starburst: at icon scale the strokes read as bars and the
# polyhedron identity is gone.  What survives on the launcher instead is the
# artwork's own composition idea -- two polyhedra at different scales and
# slight tilts, both overhanging the canvas so every cut end exits the mask.
LINE_BODY_CLEAR = 4     # source px kept clear around the solid silhouette
# Palette anchors, measured like every other colour in this pipeline
# (tools/branding/sample_palette.py prints the same numbers from the artwork).
LINE = (173, 148, 178)     # wireframe stroke core, #AD94B2
LINE_TOL = 60              # Manhattan distance that still counts as stroke lavender
BODY = (65, 55, 90)        # hair / body purple, #41375A
WIRE_REGION = (1380, 500, 1920, 1800)   # source box holding the right polyhedron
WIRE_SEAM_PX = 3        # morphological closing radius that heals JPEG pinholes
WIRE_ALPHA = 150        # 59% -- the field must stay behind the mascot
# Large copy: long side 1.75 canvas -> a ~7px source stroke lands near 1.1dp,
# thick enough to read at 48px.  Small copy: ~0.6dp before the mono boost.
WIRE_LARGE_COVER = 1.75
WIRE_LARGE_CENTRE = (0.62, 0.38)
WIRE_LARGE_ANGLE = 14.0
WIRE_SMALL_COVER = 0.95
WIRE_SMALL_CENTRE = (0.30, 0.74)
WIRE_SMALL_ANGLE = -10.0
WIRE_LAYOUT = (
    (WIRE_LARGE_COVER, WIRE_LARGE_CENTRE, WIRE_LARGE_ANGLE),
    (WIRE_SMALL_COVER, WIRE_SMALL_CENTRE, WIRE_SMALL_ANGLE),
)
# The monochrome glyph is drawn, not extracted: an open kite reads as random
# bars at launcher sizes, and mirroring the extraction doubles its vertices
# because the crystal's axis is not the bbox centre.  A closed elongated gem
# with a girdle and two facet lines keeps the artwork's wireframe idea with
# geometry that is exactly closed at every size.  Coordinates are fractions of
# the glyph box; the outline order traces the gem without lifting the pen.
CRYSTAL_OUTLINE = (
    (0.50, 0.00), (0.88, 0.26), (0.72, 0.60), (0.50, 1.00),
    (0.28, 0.60), (0.12, 0.26), (0.50, 0.00),
)
CRYSTAL_FACETS = (
    ((0.12, 0.26), (0.88, 0.26)),   # girdle
    ((0.28, 0.60), (0.72, 0.60)),   # lower facet break
    ((0.50, 0.00), (0.50, 0.26)),   # table edge
)
# The monochrome layer stands alone in the launcher, so its polyhedra must be
# self-contained: each crystal fully inside the 66dp safe circle, scaled down
# from the background field (a tall kite cut by the window reads as random
# bars, which is exactly what an earlier revision shipped).  Three sizes echo
# the artwork's scattered-crystal look.
MONO_LARGE_COVER = 0.60
MONO_LARGE_CENTRE = (0.45, 0.45)
MONO_LARGE_ANGLE = 0.0
MONO_SMALL_COVER = 0.34
MONO_SMALL_CENTRE = (0.67, 0.68)
MONO_SMALL_ANGLE = -18.0
MONO_TINY_COVER = 0.20
MONO_TINY_CENTRE = (0.30, 0.28)
MONO_TINY_ANGLE = 22.0
MONO_LAYOUT = (
    (MONO_LARGE_COVER, MONO_LARGE_CENTRE, MONO_LARGE_ANGLE),
    (MONO_SMALL_COVER, MONO_SMALL_CENTRE, MONO_SMALL_ANGLE),
    (MONO_TINY_COVER, MONO_TINY_CENTRE, MONO_TINY_ANGLE),
)
MONO_STROKE_BOOST = 3   # source px added to each side of the monochrome stroke
MASTER_PX = 1296        # 12 px/dp: feeds every square derivative at once

# The artwork is cut by its own left/bottom edges; replicating those rows and
# columns outward keeps the character's scale and moves the seam past the mask.
EDGE_COLOUR_TOL = 60      # Manhattan colour tolerance for a replicated pixel
EDGE_COLOUR_RATIO = 0.005  # at most 0.5% of the band may miss the edge colour
EDGE_ALPHA_FLOOR = 250     # band pixels must be opaque; a seam would read < 128
ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""

SENTINEL = (255, 0, 255)



ADAPTIVE_XML_BITMAP_BG = ADAPTIVE_XML.replace(
    '<background android:drawable="@color/ic_launcher_background" />',
    '<background android:drawable="@mipmap/ic_launcher_background" />',
)
# --------------------------------------------------------------------------- #
# Pillow mask helpers (all masks are mode "L" with values 0 or 255)
# --------------------------------------------------------------------------- #
def _mask_and(a: Image.Image, b: Image.Image) -> Image.Image:
    return ImageChops.multiply(a, b)


def _mask_or(a: Image.Image, b: Image.Image) -> Image.Image:
    return ImageChops.lighter(a, b)


def _mask_not(a: Image.Image) -> Image.Image:
    return a.point(lambda v: 255 - v)


def _dilate(a: Image.Image, radius: int) -> Image.Image:
    return a.filter(ImageFilter.MaxFilter(2 * radius + 1))


def _erode(a: Image.Image, radius: int) -> Image.Image:
    return a.filter(ImageFilter.MinFilter(2 * radius + 1))


def _colour_distance(img: Image.Image, colour: tuple[int, int, int]) -> Image.Image:
    """Manhattan colour distance to *colour*, clamped to 255 per channel sum."""
    diff = ImageChops.difference(img, Image.new("RGB", img.size, colour))
    r, g, b = diff.split()
    return ImageChops.add(ImageChops.add(r, g), b)


def _flood_spans(raw: bytes, seen: bytearray, w: int, h: int, start: int) -> int:
    """Scanline flood fill from *start*; marks *seen* and returns the area."""
    stack = [start]
    area = 0
    while stack:
        idx = stack.pop()
        if seen[idx] or not raw[idx]:
            continue
        y, x = divmod(idx, w)
        row = y * w
        x0 = x
        while x0 > 0 and raw[row + x0 - 1] and not seen[row + x0 - 1]:
            x0 -= 1
        x1 = x
        while x1 < w - 1 and raw[row + x1 + 1] and not seen[row + x1 + 1]:
            x1 += 1
        seen[row + x0:row + x1 + 1] = b"\x01" * (x1 - x0 + 1)
        area += x1 - x0 + 1
        for ny in (y - 1, y + 1):
            if 0 <= ny < h:
                nrow = ny * w
                nx = x0
                while nx <= x1:
                    if raw[nrow + nx] and not seen[nrow + nx]:
                        stack.append(nrow + nx)
                        while nx <= x1 and raw[nrow + nx]:
                            nx += 1
                    nx += 1
    return area


def _components(mask: Image.Image, min_area: int = 1) -> list[int]:
    """Seeds of every 8-connected component at least *min_area* pixels big."""
    w, h = mask.size
    raw = mask.tobytes().translate(bytes([0] * 255 + [1]))
    seen = bytearray(w * h)
    seeds: list[int] = []
    for start in range(w * h):
        if raw[start] and not seen[start]:
            if _flood_spans(raw, seen, w, h, start) >= min_area:
                seeds.append(start)
    return seeds


def _component_mask(mask: Image.Image, start: int) -> Image.Image:
    w, h = mask.size
    raw = mask.tobytes().translate(bytes([0] * 255 + [1]))
    seen = bytearray(w * h)
    _flood_spans(raw, seen, w, h, start)
    return Image.frombytes("L", mask.size, bytes(seen).translate(bytes([0] + [255] * 255)))


def _largest_component(mask: Image.Image) -> Image.Image:
    """Return the biggest 8-connected component of a binary mask."""
    w, h = mask.size
    raw = mask.tobytes().translate(bytes([0] * 255 + [1]))
    seen = bytearray(w * h)
    best_area, best_seed = 0, -1
    for start in range(w * h):
        if raw[start] and not seen[start]:
            area = _flood_spans(raw, seen, w, h, start)
            if area > best_area:
                best_area, best_seed = area, start
    if best_seed < 0:
        return Image.new("L", mask.size, 0)
    return _component_mask(mask, best_seed)


def _propagate_colour(
    rgb: Image.Image, seed: Image.Image, target: Image.Image, passes: int = 3
) -> Image.Image:
    """Grow colours out of *seed* into *target* (4-neighbour, nearest wins)."""
    known = seed.copy()
    acc = Image.new("RGB", rgb.size, BACKGROUND)
    acc.paste(rgb, (0, 0), seed)
    for _ in range(passes):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            fill = _mask_and(_mask_and(target, _mask_not(known)), ImageChops.offset(known, dx, dy))
            if fill.getbbox() is None:
                continue
            acc.paste(ImageChops.offset(acc, dx, dy), (0, 0), fill)
            known.paste(255, (0, 0), fill)
    return acc


# --------------------------------------------------------------------------- #
# Foreground extraction
# --------------------------------------------------------------------------- #
def _outside_mask(rgb: Image.Image) -> tuple[Image.Image, Image.Image]:
    """Tolerance flood from the borders; returns (outside mask, distance map)."""
    work = rgb.copy()
    w, h = work.size
    dist = _colour_distance(rgb, BACKGROUND)

    seeds: list[tuple[int, int]] = []
    for x in range(w):
        for y in (0, 1, h - 2, h - 1):
            if dist.getpixel((x, y)) <= EDGE_SEED_TOL:
                seeds.append((x, y))
    for y in range(h):
        for x in (0, 1, w - 2, w - 1):
            if dist.getpixel((x, y)) <= EDGE_SEED_TOL:
                seeds.append((x, y))

    for seed in seeds:
        if work.getpixel(seed) != SENTINEL:
            ImageDraw.floodfill(work, seed, SENTINEL, thresh=FLOOD_TOL)

    r, g, b = work.split()
    hit = _mask_and(
        _mask_and(
            r.point(lambda v: 255 if v == 255 else 0),
            g.point(lambda v: 255 if v == 0 else 0),
        ),
        b.point(lambda v: 255 if v == 255 else 0),
    )
    return hit, dist


def extract_foreground(rgb: Image.Image) -> tuple[Image.Image, Image.Image, Image.Image]:
    """Return (rgba foreground, foreground mask, eye mask).

    Anything the border flood cannot reach is enclosed: the mascot itself, the
    insides of the thin lavender triangles and the eyes.  The eyes happen to be
    painted in the backdrop colour rather than in ink, so they must not become
    alpha holes -- the icon would show two see-through slits that turn white on
    any light surface.  Each enclosed region is therefore classified by looking
    at a ring clear of its own antialiased rim:

    * ring is light lavender (the geometric outline) -> real hole;
    * ring is cream (the face)                       -> eye, stays opaque;
    * anything else                                  -> keep, never punch blind.
    """
    outside, dist = _outside_mask(rgb)

    enclosed = _mask_and(
        dist.point(lambda v: 255 if v <= ISLAND_TOL else 0), _mask_not(outside)
    )
    holes = Image.new("L", rgb.size, 0)
    eyes = Image.new("L", rgb.size, 0)
    for seed in _components(enclosed, MIN_ISLAND_PX):
        comp = _component_mask(enclosed, seed)
        ring = ImageChops.subtract(_dilate(comp, 6), _dilate(comp, 5))
        if ring.getbbox() is None:
            ring = _mask_and(_dilate(comp, 2), _mask_not(comp))
        mean = ImageStat.Stat(rgb, ring).mean
        brightness = sum(mean) / 3.0
        if brightness < 110:
            continue  # purple-ish surround: part of the body, keep
        if sum(abs(mean[i] - CREAM[i]) for i in range(3)) <= EYE_RING_TOL:
            eyes.paste(255, (0, 0), comp)  # eye: opaque foreground
        else:
            holes.paste(255, (0, 0), comp)  # decoration interior: transparent
    holes = _dilate(holes, 1)  # swallow the 1px blend ring around each hole

    fg = _mask_and(_mask_not(outside), _mask_not(holes))

    # Decontaminate only the silhouette that faces the transparent backdrop:
    # inner colour borders (eye rims, cream/purple edges) are already correct
    # and must not be repainted, or the eyes pick up a cream halo.
    rim = _mask_and(fg, _mask_not(_erode(fg, 1)))
    band = _mask_and(rim, _dilate(outside, 1))
    ring_out = _mask_and(_mask_and(_mask_not(fg), _dilate(fg, 1)), outside)
    target = _mask_or(band, ring_out)
    core = _mask_and(_erode(fg, 2), _mask_not(target))
    tinted = _propagate_colour(rgb, core, target, passes=4)

    rgba = rgb.copy()
    rgba.paste(tinted, (0, 0), _mask_and(target, _mask_not(core)))

    alpha = fg.copy()
    alpha.paste(EDGE_ALPHA, (0, 0), ring_out)  # 1px alpha feather
    rgba.putalpha(alpha)
    return rgba, fg, eyes


def face_box(rgb: Image.Image) -> tuple[int, int, int, int]:
    cream = _colour_distance(rgb, CREAM).point(lambda v: 255 if v <= CREAM_TOL else 0)
    box = cream.getbbox()
    if box is None:
        raise SystemExit("face detection failed: no cream pixels found")
    return box


# --------------------------------------------------------------------------- #
# Composition: uniform cover scale + centre crop around the face
# --------------------------------------------------------------------------- #
def render(master: Image.Image, out_px: int, cx: float, cy: float, side: float) -> Image.Image:
    """Cover-scale a square source region onto an out_px square canvas."""
    # Image.transform has no Lanczos kernel, so resample 2x with bicubic and let
    # a Lanczos reduction produce the final antialiasing.
    ss = 2
    n = out_px * ss
    scale = side / n
    left = cx - side / 2.0
    top = cy - side / 2.0
    c = left + (scale - 1.0) * 0.5
    f = top + (scale - 1.0) * 0.5
    big = master.transform(
        (n, n), Image.AFFINE, (scale, 0, c, 0, scale, f), resample=Image.BICUBIC
    )
    return big.resize((out_px, out_px), RESAMPLE)


def clean_alpha(img: Image.Image, background: tuple[int, int, int]) -> Image.Image:
    """Drop sub-1% alpha fringes left by resampling and repaint dead pixels.

    The affine pass fills outside the artwork with transparent black; Lanczos
    then overshoots at that seam and leaves stray bright pixels at alpha 1-2.
    They are invisible but poison colour analysis and any later recompositing.
    Note that pasting an RGB image into RGBA would reset alpha, so the colour
    planes are replaced individually and the alpha plane is kept.
    """
    alpha = img.getchannel("A").point(lambda v: 0 if v <= 12 else v)
    empty = alpha.point(lambda v: 255 if v == 0 else 0)
    planes = list(img.split()[:3])
    for index, channel in enumerate(planes):
        channel.paste(Image.new("L", img.size, background[index]), (0, 0), empty)
    return Image.merge("RGBA", (*planes, alpha))


def flatten(rgba: Image.Image, size: tuple[int, int]) -> Image.Image:
    bg = Image.new("RGBA", size, BACKGROUND + (255,))
    return Image.alpha_composite(bg, rgba).convert("RGB")


def circle_mask(size: int, supersample: int = 4) -> Image.Image:
    big = Image.new("L", (size * supersample, size * supersample), 0)
    ImageDraw.Draw(big).ellipse(
        (0, 0, size * supersample - 1, size * supersample - 1), fill=255
    )
    return big.resize((size, size), RESAMPLE)


def to_canvas_dp(value: float, centre: float, side: float) -> float:
    """Map a source-space coordinate onto the 108dp adaptive canvas."""
    return (value - (centre - side / 2.0)) / side * ADAPTIVE_CANVAS_DP


# --------------------------------------------------------------------------- #
# Output
# --------------------------------------------------------------------------- #
def write_png(img: Image.Image, path: Path) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG", optimize=True)
    return path.stat().st_size


def check(image: Image.Image, expected: tuple[int, int], label: str) -> None:
    assert image.size == expected, f"{label}: size {image.size} != {expected}"



# --------------------------------------------------------------------------- #
# Wireframe field and the drawn monochrome glyph
# --------------------------------------------------------------------------- #
def wireframe_strokes(rgb: Image.Image, fg_mask: Image.Image) -> Image.Image:
    """Source-space mask of the artwork's lavender wireframe.

    A pixel counts as stroke when the lavender is the *nearest* of the four
    palette colours.  A plain distance threshold would also claim the
    antialiased seam where cream meets the backdrop (that blend happens to pass
    through lavender), which would ghost the whole face outline into the
    background.  The solid body is then erased with a small clearance so the
    strokes stop at the silhouette instead of hanging over the mascot.
    """
    dist_line = _colour_distance(rgb, LINE)
    nearest = dist_line
    for colour in (CREAM, BACKGROUND, BODY):
        nearest = ImageChops.darker(nearest, _colour_distance(rgb, colour))
    is_line = ImageChops.difference(dist_line, nearest).point(lambda v: 255 if v == 0 else 0)
    is_line = _mask_and(is_line, dist_line.point(lambda v: 255 if v <= LINE_TOL else 0))

    solid = _largest_component(_dilate(_erode(fg_mask, 8), 8))
    away = _mask_not(_dilate(solid, LINE_BODY_CLEAR))
    strokes = _mask_and(is_line, away)
    return _erode(_dilate(strokes, 1), 1)  # 3px closing: heal JPEG pinholes


def wireframe_sprite(strokes: Image.Image, boost: int = 0) -> Image.Image:
    """The right-hand polyhedron as extracted, cropped and optionally thickened.

    The crystal is drawn overlapping the hair in the source, so its left edges
    are erased by the body clearance and the extraction is an open kite.  That
    is acceptable *behind the mascot*, where the background field only needs
    credible geometry, but it is not acceptable as the standalone monochrome
    glyph -- see `crystal_sprite` for that layer.
    """
    region = strokes.crop(WIRE_REGION)
    box = region.getbbox()
    if box is None:
        raise SystemExit("wireframe extraction found no strokes in WIRE_REGION")
    sprite = region.crop(box)
    if boost:
        sprite = _dilate(sprite, boost)
    return _erode(_dilate(sprite, WIRE_SEAM_PX), WIRE_SEAM_PX)


def crystal_sprite(height_px: int, stroke_px: int) -> Image.Image:
    """One closed wireframe gem as an 'L' mask, *height_px* tall."""
    aspect = 0.88 / 1.00            # glyph box, from CRYSTAL_OUTLINE extents
    width_px = max(1, round(height_px * aspect))
    img = Image.new("L", (width_px + stroke_px * 2, height_px + stroke_px * 2), 0)
    draw = ImageDraw.Draw(img)

    def pt(fx: float, fy: float) -> tuple[float, float]:
        return (stroke_px + fx * width_px, stroke_px + fy * height_px)

    draw.line([pt(x, y) for x, y in CRYSTAL_OUTLINE], fill=255, width=stroke_px, joint="curve")
    for (ax, ay), (bx, by) in CRYSTAL_FACETS:
        draw.line([pt(ax, ay), pt(bx, by)], fill=255, width=max(1, stroke_px * 2 // 3), joint="curve")
    return img


def wireframe_field(sprite: Image.Image, out_px: int,
                    layout: tuple = WIRE_LAYOUT) -> Image.Image:
    """Polyhedra placed by *layout*: (cover, centre fraction, tilt) per copy.

    Background copies overhang the canvas: the launcher mask can cut them
    anywhere in the outer ring and the lines just continue, so no stroke ever
    ends mid-air inside the visible window.  The monochrome layout instead
    keeps its shapes self-contained, because that layer stands alone in the
    icon.  The constants live next to WIRE_REGION so the composition stays
    reviewable as data, not buried in pixel maths.
    """
    field = Image.new("L", (out_px, out_px), 0)
    w, h = sprite.size
    for cover, (fx, fy), angle in layout:
        scale = out_px * cover / max(w, h)
        piece = sprite.resize((max(1, round(w * scale)), max(1, round(h * scale))), RESAMPLE)
        if angle:
            piece = piece.rotate(angle, expand=True, resample=Image.BILINEAR)
        layer = Image.new("L", (out_px, out_px), 0)
        layer.paste(piece, (round(fx * out_px - piece.width / 2.0),
                            round(fy * out_px - piece.height / 2.0)))
        field = _mask_or(field, layer)
    return field


def crystal_field(out_px: int, layout: tuple = MONO_LAYOUT,
                  stroke_dp: float = 1.2) -> Image.Image:
    """The drawn gem, staged by *layout* -- the standalone monochrome plane.

    Stroke width is set in dp against the 108dp canvas, so the glyph stays
    legible from the 48px legacy icon up to the themed-icon render.
    """
    field = Image.new("L", (out_px, out_px), 0)
    stroke_px = max(1, round(stroke_dp * out_px / ADAPTIVE_CANVAS_DP))
    for cover, (fx, fy), angle in layout:
        piece = crystal_sprite(round(out_px * cover), stroke_px)
        if angle:
            piece = piece.rotate(angle, expand=True, resample=Image.BILINEAR)
        layer = Image.new("L", (out_px, out_px), 0)
        layer.paste(piece, (round(fx * out_px - piece.width / 2.0),
                            round(fy * out_px - piece.height / 2.0)))
        field = _mask_or(field, layer)
    return field


def background_layer(sprite: Image.Image, out_px: int) -> Image.Image:
    bg = Image.new("RGB", (out_px, out_px), BACKGROUND)
    field = wireframe_field(sprite, out_px)
    alpha = field.point(lambda v: v * WIRE_ALPHA // 255)
    bg.paste(Image.new("RGB", (out_px, out_px), LINE), (0, 0), alpha)
    return bg


def monochrome_layer(sprite: Image.Image, out_px: int) -> Image.Image:
    """Wireframe only, pure black + alpha -- the system tints the alpha plane."""
    mono = Image.new("RGBA", (out_px, out_px), (0, 0, 0, 0))
    mono.putalpha(wireframe_field(sprite, out_px, MONO_LAYOUT))
    return mono


def composite(bg: Image.Image, fg: Image.Image) -> Image.Image:
    out = bg.convert("RGBA")
    out.alpha_composite(fg)
    return out.convert("RGB")

# --------------------------------------------------------------------------- #
# Fit composition helpers (subject inside the 66dp safe circle)
# --------------------------------------------------------------------------- #
def fit_geometry(rgba: Image.Image) -> tuple[float, float, float, float]:
    """Return (cx, cy, side, r_max) that put the whole subject in the safe circle.

    *cx, cy, r_max* are source-space: the centre of the subject's bounding box
    and the radius of the circle that circumscribes that box.  *side* is the
    source-space square that maps onto the full 108dp canvas, so the circum-
    scribed circle lands exactly on the 66dp safe circle.  Using the bbox
    instead of the pixel-exact outer hull is deliberate: it is the pessimistic
    bound (the corners of the box are empty), so the subject can only end up
    smaller than the limit, never clipped.
    """
    box = rgba.getchannel("A").getbbox()
    if box is None:
        raise SystemExit("foreground extraction produced an empty mask")
    cx = (box[0] + box[2]) / 2.0
    cy = (box[1] + box[3]) / 2.0
    r_max = math.hypot(box[2] - box[0], box[3] - box[1]) / 2.0
    side = r_max * ADAPTIVE_CANVAS_DP / SAFE_RADIUS_DP
    return cx, cy, side, r_max


def _seal_line(strip: Image.Image, skip: int = 8) -> Image.Image:
    """Close transparent runs that the silhouette encloses, along a 1px strip.

    The artwork has two notches that exist only because the frame cuts them: on
    the left edge between the top curl and the shoulder, on the bottom edge
    between the body and the lower ear.  Replicating the edge verbatim would
    extrude them into 30dp slots across the new foreground, which reads as a
    glitch -- so an enclosed run is closed with a straight ramp between the two
    hair pixels that bound it.  Open ends (the top of the head) are left alone:
    that part of the silhouette is meant to be empty.
    """
    width, height = strip.size
    pixels = list(strip.getdata())
    count = len(pixels)
    opaque = [index for index, px in enumerate(pixels) if px[3]]
    if len(opaque) < 2:
        return strip
    sealed = list(pixels)
    for start, end in zip(opaque, opaque[1:]):
        if end <= start + 1:
            continue
        low = pixels[max(0, start - skip)]
        high = pixels[min(count - 1, end + skip)]
        if not low[3]:
            low = pixels[start]
        if not high[3]:
            high = pixels[end]
        span = end - start
        for step in range(1, span):
            t = step / span
            sealed[start + step] = tuple(
                int(round(low[c] * (1.0 - t) + high[c] * t)) for c in range(4)
            )
    out = Image.new("RGBA", strip.size)
    out.putdata(sealed)
    return out


def edge_lines(rgba: Image.Image) -> tuple[Image.Image, Image.Image]:
    """The sealed left column and bottom row -- the lines the extension copies."""
    width, height = rgba.size
    return (_seal_line(rgba.crop((0, 0, 1, height))),
            _seal_line(rgba.crop((0, height - 1, width, height))))


def extend_subject(rgba: Image.Image, cx: float, cy: float, side: float,
                   margin: int = 8) -> tuple[Image.Image, int, int]:
    """Replicate the artwork outward wherever the body runs off the canvas.

    The source artwork is itself a crop: the hair is cut by the left and bottom
    edges of the JPEG, so in fit mode those two straight edges land *inside* the
    launcher mask and read as a square patch of colour.  The art direction is
    "the character is bigger than the frame", so the fix is not to shrink it but
    to continue the edge: every outer row/column is replicated straight out
    until the seam is past the 108dp canvas, where the mask throws it away.

    The copy is literal -- no feathering, no averaging, alpha and colour come
    from the outermost real pixel of the sealed line (see `_seal_line` for the
    two notches that would otherwise turn into slots).  Where the outer line is
    backdrop -- the top edge, the right edge -- the copy stays transparent,
    which is correct: that part of the silhouette is supposed to be empty.

    Returns (image, offset_x, offset_y); the offsets map the old coordinate
    space onto the extended one, so the caller keeps using the original cx/cy
    plus the offset.
    """
    left = cx - side / 2.0 - margin
    top = cy - side / 2.0 - margin
    right = cx + side / 2.0 + margin
    bottom = cy + side / 2.0 + margin
    w, h = rgba.size
    pad_left = max(0, int(math.ceil(-left)))
    pad_top = max(0, int(math.ceil(-top)))
    pad_right = max(0, int(math.ceil(right - w)))
    pad_bottom = max(0, int(math.ceil(bottom - h)))
    if not (pad_left or pad_top or pad_right or pad_bottom):
        return rgba, 0, 0

    out = Image.new("RGBA", (w + pad_left + pad_right, h + pad_top + pad_bottom), (0, 0, 0, 0))
    out.paste(rgba, (pad_left, pad_top))

    # Horizontal strips first (they cover the full original height), then the
    # vertical ones over the *extended* width, so the four corners come out as
    # the replicated corner pixel instead of a hole.
    if pad_left:
        line = _seal_line(rgba.crop((0, 0, 1, h)))
        out.paste(line.resize((pad_left, h), Image.NEAREST), (0, pad_top))
    if pad_right:
        line = _seal_line(rgba.crop((w - 1, 0, w, h)))
        out.paste(line.resize((pad_right, h), Image.NEAREST), (pad_left + w, pad_top))
    if pad_top:
        line = _seal_line(out.crop((0, pad_top, out.width, pad_top + 1)))
        out.paste(line.resize((out.width, pad_top), Image.NEAREST), (0, 0))
    if pad_bottom:
        line = _seal_line(out.crop((0, pad_top + h - 1, out.width, pad_top + h)))
        out.paste(line.resize((out.width, pad_bottom), Image.NEAREST), (0, pad_top + h))
    return out, pad_left, pad_top


def master_icon(bg_sprite: Image.Image, rgba: Image.Image, px: int,
                cx: float, cy: float, side: float) -> Image.Image:
    """Full 108dp composite (background + foreground) at *px* pixels."""
    return composite(background_layer(bg_sprite, px),
                     clean_alpha(render(rgba, px, cx, cy, side), BACKGROUND))


def viewport(master: Image.Image) -> Image.Image:
    """The adaptive icon as a launcher presents it: the central 72/108 window.

    Everything square (legacy icons, store stills) is derived from this one
    crop, so a single render feeds every output and the framing cannot drift
    between sizes.
    """
    size = master.width
    edge = int(round(size * VISIBLE_SQUARE_DP / ADAPTIVE_CANVAS_DP))
    off = (size - edge) // 2
    return master.crop((off, off, off + edge, off + edge))

def _centred_squircle(size: int, diameter: int, exponent: float = 4.0,
                      supersample: int = 4) -> Image.Image:
    """Superellipse |x|^n + |y|^n = 1, the shape Android calls "squircle".

    Drawn row by row (a horizontal span per scanline) instead of per pixel: the
    span end is solved in closed form, which keeps the 4x supersampling cheap
    and the result byte-identical between runs.
    """
    big = size * supersample
    mask = Image.new("L", (big, big), 0)
    draw = ImageDraw.Draw(mask)
    half = diameter / 2.0 * supersample
    centre = size * supersample / 2.0
    n = exponent
    for y in range(big):
        dy = abs((y + 0.5) - centre) / half
        if dy >= 1.0:
            continue
        half_width = half * (1.0 - dy ** n) ** (1.0 / n)
        draw.line([(centre - half_width, y + 0.5), (centre + half_width, y + 0.5)], fill=255)
    return mask.resize((size, size), RESAMPLE)
def _centred_circle(size: int, diameter: int, supersample: int = 4) -> Image.Image:
    big = Image.new("L", (size * supersample, size * supersample), 0)
    offset = (size - diameter) / 2.0 * supersample
    ImageDraw.Draw(big).ellipse(
        (
            offset,
            offset,
            offset + diameter * supersample - 1,
            offset + diameter * supersample - 1,
        ),
        fill=255,
    )
    return big.resize((size, size), RESAMPLE)



def _ring(mask: Image.Image, width: int) -> Image.Image:
    solid = mask.point(lambda v: 255 if v >= 128 else 0)
    return _mask_and(_dilate(solid, width), _mask_not(_erode(solid, width)))


def mask_preview(img: Image.Image, mask: Image.Image, ring_colour: tuple[int, int, int],
                 safe_mask: Image.Image | None = None) -> Image.Image:
    """Show the mask cut without hiding the evidence.

    Everything the launcher would throw away is dimmed to a quarter instead of
    being deleted, the mask edge is stroked, and the 66dp safe circle is drawn
    as a thin second line.  If any opaque mascot pixel touches the dimmed band
    the preview shows it immediately.
    """
    dim = ImageChops.multiply(img, Image.new("RGB", img.size, (64, 64, 64)))
    out = Image.composite(dim, img, _mask_not(mask.convert("L")))
    out.paste(Image.new("RGB", out.size, ring_colour), (0, 0),
              _ring(mask, 2).point(lambda v: v * 220 // 255))
    if safe_mask is not None:
        out.paste(Image.new("RGB", out.size, (150, 135, 168)), (0, 0),
                  _ring(safe_mask, 1).point(lambda v: v * 150 // 255))
    return out


def _font(size: int) -> ImageFont.FreeTypeFont:
    try:
        return ImageFont.load_default(size=size)
    except TypeError:  # Pillow < 10.1
        return ImageFont.load_default()


def _captioned(canvas: Image.Image, xy: tuple[int, int], text: str, size: int = 22,
               fill: tuple[int, int, int] = (214, 208, 226)) -> None:
    ImageDraw.Draw(canvas).text(xy, text, font=_font(size), fill=fill)


def original_ratio_preview(original: Image.Image, art: Image.Image, circle: Image.Image,
                           safe: Image.Image) -> Image.Image:
    """Side-by-side: the artwork at its own 1:1, and the icon as the mask shows it."""
    width, height = 1080, 660
    canvas = Image.new("RGB", (width, height), (24, 24, 24))
    left = original.resize((height, height), RESAMPLE)
    canvas.paste(left, (0, 0))
    right = art.resize((height, height), RESAMPLE)
    canvas.paste(right, (width - height, 0))
    _captioned(canvas, (16, 16), "source artwork 1:1 (docs/assets/branding)")
    _captioned(canvas, (width - height + 16, 16), "launcher mask (72dp circle)")
    return canvas

# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #
def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        type=Path,
        default=None,
        help="source artwork (default: docs/assets/branding/source-mascot.jpg)",
    )
    parser.add_argument(
        "--import-source",
        type=Path,
        default=None,
        help="copy this artwork into docs/assets/branding/source-mascot.jpg first",
    )
    parser.add_argument(
        "--no-crop",
        action="store_true",
        help="fit the whole mascot inside the 66dp safe circle instead of the "
             "default 1:1 artwork mapping (wireframe background + previews)",
    )
    parser.add_argument(
        "--cover",
        action="store_true",
        help="session-24 face-cover composition, kept only to reproduce that "
             "round's outputs",
    )
    args = parser.parse_args()

    if args.import_source is not None:
        SOURCE_COPY.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(args.import_source, SOURCE_COPY)
        print(f"imported source -> {SOURCE_COPY.relative_to(REPO_ROOT)}")

    src = args.source or SOURCE_COPY
    if not src.is_file():
        raise SystemExit(f"source artwork missing: {src}")

    rgb = Image.open(src).convert("RGB")
    if rgb.width != rgb.height:
        raise SystemExit(f"source artwork must be square, got {rgb.size}")

    rgba, fg_mask, eyes = extract_foreground(rgb)

    if args.no_crop:
        build_fit(rgb, rgba, fg_mask)
    elif args.cover:
        build_cover(rgb, rgba, fg_mask, eyes)
    else:
        build_original(rgb, rgba, fg_mask, eyes)



# --------------------------------------------------------------------------- #
# Session 25 final direction: the artwork itself is the icon
# --------------------------------------------------------------------------- #
def build_original(rgb: Image.Image, rgba: Image.Image, fg_mask: Image.Image,
                   eyes: Image.Image) -> None:
    """The source artwork maps 1:1 onto the adaptive canvas. No composition.

    The user's direction is explicit: the original image *is* the icon, so the
    pipeline makes no framing decisions at all -- the full square artwork
    becomes the adaptive foreground, the background is its own flat backdrop
    colour, and the monochrome plane is the mascot's own silhouette extracted
    from this same artwork (nothing is redrawn).  Whatever the launcher mask
    crops is the system's standard behaviour, identical to showing the
    original image in a round frame.
    """
    side = float(rgb.width)                    # 1:1: canvas = artwork
    cx = cy = rgb.width / 2.0
    print(f"original: artwork {rgb.width}x{rgb.height} px -> 108dp canvas, 1:1")

    # Monochrome: the mascot's own silhouette, sized so the whole figure sits
    # inside the 66dp safe circle of the standalone themed icon.  Same mask
    # pipeline as the cover mode (open -> largest component -> eye slits cut).
    opened = _dilate(_erode(fg_mask, MONO_OPEN_PX), MONO_OPEN_PX)
    body = _mask_and(_largest_component(opened), _mask_not(_dilate(eyes, 1)))
    mono_src = Image.new("RGBA", rgb.size, (0, 0, 0, 0))
    mono_src.putalpha(body)
    mcx, mcy, mside, _ = fit_geometry(rgba)
    print(
        "mono: silhouette bbox fit -> centre (%.0f, %.0f) px, side %.0f px"
        % (mcx, mcy, mside)
    )

    master = composite(
        background_layer(Image.new("L", (1, 1), 0), MASTER_PX),
        render(rgb.convert("RGBA"), MASTER_PX, cx, cy, side),
    )
    window = viewport(master)
    print(f"render: master {MASTER_PX}px, viewport {window.width}px")

    for name, density in DENSITIES:
        out_px = int(round(ADAPTIVE_CANVAS_DP * density))
        directory = RES_DIR / f"mipmap-{name}"

        bg = background_layer(Image.new("L", (1, 1), 0), out_px)
        check(bg, (out_px, out_px), f"{name} background")
        size_bg = write_png(bg, directory / "ic_launcher_background.png")

        fg = render(rgb.convert("RGBA"), out_px, cx, cy, side)
        lo = fg.getchannel("A").getextrema()[0]
        if lo < 255:
            raise SystemExit(f"original foreground must be fully opaque, min alpha {lo}")
        check(fg, (out_px, out_px), f"{name} foreground")
        size_fg = write_png(fg, directory / "ic_launcher_foreground.png")

        mono = clean_alpha(render(mono_src, out_px, mcx, mcy, mside), (0, 0, 0))
        check(mono, (out_px, out_px), f"{name} monochrome")
        size_mono = write_png(mono, directory / "ic_launcher_monochrome.png")

        legacy_px = int(round(LEGACY_ICON_DP * density))
        square = window.resize((legacy_px, legacy_px), RESAMPLE)
        check(square, (legacy_px, legacy_px), f"{name} legacy")
        size_sq = write_png(square, directory / "ic_launcher.png")

        round_img = Image.new("RGBA", (legacy_px, legacy_px), (0, 0, 0, 0))
        round_img.paste(square, (0, 0), circle_mask(legacy_px))
        size_round = write_png(round_img, directory / "ic_launcher_round.png")

        print(
            f"{name:8s} bg {out_px:3d}px ({size_bg:6d}B)  fg ({size_fg:6d}B)  "
            f"mono ({size_mono:6d}B)  legacy {legacy_px:3d}px ({size_sq:6d}B)  "
            f"round ({size_round:6d}B)"
        )

    anydpi = RES_DIR / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (anydpi / name).write_text(ADAPTIVE_XML_BITMAP_BG, encoding="utf-8", newline="\n")

    original = rgb.copy()
    size_orig = write_png(original, BRANDING_DIR / "mascot-original.png")
    size_1024_orig = write_png(
        original.resize((1024, 1024), RESAMPLE), BRANDING_DIR / "mascot-original-1024.png"
    )
    size_512_orig = write_png(
        original.resize((512, 512), RESAMPLE), BRANDING_DIR / "mascot-original-512.png"
    )
    size_512 = write_png(window.resize((512, 512), RESAMPLE), BRANDING_DIR / "app-icon-512.png")
    size_1024 = write_png(
        window.resize((1024, 1024), RESAMPLE), BRANDING_DIR / "app-icon-1024.png"
    )

    preview_px = 432
    art = master.resize((preview_px, preview_px), RESAMPLE)
    circle = _centred_circle(
        preview_px, int(round(preview_px * VISIBLE_SQUARE_DP / ADAPTIVE_CANVAS_DP))
    )
    safe = _centred_circle(
        preview_px, int(round(preview_px * SAFE_CIRCLE_DP / ADAPTIVE_CANVAS_DP))
    )
    squircle = _centred_squircle(
        preview_px, int(round(preview_px * VISIBLE_SQUARE_DP / ADAPTIVE_CANVAS_DP))
    )
    size_circle = write_png(
        mask_preview(art, circle, (238, 234, 246), safe),
        BRANDING_DIR / "preview-circle-432.png",
    )
    size_squircle = write_png(
        mask_preview(art, squircle, (238, 234, 246), safe),
        BRANDING_DIR / "preview-squircle-432.png",
    )
    mono_plane = clean_alpha(render(mono_src, preview_px, mcx, mcy, mside), (0, 0, 0))
    tint = composite(
        Image.new("RGB", (preview_px, preview_px), BACKGROUND),
        Image.merge("RGBA", (
            Image.new("L", (preview_px, preview_px), 237),
            Image.new("L", (preview_px, preview_px), 230),
            Image.new("L", (preview_px, preview_px), 245),
            mono_plane.getchannel("A"),
        )),
    )
    size_mono_prev = write_png(
        mask_preview(tint, circle, (238, 234, 246), safe),
        BRANDING_DIR / "preview-monochrome-432.png",
    )
    size_compare = write_png(
        original_ratio_preview(original, art, circle, safe),
        BRANDING_DIR / "preview-original-ratio.png",
    )
    print(f"mascot-original 1920 {size_orig}B  /1024 {size_1024_orig}B  /512 {size_512_orig}B")
    print(f"store 512 {size_512}B  social 1024 {size_1024}B")
    print(
        f"previews: circle {size_circle}B  squircle {size_squircle}B  "
        f"mono {size_mono_prev}B  compare {size_compare}B"
    )
    print("verify: ok (original: foreground opaque, background flat, geometry 1:1)")

    if not SOURCE_COPY.is_file():
        raise SystemExit("source copy missing from docs/assets/branding")



# --------------------------------------------------------------------------- #
# Session 25 fit composition: subject inside the safe circle
# --------------------------------------------------------------------------- #
def build_fit(rgb: Image.Image, rgba: Image.Image, fg_mask: Image.Image) -> None:
    cx, cy, side, r_max = fit_geometry(rgba)
    dp_per_px = ADAPTIVE_CANVAS_DP / side
    box = rgba.getchannel("A").getbbox()
    subject_w_dp = (box[2] - box[0]) * dp_per_px
    subject_h_dp = (box[3] - box[1]) * dp_per_px
    art_dp = rgb.width * dp_per_px

    print(f"fit: subject bbox {box} px, circumscribed r {r_max:.1f} px")
    print(
        f"fit: 108dp canvas covers {side:.0f} source px ({dp_per_px:.5f} dp/px); "
        f"artwork {art_dp:.1f}dp, subject {subject_w_dp:.1f}x{subject_h_dp:.1f}dp"
    )
    print(
        "fit: subject radius %.2f dp <= safe radius %.2f dp (%.1f%% of the 66dp circle)"
        % (r_max * dp_per_px, SAFE_RADIUS_DP, 100.0 * r_max * dp_per_px / SAFE_RADIUS_DP)
    )

    strokes = wireframe_strokes(rgb, fg_mask)
    sprite = wireframe_sprite(strokes)
    print(
        "wireframe: %d stroke px -> sprite %dx%d px"
        % (strokes.histogram()[255], sprite.width, sprite.height)
    )

    # The source artwork is cut by its own left/bottom edges, so those two
    # straight edges would sit inside the mask.  Replicate the outer rows and
    # columns past the canvas instead.  Geometry stays derived from the original
    # bbox above -- the character keeps its size, it only gains a continued edge.
    subject, off_x, off_y = extend_subject(rgba, cx, cy, side)
    rcx, rcy = cx + off_x, cy + off_y
    to_x = lambda v: (v - (cx - side / 2.0)) * dp_per_px
    to_y = lambda v: (v - (cy - side / 2.0)) * dp_per_px
    print(
        "extend: %dx%d -> %dx%d px (offset %+d,%+d); the old cut edges sat at "
        "x %.1fdp / y %.1fdp, the replicated edge now sits on the canvas border "
        "(0/108dp), clear of the 18..90dp mask span"
        % (rgba.width, rgba.height, subject.width, subject.height, off_x, off_y,
           to_x(0), to_y(rgb.height))
    )

    # One high-resolution render feeds every square derivative below, so the
    # framing cannot drift between the legacy icons and the store stills.
    master = master_icon(sprite, subject, MASTER_PX, rcx, rcy, side)
    window = viewport(master)  # the 72dp area a launcher actually shows
    print(
        "render: master %dpx (%.1f px/dp), viewport %dpx"
        % (MASTER_PX, MASTER_PX / ADAPTIVE_CANVAS_DP, window.width)
    )

    for name, density in DENSITIES:
        out_px = int(round(ADAPTIVE_CANVAS_DP * density))
        directory = RES_DIR / f"mipmap-{name}"

        bg = background_layer(sprite, out_px)
        check(bg, (out_px, out_px), f"{name} background")
        size_bg = write_png(bg, directory / "ic_launcher_background.png")

        fg = clean_alpha(render(subject, out_px, rcx, rcy, side), BACKGROUND)
        check(fg, (out_px, out_px), f"{name} foreground")
        size_fg = write_png(fg, directory / "ic_launcher_foreground.png")

        mono = monochrome_layer(sprite, out_px)
        check(mono, (out_px, out_px), f"{name} monochrome")
        size_mono = write_png(mono, directory / "ic_launcher_monochrome.png")

        # --- legacy 48dp icons (opaque square + circular variant) ---------- #
        # API < 26 has no adaptive icon, so the launcher shows the whole 48dp
        # square: the 108dp composition reduced to its visible 72dp window.
        legacy_px = int(round(LEGACY_ICON_DP * density))
        square = window.resize((legacy_px, legacy_px), RESAMPLE)
        check(square, (legacy_px, legacy_px), f"{name} legacy")
        size_sq = write_png(square, directory / "ic_launcher.png")

        round_img = Image.new("RGBA", (legacy_px, legacy_px), (0, 0, 0, 0))
        round_img.paste(square, (0, 0), circle_mask(legacy_px))
        size_round = write_png(round_img, directory / "ic_launcher_round.png")

        print(
            f"{name:8s} bg {out_px:3d}px ({size_bg:6d}B)  fg ({size_fg:6d}B)  "
            f"mono ({size_mono:6d}B)  legacy {legacy_px:3d}px ({size_sq:6d}B)  "
            f"round ({size_round:6d}B)"
        )

    # --- adaptive icon descriptors ----------------------------------------- #
    anydpi = RES_DIR / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (anydpi / name).write_text(ADAPTIVE_XML_BITMAP_BG, encoding="utf-8", newline="\n")

    # --- non-launcher deliveries: the artwork, 1:1, untouched --------------- #
    # The source JPEG is decoded once and re-encoded losslessly; no scaling, no
    # crop, no colour work.  These are what a web/print/banner use wants.
    original = rgb.copy()
    size_orig = write_png(original, BRANDING_DIR / "mascot-original.png")
    size_1024_orig = write_png(
        original.resize((1024, 1024), RESAMPLE), BRANDING_DIR / "mascot-original-1024.png"
    )
    size_512_orig = write_png(
        original.resize((512, 512), RESAMPLE), BRANDING_DIR / "mascot-original-512.png"
    )

    # --- launcher stills (the 72dp viewport a launcher shows) --------------- #
    size_512 = write_png(window.resize((512, 512), RESAMPLE), BRANDING_DIR / "app-icon-512.png")
    size_1024 = write_png(
        window.resize((1024, 1024), RESAMPLE), BRANDING_DIR / "app-icon-1024.png"
    )

    # --- mask acceptance previews ------------------------------------------ #
    preview_px = 432  # xxxhdpi canvas, so a preview pixel is exactly 0.25dp
    art = master.resize((preview_px, preview_px), RESAMPLE)
    circle = _centred_circle(
        preview_px, int(round(preview_px * VISIBLE_SQUARE_DP / ADAPTIVE_CANVAS_DP))
    )
    safe = _centred_circle(
        preview_px, int(round(preview_px * SAFE_CIRCLE_DP / ADAPTIVE_CANVAS_DP))
    )
    squircle = _centred_squircle(
        preview_px, int(round(preview_px * VISIBLE_SQUARE_DP / ADAPTIVE_CANVAS_DP))
    )

    size_circle = write_png(
        mask_preview(art, circle, (238, 234, 246), safe),
        BRANDING_DIR / "preview-circle-432.png",
    )
    size_squircle = write_png(
        mask_preview(art, squircle, (238, 234, 246), safe),
        BRANDING_DIR / "preview-squircle-432.png",
    )

    mono_plane = monochrome_layer(sprite, preview_px)
    tint = composite(
        Image.new("RGB", (preview_px, preview_px), BACKGROUND),
        Image.merge("RGBA", (
            Image.new("L", (preview_px, preview_px), 237),
            Image.new("L", (preview_px, preview_px), 230),
            Image.new("L", (preview_px, preview_px), 245),
            mono_plane.getchannel("A"),
        )),
    )
    size_mono_prev = write_png(
        mask_preview(tint, circle, (238, 234, 246), safe),
        BRANDING_DIR / "preview-monochrome-432.png",
    )

    size_compare = write_png(
        original_ratio_preview(original, art, circle, safe),
        BRANDING_DIR / "preview-original-ratio.png",
    )

    print(
        f"mascot-original 1920 {size_orig}B  /1024 {size_1024_orig}B  /512 {size_512_orig}B"
    )
    print(f"store 512 {size_512}B  social 1024 {size_1024}B")
    print(
        f"previews: circle {size_circle}B  squircle {size_squircle}B  "
        f"mono {size_mono_prev}B  compare {size_compare}B"
    )

    if not SOURCE_COPY.is_file():
        raise SystemExit("source copy missing from docs/assets/branding")

    verify_fit(rgb, rgba, subject, (off_x, off_y), side, cx, cy)



# --------------------------------------------------------------------------- #
# Session 24 composition: cover crop around the face (kept for reference)
# --------------------------------------------------------------------------- #
def build_cover(rgb: Image.Image, rgba: Image.Image, fg_mask: Image.Image,
                eyes: Image.Image) -> None:
    """Session 24's cover composition, byte-for-byte the old main() body."""
    fx0, fy0, fx1, fy1 = face
    cx, cy = (fx0 + fx1) / 2.0, (fy0 + fy1) / 2.0
    side = (fy1 - fy0) / FACE_HEIGHT_RATIO

    # Monochrome: keep only the mascot body.  The opening removes specks and
    # thin fringes; the geometric hairlines are fused to the head and survive
    # as very thin lines (invisible at 48dp, ~0.3dp wide).  The eye slits stay
    # cut out so the face is still recognisable once the system tints it.
    opened = _dilate(_erode(fg_mask, MONO_OPEN_PX), MONO_OPEN_PX)
    body = _mask_and(_largest_component(opened), _mask_not(_dilate(eyes, 1)))
    mono = Image.new("RGBA", rgb.size, (0, 0, 0, 0))
    mono.putalpha(body)

    face_dp = (
        to_canvas_dp(fx0, cx, side),
        to_canvas_dp(fy0, cy, side),
        to_canvas_dp(fx1, cx, side),
        to_canvas_dp(fy1, cy, side),
    )
    print(
        "face bbox (source px):",
        face,
        "-> crop side %.0f px" % side,
        "| on 108dp canvas: x %.1f..%.1f dp, y %.1f..%.1f dp"
        % (face_dp[0], face_dp[2], face_dp[1], face_dp[3]),
    )
    centre_dp = (to_canvas_dp(cx, cx, side), to_canvas_dp(cy, cy, side))
    print("face centre on canvas: %.1f, %.1f dp" % centre_dp)

    # Square deliveries (legacy icons, store stills) show the 72dp adaptive
    # window instead of the full 108dp canvas, which is how a launcher presents
    # an adaptive icon anyway.  The window must stay inside the source artwork:
    # if it ran past the edge we would have to invent pixels there.
    legacy_side = side * LEGACY_VIEW_DP / ADAPTIVE_CANVAS_DP
    w, h = rgb.size
    assert (
        cx - legacy_side / 2 >= 0
        and cx + legacy_side / 2 <= w
        and cy - legacy_side / 2 >= 0
        and cy + legacy_side / 2 <= h
    ), "square crop exceeds the source artwork; lower LEGACY_VIEW_DP"

    lo = (ADAPTIVE_CANVAS_DP - VISIBLE_SQUARE_DP) / 2.0
    hi = ADAPTIVE_CANVAS_DP - lo
    assert face_dp[0] > lo and face_dp[2] < hi, f"face wider than the visible square: {face_dp}"
    assert face_dp[1] > lo and face_dp[3] < hi, f"face taller than the visible square: {face_dp}"

    # Ratio of the face box that survives a 66dp circular mask (reported, not asserted hard).
    radius = SAFE_CIRCLE_DP / 2.0
    inside = total = 0
    steps = 64
    for i in range(steps):
        for j in range(steps):
            x = face_dp[0] + (face_dp[2] - face_dp[0]) * (i + 0.5) / steps - ADAPTIVE_CANVAS_DP / 2
            y = face_dp[1] + (face_dp[3] - face_dp[1]) * (j + 0.5) / steps - ADAPTIVE_CANVAS_DP / 2
            total += 1
            inside += (x * x + y * y) <= radius * radius
    print(f"face area inside the 66dp safe circle: {100.0 * inside / total:.1f}%")

    # --- adaptive foreground / monochrome ---------------------------------- #
    for name, scale in DENSITIES:
        out_px = int(round(ADAPTIVE_CANVAS_DP * scale))
        directory = RES_DIR / f"mipmap-{name}"

        fg = clean_alpha(render(rgba, out_px, cx, cy, side), BACKGROUND)
        check(fg, (out_px, out_px), f"{name} foreground")
        size_fg = write_png(fg, directory / "ic_launcher_foreground.png")

        mono_px = clean_alpha(render(mono, out_px, cx, cy, side), (0, 0, 0))
        check(mono_px, (out_px, out_px), f"{name} monochrome")
        size_mono = write_png(mono_px, directory / "ic_launcher_monochrome.png")

        # --- legacy 48dp icons (opaque square + circular variant) ---------- #
        legacy_px = int(round(LEGACY_ICON_DP * scale))
        art = clean_alpha(render(rgba, legacy_px, cx, cy, legacy_side), BACKGROUND)
        square = flatten(art, (legacy_px, legacy_px))
        check(square, (legacy_px, legacy_px), f"{name} legacy")
        size_sq = write_png(square, directory / "ic_launcher.png")

        round_img = Image.new("RGBA", (legacy_px, legacy_px), (0, 0, 0, 0))
        round_img.paste(square, (0, 0), circle_mask(legacy_px))
        size_round = write_png(round_img, directory / "ic_launcher_round.png")

        print(
            f"{name:8s} fg {out_px:3d}px ({size_fg:6d}B)  mono {size_mono:6d}B  "
            f"legacy {legacy_px:3d}px ({size_sq:6d}B)  round {size_round:6d}B"
        )

    # --- adaptive icon descriptors ----------------------------------------- #
    anydpi = RES_DIR / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (anydpi / name).write_text(ADAPTIVE_XML, encoding="utf-8", newline="\n")

    # --- brand stills ------------------------------------------------------- #
    store = clean_alpha(render(rgba, 512, cx, cy, legacy_side), BACKGROUND)
    store_img = flatten(store, (512, 512))
    size_512 = write_png(store_img, BRANDING_DIR / "app-icon-512.png")

    # The source artwork is itself cropped on the left/bottom, so a padded
    # 108dp framing would expose a straight purple edge inside the canvas.
    # The social still therefore uses the same full-bleed 74dp window.
    social = clean_alpha(render(rgba, 1024, cx, cy, legacy_side), BACKGROUND)
    social_img = flatten(social, (1024, 1024))
    size_1024 = write_png(social_img, BRANDING_DIR / "app-icon-1024.png")

    # Mask rehearsal: the xxxhdpi foreground cut by the 72dp circular mask.
    preview = Image.new("RGBA", (432, 432), BACKGROUND + (255,))
    fg432 = clean_alpha(render(rgba, 432, cx, cy, side), BACKGROUND)
    diameter = int(round(432 * VISIBLE_SQUARE_DP / ADAPTIVE_CANVAS_DP))
    masked = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    masked.paste(fg432, (0, 0), _centred_circle(432, diameter))
    preview = Image.alpha_composite(preview, masked).convert("RGB")
    size_prev = write_png(preview, BRANDING_DIR / "preview-circle-432.png")

    print(f"store 512 {size_512}B  social 1024 {size_1024}B  preview {size_prev}B")

    if not SOURCE_COPY.is_file():
        raise SystemExit("source copy missing from docs/assets/branding")

    verify(rgba, mono, store_img)


def verify(rgba: Image.Image, mono: Image.Image, store: Image.Image) -> None:
    """Assert the invariants that matter for the launcher."""
    for name, scale in DENSITIES:
        out_px = int(round(ADAPTIVE_CANVAS_DP * scale))
        fg = Image.open(RES_DIR / f"mipmap-{name}" / "ic_launcher_foreground.png")
        assert fg.size == (out_px, out_px), f"{name} foreground size {fg.size}"
        assert fg.mode == "RGBA", f"{name} foreground mode {fg.mode}"
        alpha = fg.getchannel("A")
        corners = [
            alpha.getpixel((0, 0)),
            alpha.getpixel((out_px - 1, 0)),
            alpha.getpixel((0, out_px - 1)),
            alpha.getpixel((out_px - 1, out_px - 1)),
        ]
        assert corners == [0, 0, 0, 0], f"{name} foreground corners not transparent: {corners}"

        mo = Image.open(RES_DIR / f"mipmap-{name}" / "ic_launcher_monochrome.png")
        assert mo.size == (out_px, out_px), f"{name} monochrome size {mo.size}"
        assert mo.mode == "RGBA"
        mr, mg, mb, ma = mo.split()
        non_black = ImageChops.lighter(ImageChops.lighter(mr, mg), mb)
        assert _mask_and(non_black, ma).getbbox() is None, (
            f"{name} monochrome carries a non-black pixel"
        )

        legacy_px = int(round(LEGACY_ICON_DP * scale))
        sq = Image.open(RES_DIR / f"mipmap-{name}" / "ic_launcher.png")
        assert sq.size == (legacy_px, legacy_px) and sq.mode == "RGB"
        rd = Image.open(RES_DIR / f"mipmap-{name}" / "ic_launcher_round.png")
        assert rd.size == (legacy_px, legacy_px) and rd.mode == "RGBA"

    assert store.size == (512, 512) and store.mode == "RGB"
    alpha_512 = Image.open(BRANDING_DIR / "app-icon-512.png")
    assert alpha_512.mode == "RGB", "store icon must be opaque (no alpha channel)"
    assert alpha_512.size == (512, 512)
    assert Image.open(BRANDING_DIR / "app-icon-1024.png").size == (1024, 1024)
    preview = Image.open(BRANDING_DIR / "preview-circle-432.png")
    assert preview.size == (432, 432) and preview.mode == "RGB"

    # End-to-end geometry: the cream face must land on the canvas centre.
    fg432 = Image.open(RES_DIR / "mipmap-xxxhdpi" / "ic_launcher_foreground.png")
    cream = _mask_and(
        _colour_distance(fg432.convert("RGB"), CREAM).point(
            lambda v: 255 if v <= CREAM_TOL else 0
        ),
        fg432.getchannel("A"),
    )
    box = cream.getbbox()
    assert box is not None, "no cream face in the rendered foreground"
    centre = ((box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0)
    assert abs(centre[0] - 216) <= 2 and abs(centre[1] - 216) <= 2, (
        f"face is off-centre in the rendered icon: {centre}"
    )

    # The source artwork is cropped on the left and bottom edge, so only the
    # rendered assets (which carry a transparent margin) can be corner-checked;
    # the full-resolution master is allowed to reach the border.
    assert mono.getchannel("A").getpixel((0, 0)) == 0

    print("verify: ok")



def verify_fit(rgb: Image.Image, rgba: Image.Image, subject: Image.Image,
               offsets: tuple[int, int], side: float, cx: float, cy: float) -> None:
    """Prove the two acceptance contracts on the files that were actually written.

    (a) Character content: the subject as authored (the un-extended artwork)
        still has every opaque pixel inside the 66dp safe circle.  Measured on a
        fresh render of *rgba*, not on the shipped bitmap, because the shipped
        one is deliberately extended and no longer bounded by that circle.
        Also asserts the extension only ever adds: no pixel that was opaque
        before may have disappeared from the shipped foreground.

    (b) No visible straight edge: inside the mask circle, in the bands that the
        extension now covers (left of the artwork's left edge, below its bottom
        edge), every pixel that was replicated from hair must be fully opaque
        and must carry the colour of the edge it was copied from.  A seam, a
        transparent slit or a blended ring shows up here immediately.
    """
    off_x, off_y = offsets
    left_line, bottom_line = edge_lines(rgba)

    def opaque_runs(strip: Image.Image) -> list[tuple[int, int]]:
        marks = [px[3] for px in strip.getdata()]
        runs, start = [], None
        for index, value in enumerate(marks):
            if value and start is None:
                start = index
            elif not value and start is not None:
                runs.append((start, index - 1))
                start = None
        if start is not None:
            runs.append((start, len(marks) - 1))
        return runs

    left_runs, bottom_runs = opaque_runs(left_line), opaque_runs(bottom_line)
    print(
        "  sealed edge runs: left column %s, bottom row %s (each must be one run: "
        "the notches the frame cut are closed)" % (left_runs, bottom_runs)
    )
    if len(left_runs) != 1 or len(bottom_runs) != 1:
        raise SystemExit("sealed edge lines must each be a single opaque run")

    rcx, rcy = cx + off_x, cy + off_y
    dp_per_px = ADAPTIVE_CANVAS_DP / side
    art_left_dp = (side / 2.0 - cx) * dp_per_px            # original x=0 on canvas
    art_bottom_dp = (side / 2.0 + (rgba.height - cy)) * dp_per_px
    art_right_dp = (side / 2.0 + (rgba.width - cx)) * dp_per_px
    art_top_dp = (side / 2.0 - cy) * dp_per_px
    left_pixels = list(left_line.getdata())
    bottom_pixels = list(bottom_line.getdata())
    art_w, art_h = rgba.size

    failures = 0
    for name, density in DENSITIES:
        out_px = int(round(ADAPTIVE_CANVAS_DP * density))
        scale = out_px / ADAPTIVE_CANVAS_DP
        shipped = Image.open(RES_DIR / f"mipmap-{name}" / "ic_launcher_foreground.png")
        content = clean_alpha(render(rgba, out_px, cx, cy, side), BACKGROUND)
        extended = clean_alpha(render(subject, out_px, rcx, rcy, side), BACKGROUND)

        alpha = content.getchannel("A")
        bbox = alpha.getbbox()
        centre = out_px / 2.0
        limit = SAFE_RADIUS_DP * scale
        outside = 0
        max_radius = 0.0
        for y in range(out_px):
            for x in range(out_px):
                if alpha.getpixel((x, y)):
                    radius = math.hypot(x + 0.5 - centre, y + 0.5 - centre) / scale
                    max_radius = max(max_radius, radius)
                    if radius > SAFE_RADIUS_DP:
                        outside += 1
        lost = sum(
            1 for y in range(out_px) for x in range(out_px)
            if alpha.getpixel((x, y)) and not extended.getchannel("A").getpixel((x, y))
        )
        drop = max(
            (content.getchannel("A").getpixel((x, y))
             - shipped.getchannel("A").getpixel((x, y))
             for y in range(out_px) for x in range(out_px)),
            default=0,
        )
        shipped_bbox = shipped.getchannel("A").getbbox()
        print(
            "  %-8s canvas %3dpx  content bbox %s  max radius %5.2fdp (limit %.2fdp)  "
            "outside %d  lost %d  max alpha drop %d  shipped bbox %s"
            % (name, out_px, bbox, max_radius, SAFE_RADIUS_DP, outside, lost,
               max(0, drop), shipped_bbox)
        )
        if outside or lost:
            failures += 1

        # (b) the extension bands, sampled inside the 72dp visible circle
        radius_px = (VISIBLE_SQUARE_DP / 2.0) * scale
        left_bound = art_left_dp * scale
        bottom_bound = art_bottom_dp * scale
        right_bound = art_right_dp * scale
        top_bound = art_top_dp * scale
        band = []
        for y in range(out_px):
            for x in range(out_px):
                if math.hypot(x + 0.5 - centre, y + 0.5 - centre) > radius_px:
                    continue
                in_left = x < left_bound
                in_bottom = y > bottom_bound
                if not (in_left or in_bottom):
                    continue
                fx = x / scale - (side / 2.0 - rcx)
                fy = y / scale - (side / 2.0 - rcy)
                if in_left and not in_bottom:
                    source = left_pixels[min(art_h - 1, max(0, int(fy)))]
                elif in_bottom and not in_left:
                    source = bottom_pixels[min(art_w - 1, max(0, int(fx)))]
                else:
                    source = tuple(
                        max(a, b) for a, b in zip(
                            left_pixels[min(art_h - 1, max(0, int(fy)))],
                            bottom_pixels[min(art_w - 1, max(0, int(fx)))],
                        )
                    )
                band.append((shipped.getpixel((x, y)), source))
        off_colour = sum(
            1 for pixel, source in band
            if sum(abs(a - b) for a, b in zip(pixel[:3], source[:3])) > EDGE_COLOUR_TOL
        )
        alphas = [pixel[3] for pixel, _ in band]
        worst = max(
            (sum(abs(a - b) for a, b in zip(pixel[:3], source[:3])) for pixel, source in band),
            default=0,
        )
        ratio = 100.0 * off_colour / len(band) if band else 0.0
        print(
            "           band samples %4d  off-colour %4d (%.2f%%, limit %.1f%%)  "
            "mean alpha %.2f  min %d  holes %d  worst dE %d"
            % (len(band), off_colour, ratio, 100.0 * EDGE_COLOUR_RATIO,
               sum(alphas) / len(alphas) if alphas else 0.0,
               min(alphas) if alphas else 0,
               sum(1 for a in alphas if a == 0), worst)
        )
        if ratio > 100.0 * EDGE_COLOUR_RATIO or (alphas and min(alphas) < EDGE_ALPHA_FLOOR):
            failures += 1

    print(
        "  margin to the circle mask (36dp radius): %.2f dp"
        % ((VISIBLE_SQUARE_DP - 66.0) / 2.0 + (SAFE_RADIUS_DP - max_radius))
    )
    if failures:
        raise SystemExit(f"verify_fit: {failures} density bucket(s) failed")
    print("verify: ok (--no-crop)")


if __name__ == "__main__":
    main()
