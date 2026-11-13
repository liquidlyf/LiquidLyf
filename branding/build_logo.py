"""Build the flat LiquidLyf logo SVGs in this folder.

The geometry was measured from the original university-era logo, in "heart units": origin at the
heart's top-centre, x to the right, y down; the heart is 518 wide and 444 tall.

    pip install fonttools
    python build_logo.py path/to/Montserrat[wght].ttf .

Montserrat (SIL Open Font License) is at https://github.com/google/fonts/tree/main/ofl/montserrat.
The font file isn't needed to use the logo: the wordmark is converted to outlines.
"""
import math
import sys
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.recordingPen import DecomposingRecordingPen

FONT_PATH, OUT = Path(sys.argv[1]), Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------- colours
RED = "#DE2027"        # heart and "Liquid" (4.8:1 on white)
FOLD = "#B71C22"       # the fold: a darker red, close to the original's shading
WHITE = "#FFFFFF"      # drop ring and highlight
LYF = "#534341"        # "Lyf" on light backgrounds (M3 onSurfaceVariant)
RED_ON_DARK = "#F2353B"  # "Liquid" on dark backgrounds (4.7:1 on #1A1111)
LYF_ON_DARK = "#D8C2BF"  # "Lyf" on dark backgrounds (M3 dark onSurfaceVariant)

# ---------------------------------------------------------------- geometry (heart units)
LOBE_D, LOBE_R, TIP_Y = 118.0, 141.0, 444.0      # lobe circle centres (±D, R), radius R
LOBE_CY = LOBE_R
DROP_CY, DROP_R, DROP_APEX = 285.5, 93.5, 112.0  # drop circle centre y, radius, apex y
RING = 19.0                                       # white ring thickness
HL_R, HL_W, HL_A1, HL_A2 = 55.0, 8.5, 97.0, 190.0  # highlight arc radius, width, angles (deg, y down)
FOLD_C, FOLD_AX, FOLD_ANG = (49.1, 234.1), (220.9, 147.6), -58.8  # fitted ellipse


