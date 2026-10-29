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
4. Composition is always "cover": uniform scale + centre crop around the face.
   No non-uniform resize anywhere.

Usage
-----
    python tools/branding/make_app_icons.py
    python tools/branding/make_app_icons.py --import-source <artwork>.jpg
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageStat

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

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""

SENTINEL = (255, 0, 255)


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
    face = face_box(rgb)
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


if __name__ == "__main__":
    main()
