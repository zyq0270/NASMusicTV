#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
scripts/shan_shui_upstream_ref.py
---------------------------------------------------------------------------
Render the upstream shan-shui-inf scene (captured as structured JSON by
scripts/shan_shui_upstream_ref.js) into a PNG reference image, so that the
E50 「千里江山」 port can be diffed against the original JavaScript.

Rendering model
---------------------------------------------------------------------------
Upstream renders the whole scene into ONE <svg> element that carries
`style='mix-blend-mode:multiply'` (index.html :4023-4037) and that svg is
laid over the paper texture (index.html :4336-4360).

Therefore the faithful pipeline is:

    1. composite every shape into one RGBA group with NORMAL source-over
       (this is what the browser does *inside* the svg),
    2. multiply that group with the paper backdrop (mix-blend-mode:multiply).

    final = paper * (A*Cs + (1-A))          # A = group alpha, Cs = group rgb

We produce TWO images:

    output/e50_upstream_ref.png              -> the faithful multiply version
    output/e50_upstream_ref_normalblend.png  -> naive source-over (what a port
                                               that forgets mix-blend-mode gets)

The second one is deliberately included: the difference between the two is
exactly what makes upstream's pure-"white" fills (mountain WHITE BG, rock
WHITE BG, ...) invisible upstream and VISIBLE in a naive port.

Geometry
---------------------------------------------------------------------------
Upstream svg: width=MEM.windx=3000, height=MEM.windy=800, viewBox
"0 0 2626.97 700.53" (MEM.windx/zoom, MEM.windy/zoom, zoom=1.142).
=> one world unit == 1.142 px, and the visible world region is
   x in [0, 2626.97], y in [0, 700.53].

Usage:
    python scripts/shan_shui_upstream_ref.py [--json <file>] [--ss <n>]
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw
import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_JSON = REPO_ROOT / "output" / "shan_shui_upstream_ref.json"
OUT_MULTIPLY = REPO_ROOT / "output" / "e50_upstream_ref.png"
OUT_NORMAL = REPO_ROOT / "output" / "e50_upstream_ref_normalblend.png"

CANVAS_W = 3000
CANVAS_H = 800
ZOOM = 1.142
CWID = 512  # upstream chunk width -> chunkrender() window filter

# upstream paper colour (index.html :4336-4360 builds a noisy texture around
# rgb(240,228,204); the flat average requested for the reference is below)
PAPER = (245, 232, 208)

# upstream chunkrender() keeps chunks with  xmin-CWID < chunk.x < xmax+CWID
VISIBLE_X0 = 0 - CWID
VISIBLE_X1 = 3000 + CWID


# ---------------------------------------------------------------------------
# colour helpers
# ---------------------------------------------------------------------------
def parse_color(css):
    """Return (r, g, b, a) with r/g/b in 0..255 and a in 0..1, or None."""
    if css is None:
        return None
    css = css.strip()
    if css == "none":
        return None
    if css == "white":
        return (255, 255, 255, 1.0)
    if css == "black":
        return (0, 0, 0, 1.0)
    if css.startswith("rgb"):
        try:
            inner = css[css.index("(") + 1 : css.rindex(")")]
            parts = [p.strip() for p in inner.split(",")]
            r, g, b = (int(float(parts[0])), int(float(parts[1])), int(float(parts[2])))
            a = float(parts[3]) if len(parts) > 3 else 1.0
            return (r, g, b, a)
        except Exception:
            return None
    return None