def num(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def P(p, f):
    x, y = f(p)
    return f"{num(x)} {num(y)}"


def heart_points():
    c = (LOBE_D, LOBE_CY)
    tip = (0.0, TIP_Y)
    dx, dy = tip[0] - c[0], tip[1] - c[1]
    dist = math.hypot(dx, dy)
    base = math.atan2(dy, dx)
    off = math.acos(LOBE_R / dist)
    a = base - off  # outer tangent point on the right lobe
    tr = (c[0] + LOBE_R * math.cos(a), c[1] + LOBE_R * math.sin(a))
    notch_y = LOBE_CY - math.sqrt(LOBE_R ** 2 - LOBE_D ** 2)
    return tip, tr, (-tr[0], tr[1]), (0.0, notch_y)


def heart_path(f, s):
    tip, tr, tl, notch = heart_points()
    r = num(LOBE_R * s)
    return (f"M{P(tip, f)}L{P(tr, f)}A{r} {r} 0 1 0 {P(notch, f)}"
            f"A{r} {r} 0 1 0 {P(tl, f)}Z")


def drop_path(scale, f, s):
    """Drop outline scaled about the circle centre (scale 1 = outer edge)."""
    R = DROP_R * scale
    D = (DROP_CY - DROP_APEX) * scale
    apex = (0.0, DROP_CY - D)
    off = math.acos(R / D)
    a_right = -math.pi / 2 + off
    a_left = -math.pi / 2 - off
    pr = (R * math.cos(a_right), DROP_CY + R * math.sin(a_right))
    pl = (R * math.cos(a_left), DROP_CY + R * math.sin(a_left))
    r = num(R * s)
    return f"M{P(apex, f)}L{P(pr, f)}A{r} {r} 0 1 1 {P(pl, f)}Z"


def highlight_path(f, s):
    ro, ri, hw = HL_R + HL_W / 2, HL_R - HL_W / 2, HL_W / 2
    a1, a2 = math.radians(HL_A1), math.radians(HL_A2)

    def on(r, a):
        return (r * math.cos(a), DROP_CY + r * math.sin(a))

    return (f"M{P(on(ro, a1), f)}A{num(ro * s)} {num(ro * s)} 0 0 1 {P(on(ro, a2), f)}"
            f"A{num(hw * s)} {num(hw * s)} 0 0 1 {P(on(ri, a2), f)}"
            f"A{num(ri * s)} {num(ri * s)} 0 0 0 {P(on(ri, a1), f)}"
            f"A{num(hw * s)} {num(hw * s)} 0 0 1 {P(on(ro, a1), f)}Z")


def ellipse():
    """The fitted fold ellipse, rescaled so it passes exactly through the heart's notch."""
    (cx, cy), (a, b), ang = FOLD_C, FOLD_AX, math.radians(FOLD_ANG)
    _, _, _, notch = heart_points()
    dx, dy = notch[0] - cx, notch[1] - cy
    c, s_ = math.cos(ang), math.sin(ang)
    q = ((dx * c + dy * s_) / a) ** 2 + ((-dx * s_ + dy * c) / b) ** 2
    k = math.sqrt(q)
    return (cx, cy), (a * k, b * k), ang


def ellipse_line_hits(p0, p1):
    """Parameters t in [0,1] where segment p0->p1 crosses the fold ellipse."""
    (cx, cy), (a, b), ang = ellipse()
    c, s_ = math.cos(ang), math.sin(ang)

    def local(p):
        dx, dy = p[0] - cx, p[1] - cy
        return ((dx * c + dy * s_) / a, (-dx * s_ + dy * c) / b)

    u0, u1 = local(p0), local(p1)
    du = (u1[0] - u0[0], u1[1] - u0[1])
    A = du[0] ** 2 + du[1] ** 2
    B = 2 * (u0[0] * du[0] + u0[1] * du[1])
    C = u0[0] ** 2 + u0[1] ** 2 - 1
    disc = B * B - 4 * A * C
    if disc < 0:
        return []
    r = math.sqrt(disc)
    return sorted(t for t in ((-B - r) / (2 * A), (-B + r) / (2 * A)) if 0 <= t <= 1)


def fold_path(f, s):
    tip, tr, tl, notch = heart_points()
    (cx, cy), (a, b), ang = ellipse()
    # where the ellipse leaves the heart on the two straight lower edges
    tr_hits = ellipse_line_hits(tr, tip)
    tl_hits = ellipse_line_hits(tl, tip)
    assert tr_hits and tl_hits, (tr_hits, tl_hits)
    lerp = lambda p, q, t: (p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t)
    p1 = lerp(tr, tip, tr_hits[0])
    p2 = lerp(tl, tip, tl_hits[0])
    ra, rb, deg = num(a * s), num(b * s), num(math.degrees(ang))
    return (f"M{P(notch, f)}A{ra} {rb} {deg} 0 1 {P(p1, f)}L{P(tip, f)}"
            f"L{P(p2, f)}A{ra} {rb} {deg} 0 1 {P(notch, f)}Z"), p1, p2


def check_fold_inside():
    """Sample the fold's elliptical edge between the notch and the exits; all must be inside the heart."""
    (cx, cy), (a, b), ang = ellipse()
    tip, tr, tl, notch = heart_points()
    bad = 0
    for i in range(720):
        t = 2 * math.pi * i / 720
        x = cx + a * math.cos(t) * math.cos(ang) - b * math.sin(t) * math.sin(ang)
        y = cy + a * math.cos(t) * math.sin(ang) + b * math.sin(t) * math.cos(ang)
        if y > tr[1]:
            continue  # lower part: the path uses the heart's own edges there
        inside_lobe = min(math.hypot(x - LOBE_D, y - LOBE_CY), math.hypot(x + LOBE_D, y - LOBE_CY)) <= LOBE_R + 0.01
        if not inside_lobe and y < notch[1] + 1 and abs(x) < 2:
            continue
        if not inside_lobe:
            bad += 1
    return bad


def mark_mapper(size, margin_x):
    """Map heart units into a square viewBox `size`, heart width = size - 2*margin_x, centred."""
    s = (size - 2 * margin_x) / 518.0
    oy = (size - TIP_Y * s) / 2
    return (lambda p: (size / 2 + p[0] * s, oy + p[1] * s)), s


# ---------------------------------------------------------------- wordmark
def load_font():
    font = TTFont(FONT_PATH)
    return instantiateVariableFont(font, {"wght": 500}, inplace=False)


def pair_kern(font, left, right):
    """Horizontal kerning (font units) for a glyph pair from GPOS PairPos lookups in 'kern'."""
    if "GPOS" not in font:
        return 0
    gpos = font["GPOS"].table
    lookups = set()
    for fr in gpos.FeatureList.FeatureRecord:
        if fr.FeatureTag == "kern":
            lookups.update(fr.Feature.LookupListIndex)
    total = 0
    for li in sorted(lookups):
        lk = gpos.LookupList.Lookup[li]
        subs = lk.SubTable
        if lk.LookupType == 9:
            subs = [st.ExtSubTable for st in subs]
        for st in subs:
            cov = st.Coverage.glyphs
            if left not in cov:
                continue
            if st.Format == 1:
                ps = st.PairSet[cov.index(left)]
                for rec in ps.PairValueRecord:
                    if rec.SecondGlyph == right:
                        v = rec.Value1
                        return total + (getattr(v, "XAdvance", 0) or 0)
            elif st.Format == 2:
                c1 = st.ClassDef1.classDefs.get(left, 0)
                c2 = st.ClassDef2.classDefs.get(right, 0)
                v = st.Class1Record[c1].Class2Record[c2].Value1
                adv = getattr(v, "XAdvance", 0) or 0
                if adv:
                    return total + adv
    return total


def word_paths(font, text, x0, baseline, scale):
    """Return (svg path d, end x) for `text` set at `baseline`, scale = units per font unit."""
    cmap = font.getBestCmap()
    gs = font.getGlyphSet()
    hmtx = font["hmtx"]
    names = [cmap[ord(ch)] for ch in text]
    ds = []
    x = x0
    for i, g in enumerate(names):
        rec = DecomposingRecordingPen(gs)
        gs[g].draw(rec)
        svg = SVGPathPen(None, ntos=num)
        rec.replay(TransformPen(svg, (scale, 0, 0, -scale, x, baseline)))
        ds.append(svg.getCommands())
        adv = hmtx[g][0]
        if i + 1 < len(names):
            adv += pair_kern(font, g, names[i + 1])
        x += adv * scale
    return "".join(ds), x


def text_advance(font, text, next_text=""):
    cmap = font.getBestCmap()
    hmtx = font["hmtx"]
    names = [cmap[ord(ch)] for ch in text + next_text]
    w = 0
    for i in range(len(text)):
        w += hmtx[names[i]][0]
        if i + 1 < len(names):
            w += pair_kern(font, names[i], names[i + 1])
    return w


def glyph_bounds(font, text):
    """Ink top (max y) and bottom (min y) in font units for the text."""
    from fontTools.pens.boundsPen import BoundsPen
    gs = font.getGlyphSet()
    cmap = font.getBestCmap()
    ymin, ymax = 0, 0
    for ch in text:
        bp = BoundsPen(gs)
        gs[cmap[ord(ch)]].draw(bp)
        if bp.bounds:
            ymin = min(ymin, bp.bounds[1])
            ymax = max(ymax, bp.bounds[3])
    return ymin, ymax


# ---------------------------------------------------------------- writers
HEADER = '<?xml version="1.0" encoding="UTF-8"?>\n'


def svg(view_w, view_h, title, body):
    return (HEADER + f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {num(view_w)} {num(view_h)}" '
            f'width="{num(view_w)}" height="{num(view_h)}" role="img" aria-label="{title}">\n'
            f"<title>{title}</title>\n{body}</svg>\n")


def mark_body(f, s):
    fold, p1, p2 = fold_path(f, s)
    return (f'<path fill="{RED}" d="{heart_path(f, s)}"/>\n'
            f'<path fill="{FOLD}" d="{fold}"/>\n'
            f'<path fill="{WHITE}" d="{drop_path(1.0, f, s)}"/>\n'
            f'<path fill="{RED}" d="{drop_path((DROP_R - RING) / DROP_R, f, s)}"/>\n'
            f'<path fill="{WHITE}" d="{highlight_path(f, s)}"/>\n')


def main():
    print("fold edge points outside the heart:", check_fold_inside())
    _, p1, p2 = fold_path(lambda p: p, 1)
    print("fold exits at", [round(v, 1) for v in p1], [round(v, 1) for v in p2])

    # 1. logo-mark.svg: 512 square, heart 448 wide
    f, s = mark_mapper(512, 32)
    (OUT / "logo-mark.svg").write_text(svg(512, 512, "LiquidLyf", mark_body(f, s)), encoding="utf-8")

    # 2. logo-mark-mono.svg: one flat shape (heart with the drop ring and highlight cut out), no fold
    mono = (heart_path(f, s) + drop_path(1.0, f, s) + drop_path((DROP_R - RING) / DROP_R, f, s)
            + highlight_path(f, s))
    (OUT / "logo-mark-mono.svg").write_text(
        svg(512, 512, "LiquidLyf", f'<path fill="#000000" fill-rule="evenodd" d="{mono}"/>\n'),
        encoding="utf-8")

    # 3. logo-full.svg / logo-full-dark.svg: mark above the wordmark, centred
    font = load_font()
    upm = font["head"].unitsPerEm
    cap = font["OS/2"].sCapHeight
    k = (0.338 * TIP_Y) / cap            # cap height = 0.338 x heart height, as in the original
    w_liquid = text_advance(font, "Liquid", "L")
    w_lyf = text_advance(font, "Lyf")
    text_w = (w_liquid + w_lyf) * k
    ymin, ymax = glyph_bounds(font, "LiquidLyf")
    gap = 0.12 * TIP_Y
    pad = 40.0
    width = max(text_w, 518.0) + 2 * pad
    baseline = pad + TIP_Y + gap + ymax * k
    height = baseline - ymin * k + pad
    hx = width / 2

    def fm(p):
        return (hx + p[0], pad + p[1])

    tx = (width - text_w) / 2
    d_liquid, _ = word_paths(font, "Liquid", tx, baseline, k)
    # w_liquid already includes the "d"-"L" kerning, so "Lyf" starts there
    d_lyf, _ = word_paths(font, "Lyf", tx + w_liquid * k, baseline, k)
    for name, c_liquid, c_lyf in (("logo-full.svg", RED, LYF), ("logo-full-dark.svg", RED_ON_DARK, LYF_ON_DARK)):
        body = (mark_body(fm, 1.0)
                + f'<path fill="{c_liquid}" d="{d_liquid}"/>\n'
                + f'<path fill="{c_lyf}" d="{d_lyf}"/>\n')
        (OUT / name).write_text(svg(width, height, "LiquidLyf", body), encoding="utf-8")
    print("full logo viewBox", round(width, 1), round(height, 1), "text width", round(text_w, 1),
          "kern d-L", pair_kern(font, "d", "L"), "kern L-y", pair_kern(font, "L", "y"))


if __name__ == "__main__":
    main()
