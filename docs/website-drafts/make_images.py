"""Makes the website's PNG images in site/assets/ from the logo SVGs in branding/.

    pip install pillow
    python docs/website-drafts/make_images.py

- share-en.png, share-bn.png: 1200 × 630 cards that Facebook, Messenger and WhatsApp show for a link;
- favicon-32.png: the browser-tab icon for browsers without SVG icons;
- apple-touch-icon.png: 180 × 180, for "Add to Home screen" (white background, like the app icon).

It draws each image as a small HTML page and takes a screenshot with headless Chrome (or Edge), using
a temporary empty profile, then sizes it with Pillow. Bangla text needs a Bangla system font; Windows
has Nirmala UI. Run it again after the logo or the tagline changes, and commit the PNGs.
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
OUT = HERE / "site" / "assets"
BRAND = REPO / "branding"

BROWSERS = [
    os.environ.get("CHROME", ""),
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "google-chrome", "chromium", "chromium-browser", "microsoft-edge",
]

SHARE = {
    "en": ("Need blood? Find donors near you.",
           "Free · No ads · Your number stays private",
           "system-ui, 'Segoe UI', Roboto, sans-serif"),
    "bn": ("রক্ত লাগবে? কাছের রক্তদাতা খুঁজুন",
           "ফ্রি · কোনো বিজ্ঞাপন নেই · আপনার নম্বর গোপন থাকে",
           "'Noto Sans Bengali', 'Nirmala UI', 'Vrinda', sans-serif"),
}


def browser() -> str:
    for b in BROWSERS:
        if b and (pathlib.Path(b).is_file() or shutil.which(b)):
            return b
    raise SystemExit("Chrome or Edge not found: set CHROME to its path")


def uri(p: pathlib.Path) -> str:
    return p.resolve().as_uri()


def shoot(page: str, width: int, height: int, dest: pathlib.Path, transparent: bool = False) -> pathlib.Path:
    """Screenshots the page at width × height; returns a temporary PNG next to dest."""
    with tempfile.TemporaryDirectory() as tmp:
        src = pathlib.Path(tmp) / "page.html"
        src.write_text(page, encoding="utf-8")
        png = pathlib.Path(tmp) / "shot.png"
        cmd = [browser(), "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run",
               "--allow-file-access-from-files", f"--user-data-dir={pathlib.Path(tmp) / 'profile'}",
               "--force-device-scale-factor=1", f"--window-size={width},{height}",
               f"--screenshot={png}", uri(src)]
        if transparent:
            cmd.insert(1, "--default-background-color=00000000")
        subprocess.run(cmd, check=True, capture_output=True, timeout=120)
        img = Image.open(png).convert("RGBA").crop((0, 0, width, height))
        img.save(dest.with_suffix(".tmp.png"))
    return dest.with_suffix(".tmp.png")


def share(lang: str) -> None:
    title, sub, font = SHARE[lang]
    page = f"""<!doctype html><meta charset="utf-8"><style>
html, body {{ margin: 0; width: 1200px; height: 630px; overflow: hidden; background: #fffbff; }}
body {{ display: grid; grid-template-columns: 1fr 420px; align-items: center; font-family: {font}; color: #201a19; }}
.text {{ padding: 0 0 0 80px; }}
.logo {{ height: 64px; display: block; margin-bottom: 44px; }}
h1 {{ margin: 0 0 24px; font-size: {64 if lang == 'en' else 60}px; line-height: {1.12 if lang == 'en' else 1.35}; font-weight: 800; }}
p {{ margin: 0; font-size: {30 if lang == 'en' else 27}px; color: #534341; font-weight: 600; white-space: nowrap; }}
.url {{ margin-top: 40px; font-family: system-ui, 'Segoe UI', sans-serif; font-size: 26px; color: #a4161a; font-weight: 700; }}
.art {{ width: 360px; height: 360px; border-radius: 50%; background: #fff4f2; border: 2px solid #e7d6d3;
        display: grid; place-items: center; }}
.art img {{ width: 230px; transform: translateY(3.5%); }}
.bar {{ position: absolute; left: 0; right: 0; bottom: 0; height: 14px; background: #b71c22; }}
</style>
<div class="text">
  <img class="logo" src="{uri(BRAND / 'logo-horizontal.svg')}" alt="">
  <h1>{title}</h1>
  <p>{sub}</p>
  <p class="url">liquidlyf.github.io</p>
</div>
<div class="art"><img src="{uri(BRAND / 'logo-mark.svg')}" alt=""></div>
<div class="bar"></div>"""
    tmp = shoot(page, 1200, 630, OUT / f"share-{lang}.png")
    Image.open(tmp).convert("RGB").save(OUT / f"share-{lang}.png", optimize=True)
    tmp.unlink()


def icons() -> None:
    mark = uri(BRAND / "logo-mark.svg")
    # Transparent, the mark filling the square (browser tabs).
    tmp = shoot(f"""<!doctype html><style>html,body{{margin:0;background:transparent}}
img{{display:block;width:512px;height:512px}}</style><img src="{mark}">""", 512, 512, OUT / "favicon-32.png", True)
    Image.open(tmp).resize((32, 32), Image.LANCZOS).save(OUT / "favicon-32.png", optimize=True)
    tmp.unlink()
    # White square, the mark at 75 % and 18/512 lower (optical centring, branding/README.md).
    tmp = shoot(f"""<!doctype html><style>html,body{{margin:0;background:#fff;width:720px;height:720px}}
img{{position:absolute;left:90px;top:{90 + round(18 / 512 * 540)}px;width:540px;height:540px}}</style>
<img src="{mark}">""", 720, 720, OUT / "apple-touch-icon.png")
    Image.open(tmp).convert("RGB").resize((180, 180), Image.LANCZOS).save(OUT / "apple-touch-icon.png", optimize=True)
    tmp.unlink()


if __name__ == "__main__":
    share("en")
    share("bn")
    icons()
    for f in ("share-en.png", "share-bn.png", "favicon-32.png", "apple-touch-icon.png"):
        p = OUT / f
        print(f"{f}: {Image.open(p).size[0]} × {Image.open(p).size[1]}, {p.stat().st_size // 1024} KB")
    sys.exit(0)
