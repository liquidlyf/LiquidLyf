"""Makes the screen gallery for the UI/UX review (project-plan 13, step 1).

Runs ScreenGalleryTest (app/src/androidTest/.../gallery/) on the USB phone with the demo build,
pulls its pictures (and each first page's UI tree, into _trees/), and writes a README.md index
plus one contact sheet per screen state (its 8 variants side by side) into _sheets/.

    python app/scripts/screen_gallery.py --out <folder>
        [--tests home,requests]        only these test methods (default: all)
        [--only home-filled,requests]  only screen states whose name starts so
        [--variants light-en-100]      only these variants
        [--no-install] [--index-only]

While it runs, Do Not Disturb is on (if it was off: priority mode, so alarms still ring) and the phone's animations are off where the phone allows
it (some phones, such as realme's, refuse `settings put` unless "Disable permission monitoring" is
on in Developer options; the test then waits a little longer for dialogs). Every setting goes
back to what it was afterwards, even when the run fails. The demo app and
its test app stay installed: the pictures are in the app's own files folder until pulled.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PKG = "io.github.liquidlyf.demo"
RUNNER = f"{PKG}.test/io.github.liquidlyf.HiltTestRunner"
CLASS = "io.github.liquidlyf.gallery.ScreenGalleryTest"
DEVICE_DIR = f"/sdcard/Android/data/{PKG}/files/gallery"
TESTS = ["beforeSignIn", "home", "requests", "requestBlood", "requestDetails", "findDonors",
         "profile", "settings", "account", "inboxAndModeration"]
VARIANTS = [f"{t}-{l}-{s}" for s in ("100", "200") for t in ("light", "dark") for l in ("en", "bn")]
NAME = re.compile(r"^(?P<state>.+)-(?P<variant>(?:light|dark)-(?:en|bn)-(?:100|200))(?:-p(?P<page>\d+))?\.png$")
# Settings changed while the gallery runs (namespace, key, value).
TEMPORARY = [
    ("global", "window_animation_scale", "0"),
    ("global", "transition_animation_scale", "0"),
    ("global", "animator_duration_scale", "0"),
]


def find_adb() -> str:
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")):
        if root:
            path = Path(root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if path.exists():
                return str(path)
    found = shutil.which("adb")
    if not found:
        sys.exit("adb not found: set ANDROID_HOME")
    return found


ADB = find_adb()


def adb(*args: str, check: bool = True, timeout: int = 60) -> str:
    r = subprocess.run([ADB, *args], capture_output=True, timeout=timeout)
    out = r.stdout.decode("utf-8", errors="replace")
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace').strip()}")
    return out


def shell(command: str, check: bool = True, timeout: int = 60) -> str:
    return adb("shell", command, check=check, timeout=timeout).strip()


def run_gallery(args) -> None:
    saved = {}
    zen = shell("settings get global zen_mode")
    try:
        for ns, key, value in TEMPORARY:
            before = shell(f"settings get {ns} {key}")
            if "Exception" in shell(f"settings put {ns} {key} {value}", check=False):
                print(f"the phone refused to change {key}; left as it is", flush=True)
            else:
                saved[(ns, key)] = before
        if zen == "0":  # off: turn it on for the run (priority mode, so alarms still ring); else leave the owner's setting
            shell("cmd notification set_dnd priority", check=False)
        shell(f"rm -rf {DEVICE_DIR}", check=False)
        extras = ["-e", "gallery", "true"]
        if args.only:
            extras += ["-e", "galleryOnly", args.only]
        if args.variants:
            extras += ["-e", "galleryVariants", args.variants]
        for test in args.tests.split(",") if args.tests else TESTS:
            started = time.time()
            out = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", f"{CLASS}#{test}", *extras, RUNNER,
                      check=False, timeout=3600)
            ok = "INSTRUMENTATION_CODE: -1" in out and "FAILURES!!!" not in out and "INSTRUMENTATION_STATUS_CODE: -2" not in out
            print(f"{test}: {'ok' if ok else 'FAILED'} ({time.time() - started:.0f} s)", flush=True)
            if not ok:
                stack = re.findall(r"INSTRUMENTATION_STATUS: stack=(.*?)(?:INSTRUMENTATION_STATUS:|\Z)", out, re.S)
                print((stack[0] if stack else out)[-3000:], flush=True)
    finally:
        for (ns, key), value in saved.items():
            shell(f"settings delete {ns} {key}" if value == "null" else f"settings put {ns} {key} {value}", check=False)
        for _ in range(5 if zen == "0" else 0):  # only if this script turned it on; once it didn't take at the first try
            shell("cmd notification set_dnd off", check=False)
            time.sleep(1)
            if shell("settings get global zen_mode") == zen:
                break
        print(f"phone settings restored (Do Not Disturb: zen_mode {shell('settings get global zen_mode')}, was {zen})", flush=True)


def pull(out: Path, replace_all: bool) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        adb("pull", DEVICE_DIR, tmp, timeout=1800)
        pulled = Path(tmp) / "gallery"
        trees = out / "_trees"
        trees.mkdir(exist_ok=True)
        if replace_all:
            for old in [*out.glob("*.png"), *trees.glob("*.xml")]:
                old.unlink()
        for png in pulled.glob("*.png"):
            shutil.move(str(png), out / png.name)
        for xml in (pulled / "trees").glob("*.xml"):
            shutil.move(str(xml), trees / xml.name)


def index(out: Path) -> None:
    from PIL import Image, ImageDraw, ImageFont

    states: dict[str, dict[str, int]] = {}
    for png in sorted(out.glob("*.png")):
        m = NAME.match(png.name)
        if not m:
            continue
        pages = states.setdefault(m["state"], {})
        pages[m["variant"]] = max(pages.get(m["variant"], 0), int(m["page"] or 1))
    sheets = out / "_sheets"
    if sheets.exists():
        shutil.rmtree(sheets)
    sheets.mkdir()
    font = ImageFont.load_default(size=18)
    w, h, gap, band = 240, 533, 8, 28
    for state, pages in states.items():
        sheet = Image.new("RGB", (4 * w + 5 * gap, 2 * (h + band) + 3 * gap), "white")
        draw = ImageDraw.Draw(sheet)
        for i, variant in enumerate(VARIANTS):
            x, y = gap + (i % 4) * (w + gap), gap + (i // 4) * (h + band + gap)
            more = pages.get(variant, 0)
            label = variant + (f"  +{more - 1} page{'s' if more > 2 else ''}" if more > 1 else "")
            draw.text((x, y + 4), label if variant in pages else f"{variant}: none", fill="black", font=font)
            if variant in pages:
                with Image.open(out / f"{state}-{variant}.png") as shot:
                    sheet.paste(shot.convert("RGB").resize((w, h)), (x, y + band))
        sheet.save(sheets / f"{state}.png", optimize=True)
    lines = [
        "# Screen gallery (UI/UX review, step 1)",
        "",
        "Made by `app/scripts/screen_gallery.py`, which runs `ScreenGalleryTest` on the USB phone (realme RMX2101,",
        "Android 11, 720×1600 px at 320 dpi = 360×800 dp) with the demo build. Each picture is the whole phone screen,",
        "status bar included (the phone's real one, with Do Not Disturb on).",
        "",
        "- **Names:** `<screen>-<state>-<light|dark>-<en|bn>-<100|200>.png`. A screen that scrolls also has",
        "  `…-p2.png`, `…-p3.png`: the same screen scrolled down by about 80% of its height each time.",
        "- **`_trees/<name>.xml`:** each first page's UI tree in `uiautomator dump`'s format (what TalkBack sees, with",
        "  exact bounds in px; 1 dp = 2 px on this phone), so tap sizes can be measured at 200% too.",
        "- **`_sheets/<screen>-<state>.png`:** all 8 variants side by side (first page only; the label says how many",
        "  more pages there are). Start here.",
        "- **The data is made up** (fake people, requests and numbers, from the test's fakes); nothing comes from Firebase.",
        "- **The screens are the app's real Compose screens.** Most are drawn from a made-up screen state. Request",
        "  details, the request form's money warning, Find donors' ask sheet, Profile, and the account screens run",
        "  their real view models on fakes, and sheets, menus and dialogs are opened by tapping, as a person would.",
        "- **200%** is font scale 2.0, as Android 11–13 apply it (every text size doubles; Android 14+ grows large",
        "  text less). Display size is the phone's default.",
        "- **Spinners and skeletons** (`…-loading`) are shown 0.7 s into their animation.",
        "- **Not here:** the launch splash, the system's own screens (notification permission prompt, Google sign-in",
        "  sheet, share sheet, date and time pickers), and real notifications. The phone measurements and videos in",
        "  `ui-audit\\` show the running app.",
        "",
        f"{len(states)} screen states, {sum(1 for _ in out.glob('*.png'))} pictures.",
        "",
        "| Screen state | Pages per variant (" + ", ".join(VARIANTS) + ") |",
        "|---|---|",
    ]
    for state, pages in states.items():
        lines.append(f"| `{state}` | " + " ".join(str(pages.get(v, "–")) for v in VARIANTS) + " |")
    (out / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"indexed {len(states)} screen states", flush=True)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", required=True, type=Path)
    p.add_argument("--tests")
    p.add_argument("--only")
    p.add_argument("--variants")
    p.add_argument("--no-install", action="store_true")
    p.add_argument("--index-only", action="store_true")
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if not args.index_only:
        if not args.no_install:
            gradlew = REPO / ("gradlew.bat" if os.name == "nt" else "gradlew")
            subprocess.run([str(gradlew), ":app:installDemoDebug", ":app:installDemoDebugAndroidTest", "--console=plain", "-q"],
                           cwd=REPO, check=True)
        run_gallery(args)
        pull(args.out, replace_all=not (args.tests or args.only or args.variants))
    index(args.out)


if __name__ == "__main__":
    main()