def alpha_mask(mask_l, alpha):
    """Scale an L mask (0..255) by a float alpha, returning a new L image."""
    if alpha >= 0.999:
        return mask_l
    v = int(round(alpha * 255))
    return mask_l.point(lambda x: (x * v) // 255)


# ---------------------------------------------------------------------------
# scene rendering
# ---------------------------------------------------------------------------
class Scene:
    def __init__(self, ss, skip_white=False):
        self.ss = ss
        self.skip_white = skip_white
        self.w = CANVAS_W * ss
        self.h = CANVAS_H * ss
        self.group = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
        self.drawn = 0
        self.skipped = 0

    def _composite(self, box, tile):
        x0, y0, x1, y1 = box
        amin, amax = tile.getextrema()[3]
        if amin == 255:  # every pixel opaque -> plain replace is exact
            self.group.paste(tile, (x0, y0))
        else:
            region = self.group.crop(box)
            self.group.paste(Image.alpha_composite(region, tile), (x0, y0))

    def add_shape(self, pts, fill_css, stroke_css, stroke_width_world):
        """pts: list of (world_x, world_y)."""
        if len(pts) < 2:
            self.skipped += 1
            return
        s = self.ss * ZOOM
        px = [(p[0] * s, p[1] * s) for p in pts]
        fill = parse_color(fill_css)
        stroke = parse_color(stroke_css)
        if self.skip_white and (fill_css or "").strip() == "white":
            fill = None
        sw = (stroke_width_world or 0) * s
        if stroke is not None and sw < 0.7:
            stroke = None
        if fill is None and stroke is None:
            self.skipped += 1
            return

        xs = [p[0] for p in px]
        ys = [p[1] for p in px]
        pad = int(math.ceil(max(sw, 1.0) / 2.0)) + 2
        x0 = max(0, int(min(xs)) - pad)
        y0 = max(0, int(min(ys)) - pad)
        x1 = min(self.w, int(max(xs)) + pad + 1)
        y1 = min(self.h, int(max(ys)) + pad + 1)
        if x1 <= x0 or y1 <= y0:
            self.skipped += 1
            return
        w = x1 - x0
        h = y1 - y0
        local = [(p[0] - x0, p[1] - y0) for p in px]

        # ---- fill (SVG polyline fill == implicit closed path) --------------
        if fill is not None and len(local) >= 3:
            m = Image.new("L", (w, h), 0)
            ImageDraw.Draw(m).polygon(local, fill=255)
            tile = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            solid = Image.new("RGBA", (w, h), (fill[0], fill[1], fill[2], 255))
            tile.paste(solid, (0, 0), alpha_mask(m, fill[3]))
            self._composite((x0, y0, x1, y1), tile)

        # ---- stroke (open polyline) ---------------------------------------
        if stroke is not None and len(local) >= 2:
            m = Image.new("L", (w, h), 0)
            ImageDraw.Draw(m).line(local, fill=255, width=max(1, int(round(sw))), joint="curve")
            tile = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            solid = Image.new("RGBA", (w, h), (stroke[0], stroke[1], stroke[2], 255))
            tile.paste(solid, (0, 0), alpha_mask(m, stroke[3]))
            self._composite((x0, y0, x1, y1), tile)

        self.drawn += 1


def render(json_path, ss, out_multiply, out_normal, skip_white=False):
    with open(json_path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    meta = data["meta"]
    chunks = data["chunks"]

    scene = Scene(ss, skip_white=skip_white)
    kept = 0
    for ch in chunks:
        # upstream chunkrender() window filter
        if not (VISIBLE_X0 < ch["x"] < VISIBLE_X1):
            continue
        kept += 1
        for sh in ch["shapes"]:
            scene.add_shape(
                [(p[0], p[1]) for p in sh["points"]],
                sh["fill"],
                sh["stroke"],
                sh["strokeWidth"],
            )

    # ---- downscale the group (premultiplied) ----------------------------
    arr = np.asarray(scene.group).astype(np.float32) / 255.0
    alpha = arr[..., 3]
    premult = arr[..., :3] * alpha[..., None]

    def down(arr2):
        a2 = np.asarray(arr2)
        if a2.ndim == 3 and a2.shape[2] == 1:
            a2 = a2[..., 0]
        return (
            np.asarray(
                Image.fromarray((np.clip(a2, 0, 1) * 255).astype(np.uint8)).resize(
                    (CANVAS_W, CANVAS_H), Image.LANCZOS
                )
            ).astype(np.float32)
            / 255.0
        )

    cs = down(premult)
    a = down(alpha)

    paper = np.array(PAPER, dtype=np.float32)[None, None, :] / 255.0

    # multiply:  final = paper * (A*Cs + (1-A))
    mult = paper * (a[..., None] * cs + (1.0 - a[..., None]))
    # naive source-over: final = A*Cs + (1-A)*paper
    norm = a[..., None] * cs + (1.0 - a[..., None]) * paper

    out_multiply.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray((np.clip(mult, 0, 1) * 255 + 0.5).astype(np.uint8)).save(out_multiply)
    Image.fromarray((np.clip(norm, 0, 1) * 255 + 0.5).astype(np.uint8)).save(out_normal)

    print("=== shan-shui-inf upstream reference render ===")
    print("json          :", json_path)
    print("seed          :", meta["seed"], " Prng.s =", meta["prngStateAfterSeed"])
    print("chunks kept   :", kept, "of", len(chunks), "(upstream chunkrender window filter)")
    print("shapes drawn  :", scene.drawn, " skipped:", scene.skipped)
    print("supersample   :", ss, "->", scene.w, "x", scene.h, "->", CANVAS_W, "x", CANVAS_H)
    print("multiply  png :", out_multiply)
    print("normalblend   :", out_normal)
    return data


# ---------------------------------------------------------------------------
# analysis printed from the JSON (numbers only, no guessing)
# ---------------------------------------------------------------------------
def bbox_of(shapes):
    x0 = y0 = math.inf
    x1 = y1 = -math.inf
    for sh in shapes:
        for p in sh["points"]:
            if p[0] < x0:
                x0 = p[0]
            if p[0] > x1:
                x1 = p[0]
            if p[1] < y0:
                y0 = p[1]
            if p[1] > y1:
                y1 = p[1]
    if x0 == math.inf:
        return None
    return (x0, y0, x1, y1)


def analyse(data):
    meta = data["meta"]
    chunks = data["chunks"]
    a = meta["analysis"]
    VIS_Y1 = meta["worldVisible"]["y1"]

    print()
    print("--- A. distant band (Mount.distMount) -------------------------------")
    ds = sorted(a["A_distmount"]["chunks"], key=lambda d: d["bandX0"])
    for d in ds:
        print(
            "   chunkX=%8.1f  band x=[%8.1f,%8.1f]  width=%7.1f  y=[%6.1f,%6.1f]"
            % (d["chunkX"], d["bandX0"], d["bandX1"], d["bandWidth"], d["bandY0"], d["bandY1"])
        )
    for g in a["A_distmount"]["gaps"]:
        kind = "GAP" if g["gap"] > 0 else "overlap"
        print(
            "   %-8s %8.1f -> %8.1f  (previous band ends %8.1f)  width=%.1f"
            % (kind, g["from"], g["nextFrom"], g["to"], g["gap"])
        )

    print()
    print("--- B. water -------------------------------------------------------")
    ws = a["B_water"]["chunks"]
    spans = [w["verticalSpan"] for w in ws]
    widths = [x for w in ws for x in w["bandWidths"]]
    print("   water chunks      :", len(ws))
    print("   bands per chunk   :", sorted(set(w["bands"] for w in ws)))
    print("   total bands       :", a["B_water"]["totalBands"])
    print(
        "   band length       : min=%.0f max=%.0f mean=%.0f world units"
        % (min(widths), max(widths), sum(widths) / len(widths))
    )
    print(
        "   cluster v-span    : min=%.1f max=%.1f mean=%.1f world units"
        % (min(spans), max(spans), sum(spans) / len(spans))
    )
    print(
        "   cluster v-span    : %.2f%% .. %.2f%% of the visible height (%.1f)"
        % (
            100.0 * min(spans) / VIS_Y1,
            100.0 * max(spans) / VIS_Y1,
            VIS_Y1,
        )
    )

    print()
    print("--- C. mountain foot (Mount.mountain -> foot) -----------------------")
    c = a["C_mountainFoot"]
    print("   white-fill polys  :", c["whiteFill"], "(fill='white' -> invisible under multiply)")
    print("   faint-gray strokes:", c["faintStroke"])
    if c["sample"]:
        s = c["sample"][0]
        print(
            "   sample            : site=%s fill=%s stroke=%s w=%.0f h=%.0f"
            % (s["site"], s["fill"], s["stroke"], s["w"], s["h"])
        )

    print()
    print("--- D. half discs (Mount.rock WHITE BG) -----------------------------")
    d = a["D_halfDisc"]
    ws_ = [s["w"] for s in d["shapes"]]
    hs_ = [s["h"] for s in d["shapes"]]
    print("   count             :", d["count"])
    if ws_:
        print(
            "   size              : w %.0f..%.0f (mean %.0f), h %.0f..%.0f (mean %.0f)"
            % (
                min(ws_),
                max(ws_),
                sum(ws_) / len(ws_),
                min(hs_),
                max(hs_),
                sum(hs_) / len(hs_),
            )
        )
        owners = {}
        for s in d["shapes"]:
            owners[s["owner"]] = owners.get(s["owner"], 0) + 1
        print("   owners            :", owners)

    print()
    print("--- layer y-extents (world units, visible screen) -------------------")
    layers = {
        "distant band (distmount)": "distmount",
        "mid mountains (mount)": "mountain",
        "water (water)": "water",
        "near bank (flatmount)": "flatmount",
        "boats": "boat",
    }
    for title, kind in layers.items():
        sel = [c for c in chunks if c["kind"] == kind]
        bb = bbox_of([sh for c in sel for sh in c["shapes"]])
        ys = [c["y"] for c in sel]
        xs = [c["x"] for c in sel]
        if bb is None:
            continue
        print(
            "   %-28s n=%3d  chunkX[%7.1f,%7.1f] chunkY[%7.1f,%7.1f]  shapeY[%6.1f,%6.1f] shapeX[%7.1f,%7.1f]"
            % (
                title,
                len(sel),
                min(xs),
                max(xs),
                min(ys),
                max(ys),
                bb[1],
                bb[3],
                bb[0],
                bb[2],
            )
        )

    print()
    print("--- palette check ----------------------------------------------------")
    colors = {}
    for ch in chunks:
        for sh in ch["shapes"]:
            for key in ("fill", "stroke"):
                colors[sh[key]] = colors.get(sh[key], 0) + 1
    hues = set()
    for css in colors:
        col = parse_color(css)
        if col:
            r, g, b, _ = col
            if not (r == g == b) and css != "none":
                hues.add(css)
    print("   distinct colours  :", len(colors))
    print("   non-grey colours  :", len(hues), sorted(hues)[:10])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default=str(DEFAULT_JSON))
    ap.add_argument("--ss", type=int, default=2, help="supersampling factor")
    ap.add_argument("--out", default=str(OUT_MULTIPLY))
    ap.add_argument("--out-normal", default=str(OUT_NORMAL))
    ap.add_argument(
        "--skip-white",
        action="store_true",
        help="treat upstream's pure-'white' fills as fully transparent "
        "(diagnostic: shows what a port that ignores mix-blend-mode would reveal)",
    )
    args = ap.parse_args()

    data = render(
        Path(args.json),
        args.ss,
        Path(args.out),
        Path(args.out_normal),
        skip_white=args.skip_white,
    )
    analyse(data)


if __name__ == "__main__":
    main()
