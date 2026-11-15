"""Builds the liquidlyf.github.io website from the drafts in this folder.

The drafts stay the single source: a pull request that changes the app's data practices changes the
Privacy Policy here too, and this script publishes the result into a clone of the website repo.

Usage, from the app repo's root:

    python docs/website-drafts/build_site.py ../liquidlyf.github.io --date 2026-11-16 \
        --publisher "Name of the publisher" --address "Bangladesh" --address-bn "বাংলাদেশ"

Options:
    --released            v1.0 is on GitHub Releases: publish the full download page and the home
                          page's download button. Without it, /download says "coming soon".
    --allow-placeholders  for a preview only: keep any [PLACEHOLDER] that has no value.

What it does to each page: removes HTML comments (the CHECK notes and other notes for maintainers,
which must never reach the public page), removes the draft-only `status:` front-matter line, and
fills [EFFECTIVE DATE], [PUBLISHER] and [COUNTRY OR POSTAL ADDRESS]. It stops if any other
[PLACEHOLDER] is left, and it lists the CHECK notes still open in the sources.

It never deletes anything in the output folder. Files there that this build didn't write are listed
at the end, to remove by hand if they're stale.

Needs only the Python standard library. This script is code under the project's AGPL licence; the
pages it publishes are CC BY 4.0, and the logo files are covered by TRADEMARKS.md.
"""
import argparse
import datetime as dt
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
PAGES = ["index", "privacy", "terms", "delete-account"]
LOGOS = ["logo-full.svg", "logo-full-dark.svg", "logo-mark.svg"]

COMMENT = re.compile(r"<!--.*?-->\n?", re.S)
PLACEHOLDER = re.compile(r"\[[A-Z][A-Z0-9]*(?:[ \-–][A-Z0-9]+)*\]")
BN_DIGITS = str.maketrans("0123456789", "০১২৩৪৫৬৭৮৯")
BN_MONTHS = ["জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট",
             "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"]


def long_date(d: dt.date, lang: str) -> str:
    if lang == "bn":
        return f"{d.day} {BN_MONTHS[d.month - 1]} {d.year}".translate(BN_DIGITS)
    return f"{d.day} {d.strftime('%B')} {d.year}"


def open_checks(text: str) -> list[str]:
    out = []
    for c in re.findall(r"<!--(.*?)-->", text, re.S):
        if c.strip().startswith("CHECK"):
            first = " ".join(c.split())
            out.append(first[:110] + ("…" if len(first) > 110 else ""))
    return out


def publish_page(text: str, values: dict[str, str]) -> str:
    text = COMMENT.sub("", text)
    head, sep, body = text.partition("\n---\n")
    if not text.startswith("---\n") or not sep:
        raise SystemExit("a page has no front matter")
    head = "\n".join(l for l in head.splitlines() if not l.startswith("status:"))
    text = head + sep + body
    for key, value in values.items():
        if value:
            text = text.replace(key, value)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.lstrip("\n")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("out", type=pathlib.Path, help="the website repo's folder")
    ap.add_argument("--date", required=True, help="effective date of the Privacy Policy and Terms, YYYY-MM-DD")
    ap.add_argument("--publisher", default="")
    ap.add_argument("--address", default="")
    ap.add_argument("--address-bn", default="")
    ap.add_argument("--released", action="store_true")
    ap.add_argument("--allow-placeholders", action="store_true")
    a = ap.parse_args()

    out = a.out.resolve()
    if out == REPO or REPO in out.parents:
        raise SystemExit(f"refusing to build inside the app repo: {out}")
    date = dt.date.fromisoformat(a.date)

    sources: dict[pathlib.Path, pathlib.Path] = {}
    for lang, prefix in (("en", ""), ("bn", "bn/")):
        for name in PAGES:
            sources[pathlib.Path(prefix + name + ".md")] = HERE / lang / (name + ".md")
        sources[pathlib.Path(prefix + "download.md")] = (
            HERE / lang / "download.md" if a.released else HERE / "site" / "prerelease" / lang / "download.md")

    files: dict[pathlib.Path, str | bytes] = {}
    problems: list[str] = []

    def write(rel: pathlib.Path, data: str | bytes) -> None:
        files[rel] = data

    print("Open CHECK notes in the sources (they don't reach the site; resolve them before relying on the text):")
    for rel, src in sources.items():
        text = src.read_text(encoding="utf-8")
        lang = "bn" if rel.parts[0] == "bn" else "en"
        for c in open_checks(text):
            print(f"  {src.relative_to(HERE)}: {c}")
        page = publish_page(text, {
            "[EFFECTIVE DATE]": long_date(date, lang),
            "[PUBLISHER]": a.publisher,
            "[COUNTRY OR POSTAL ADDRESS]": (a.address_bn or a.address) if lang == "bn" else a.address,
        })
        left = sorted(set(PLACEHOLDER.findall(page)))
        if left:
            problems.append(f"{rel}: {', '.join(left)}")
        write(rel, page)

    config = (HERE / "site" / "_config.yml").read_text(encoding="utf-8")
    config = re.sub(r"(?m)^released: \w+$", f"released: {'true' if a.released else 'false'}", config)
    write(pathlib.Path("_config.yml"), config)
    for rel in ("_layouts/default.html", "assets/site.css", "404.md", "README.md"):
        write(pathlib.Path(rel), (HERE / "site" / rel).read_text(encoding="utf-8"))
    for logo in LOGOS:
        write(pathlib.Path("assets") / logo, (REPO / "branding" / logo).read_bytes())

    if problems:
        print("\nPlaceholders without a value:")
        for p in problems:
            print("  " + p)
        if not a.allow_placeholders:
            raise SystemExit("not ready to publish, nothing written: give the missing values "
                             "(or --allow-placeholders for a preview)")

    for rel, data in files.items():
        dest = out / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, bytes):
            dest.write_bytes(data)
        else:
            dest.write_text(data, encoding="utf-8", newline="\n")
    stale = sorted(p.relative_to(out) for p in out.rglob("*")
                   if p.is_file() and ".git" not in p.relative_to(out).parts and p.relative_to(out) not in files)
    print(f"\nWrote {len(files)} files to {out}")
    for p in stale:
        print(f"  not written by this build (stale?): {p.as_posix()}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
