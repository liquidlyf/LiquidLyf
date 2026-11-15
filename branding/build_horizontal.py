"""Build the side-by-side logos (mark left, wordmark right) from logo-full.svg and logo-full-dark.svg.

    python build_horizontal.py

No font is needed: it moves the existing paths. The mark is scaled to twice the wordmark's cap
height, and its optical centre (the heart's area centre, see README "Optical centring") lines up with
the middle of the capitals. The output stays flat: filled paths only, no transforms.
"""
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Measured in logo-full.svg (heart units = logo-full units).
HEART_LEFT, HEART_TOP, HEART_W, HEART_H = 284.71, 40.0, 518.0, 444.0
HEART_AREA_CY = 180.96          # area centre below the heart's top (512 grid: y 220.5, scale 0.8649)
CAP_TOP, BASELINE = 549.45, 699.52  # the "L" of the wordmark
TEXT_LEFT, TEXT_TOP, TEXT_RIGHT, TEXT_BOTTOM = 62.5, 537.3, 1054.9, 742.3
MARK_PATHS = 5                   # the first five paths are the mark, the rest the wordmark

CAP = BASELINE - CAP_TOP
K = 2 * CAP / HEART_H            # mark height = 2 × cap height
GAP = CAP / 2                    # space between the mark and the wordmark
MARK_TOP = (CAP_TOP + BASELINE) / 2 - HEART_AREA_CY * K   # in logo-full's y, before moving to 0
TOP = min(MARK_TOP, TEXT_TOP)
BOTTOM = max(MARK_TOP + HEART_H * K, TEXT_BOTTOM)
DX_TEXT = HEART_W * K + GAP - TEXT_LEFT
WIDTH, HEIGHT = TEXT_RIGHT + DX_TEXT, BOTTOM - TOP

ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "Q": 4, "C": 6, "A": 7, "Z": 0}


def num(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def transform(d: str, k: float, dx: float, dy: float) -> str:
    """x' = k·x + dx, y' = k·y + dy, for absolute M L H V Q C A Z paths."""
    out = []
    for cmd, args in re.findall(r"([MLHVQCAZ])([^MLHVQCAZ]*)", d):
        if re.search(r"[a-z]", args):
            raise SystemExit("relative path commands aren't supported")
        n = [float(v) for v in re.findall(r"-?\d*\.?\d+(?:e-?\d+)?", args)]
        step = ARITY[cmd]
        groups = [n[i:i + step] for i in range(0, len(n), step)] if step else [[]]
        parts = []
        for g in groups:
            if cmd in "MLQC":
                parts += [num(k * g[i] + (dx if i % 2 == 0 else dy)) for i in range(len(g))]
            elif cmd == "H":
                parts.append(num(k * g[0] + dx))
            elif cmd == "V":
                parts.append(num(k * g[0] + dy))
            elif cmd == "A":
                rx, ry, rot, large, sweep, x, y = g
                parts += [num(k * rx), num(k * ry), num(rot), str(int(large)), str(int(sweep)),
                          num(k * x + dx), num(k * y + dy)]
        out.append(cmd + " ".join(parts))
    return "".join(out)


def build(src: str, dest: str) -> None:
    text = (HERE / src).read_text(encoding="utf-8")
    paths = re.findall(r'<path fill="([^"]+)" d="([^"]+)"/>', text)
    lines = []
    for i, (fill, d) in enumerate(paths):
        if i < MARK_PATHS:
            d = transform(d, K, -K * HEART_LEFT, MARK_TOP - K * HEART_TOP - TOP)
        else:
            d = transform(d, 1.0, DX_TEXT, -TOP)
        lines.append(f'<path fill="{fill}" d="{d}"/>')
    w, h = num(WIDTH), num(HEIGHT)
    svg = (f'<?xml version="1.0" encoding="UTF-8"?>\n'
           f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" '
           f'role="img" aria-label="LiquidLyf">\n<title>LiquidLyf</title>\n' + "\n".join(lines) + "\n</svg>\n")
    (HERE / dest).write_text(svg, encoding="utf-8", newline="\n")
    print(f"{dest}: {w} × {h}")


if __name__ == "__main__":
    build("logo-full.svg", "logo-horizontal.svg")
    build("logo-full-dark.svg", "logo-horizontal-dark.svg")
