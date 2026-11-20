"""Builds the liquidlyf.github.io website from the drafts in this folder.

The drafts stay the single source: a pull request that changes the app's data practices changes the
Privacy Policy here too, and this script publishes the result into a clone of the website repo.

Usage, from the app repo's root:

    python docs/website-drafts/build_site.py ../liquidlyf.github.io --date 2026-11-15 \
        --publisher "the LiquidLyf maintainers" --publisher-bn "LiquidLyf-এর মেইনটেইনাররা" \
        --address "Bangladesh" --address-bn "বাংলাদেশ"

Options:
    --released            v1.0 is on GitHub Releases: publish the full download page and the home
                          page's download button. Without it, /download says "coming soon".
    --allow-placeholders  for a preview only: keep any [PLACEHOLDER] that has no value.

What it does to each page: removes HTML comments (the CHECK notes and other notes for maintainers,
which must never reach the public page), removes the draft-only `status:` front-matter line, and
fills [EFFECTIVE DATE], [PUBLISHER] and [COUNTRY OR POSTAL ADDRESS]. Where a page has [CONTENTS], it
puts a list of the page's numbered sections ("## 1. …") there, gives each of them an anchor (#s1,
#s2…, the same in both languages) and a "Back to contents" link at its end. It writes Markdown tables
as HTML tables, so that a table with 3 or more columns can become one labelled card per row on a
phone. It stops if any other [PLACEHOLDER] is left, and it lists the CHECK notes still open in the
sources.

It also writes sitemap.xml (each page with its other-language version) and robots.txt, and puts a
hash of site.css into _config.yml (css_version), so a browser never keeps an old stylesheet, and
copies site/workflows/yearly-rebuild.yml to .github/workflows/: a 1 January rebuild, so the footer's
copyright year (the year of the build) moves on even if nothing else was published.

It publishes the hospital list (plan D14) as data/hospitals.json for the app's daily refresh: the
committed app/src/main/assets/hospitals.json, read from git so the bytes are the committed ones even
where Git checks files out with CRLF. It stops if that list isn't valid, or if it would replace the
website's list with one the phones ignore or refuse: a changed list whose version didn't go up, fewer
than 80% as many hospitals, or over 2 MiB. Until the list is committed, it says so and skips it.

It copies the request-link page (D31): site/r/index.html, with its own layout (site/_layouts/
request.html), the only one whose Content-Security-Policy allows a script, /assets/r.js (C25 = a). The
page isn't in the sitemap and asks not to be indexed. Once the release key's SHA-256 is in the committed
.github/ci/release-cert-sha256.txt (A48), it writes .well-known/assetlinks.json from it, so Android
opens /r/ links in the app; while that file says NOT-SET, it says so and skips it.

It never deletes anything in the output folder. Files there that this build didn't write are listed
at the end, to remove by hand if they're stale.

Needs only the Python standard library, and git. This script is code under the project's AGPL licence; the
pages it publishes are CC BY 4.0, and the logo files are covered by TRADEMARKS.md.
"""
import argparse
import datetime as dt
import hashlib
import html
import json
import pathlib
import re
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
# The home page is HTML (Liquid, no Markdown); the others are Markdown.
PAGES = ["index.html", "privacy.md", "terms.md", "delete-account.md"]
LOGOS = ["logo-full.svg", "logo-full-dark.svg", "logo-mark.svg", "logo-horizontal.svg",
         "logo-horizontal-dark.svg"]
# The hospital list the app bundles (A copies it from B's firebase/seed/hospitals.json), and where the
# website serves it. The phones' own rules are in the app's HospitalRepository.kt (HospitalListRules)
# and HospitalLists.kt (MAX_BYTES).
HOSPITALS = "app/src/main/assets/hospitals.json"
HOSPITALS_URL_PATH = pathlib.Path("data/hospitals.json")
HOSPITALS_MAX_BYTES = 2 * 1024 * 1024
# The release key's certificate SHA-256 (A's file, the one release.yml checks), and the file that
# tells Android the request links (/r/, D31) belong to the app. NOT-SET until the key exists (A48).
RELEASE_CERT = ".github/ci/release-cert-sha256.txt"
ASSETLINKS_URL_PATH = pathlib.Path(".well-known/assetlinks.json")
APP_ID = "io.github.liquidlyf"

COMMENT = re.compile(r"<!--.*?-->\n?", re.S)
PLACEHOLDER = re.compile(r"\[[A-Z][A-Z0-9]*(?:[ \-–][A-Z0-9]+)*\]")
BN_DIGITS = str.maketrans("0123456789", "০১২৩৪৫৬৭৮৯")
ASCII_DIGITS = str.maketrans("০১২৩৪৫৬৭৮৯", "0123456789")
SECTION = re.compile(r"(?m)^## (([0-9০-৯]+)\. .+?)[ \t]*$")
TABLE_ROW = re.compile(r"^\|.*\|[ \t]*$")
TABLE_RULE = re.compile(r"^\|(?:[ \t]*:?-{3,}:?[ \t]*\|)+[ \t]*$")
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


def add_contents(text: str, lang: str) -> str:
    """Replaces [CONTENTS] with a list of the numbered sections, gives each one an anchor, and ends
    each section with a "Back to contents" link."""
    if "[CONTENTS]" not in text:
        return text
    items = []
    back = "সূচিতে ফিরে যান" if lang == "bn" else "Back to contents"
    back_link = f'<p class="to-contents"><a href="#contents">{back}</a></p>\n\n'

    def heading(m: re.Match) -> str:
        title = html.escape(m.group(1).replace("'", "’"), quote=False)
        anchor = "s" + m.group(2).translate(ASCII_DIGITS)
        items.append((anchor, title))
        return (back_link if len(items) > 1 else "") + f'<h2 id="{anchor}">{title}</h2>'

    text = SECTION.sub(heading, text)
    if items:
        text = text.rstrip("\n") + "\n\n" + back_link
    label = "এই পাতায় যা আছে" if lang == "bn" else "On this page"
    links = "".join(f'<li><a href="#{a}">{t}</a></li>' for a, t in items)
    return text.replace("[CONTENTS]", f'<nav class="toc" id="contents" aria-label="{label}"><h2>{label}</h2>'
                                      f'<ol role="list">{links}</ol></nav>')


def inline_md(cell: str) -> str:
    """The Markdown that table cells use, as HTML: "quotes", apostrophes, **bold** and [links](url)."""
    s = html.escape(cell.strip(), quote=False)
    s = re.sub(r'"([^"]*)"', r"“\1”", s).replace("'", "’")
    s = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)
    return re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", r'<a href="\2">\1</a>', s)


def table_html(head: list[str], rows: list[list[str]]) -> str:
    """A GFM table as HTML. With 3 or more columns it gets class "stack" (one card per row on a phone),
    each cell the name of its column (data-label), and ARIA roles, which keep it a table for screen
    readers when the CSS changes its display."""
    stack = len(head) >= 3
    t, rg, tr, th, td = (' role="table"', ' role="rowgroup"', ' role="row"', ' role="columnheader"', ' role="cell"') \
        if stack else ("", "", "", "", "")
    labels = [html.escape(re.sub(r"[*_`]", "", h).strip()) for h in head]
    cols = "".join(f'<th scope="col"{th}>{inline_md(h)}</th>' for h in head)
    body = "".join(f"<tr{tr}>" + "".join(
        f'<td{td}' + (f' data-label="{labels[k]}"' if stack else "") + f">{inline_md(c)}</td>"
        for k, c in enumerate(r)) + "</tr>\n" for r in rows)
    cls = ' class="stack"' if stack else ""
    return (f'<div class="table-wrap"><table{cls}{t}>\n<thead{rg}><tr{tr}>{cols}</tr></thead>\n'
            f"<tbody{rg}>\n{body}</tbody></table></div>")


def html_tables(text: str) -> str:
    """Replaces each GFM pipe table with table_html()."""
    def cells(line: str) -> list[str]:
        return [c.strip() for c in line.strip().strip("|").split("|")]

    lines, out, i = text.split("\n"), [], 0
    while i < len(lines):
        if TABLE_ROW.match(lines[i]) and i + 1 < len(lines) and TABLE_RULE.match(lines[i + 1]):
            head, j, rows = cells(lines[i]), i + 2, []
            while j < len(lines) and TABLE_ROW.match(lines[j]):
                rows.append(cells(lines[j]))
                j += 1
            if any(len(r) != len(head) for r in rows):
                raise SystemExit(f"a table row has the wrong number of cells: {lines[i]}")
            out.append(table_html(head, rows))
            i = j
        else:
            out.append(lines[i])
            i += 1
    return "\n".join(out)


def front(page: str, key: str) -> str:
    m = re.search(rf"(?m)^{key}:[ \t]*(.+?)[ \t]*$", page.partition("\n---\n")[0])
    return m.group(1).strip('"') if m else ""


def sitemap(site: str, pages: list[tuple[str, str, str]]) -> str:
    """sitemap.xml: every page, with its English, Bangla and default (English) versions."""
    rows = []
    for url, lang, alt in pages:
        en, bn = (url, alt) if lang == "en" else (alt, url)
        links = "".join(f'\n    <xhtml:link rel="alternate" hreflang="{h}" href="{site}{u}"/>'
                        for h, u in (("en", en), ("bn", bn), ("x-default", en)))
        rows.append(f"  <url>\n    <loc>{site}{url}</loc>{links}\n  </url>")
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n'
            + "\n".join(rows) + "\n</urlset>\n")


def publish_page(text: str, values: dict[str, str], lang: str = "en") -> str:
    text = COMMENT.sub("", text)
    head, sep, body = text.partition("\n---\n")
    if not text.startswith("---\n") or not sep:
        raise SystemExit("a page has no front matter")
    head = "\n".join(l for l in head.splitlines() if not l.startswith("status:"))
    text = head + sep + body
    for key, value in values.items():
        if value:
            text = text.replace(key, value)
    text = add_contents(text, lang)
    text = html_tables(text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.lstrip("\n")


def parse_hospitals(data: bytes, name: str) -> dict:
    """The hospital list in `data`, or stop if it isn't one."""
    try:
        d = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise SystemExit(f"{name} isn't valid UTF-8 JSON: {e}")
    version = d.get("version") if isinstance(d, dict) else None
    if not (type(version) is int and version >= 1 and d.get("licence") == "ODbL-1.0"
            and isinstance(d.get("hospitals"), list) and d["hospitals"]):
        raise SystemExit(f'{name} isn\'t a hospital list: it needs "version" (1 or more), '
                         f'"licence": "ODbL-1.0" and a non-empty "hospitals" list')
    return d


def hospital_list(out: pathlib.Path) -> bytes | None:
    """The committed hospital list, checked against the website's current one; None if not committed yet."""
    try:
        got = subprocess.run(["git", "-C", str(REPO), "cat-file", "blob", f"HEAD:{HOSPITALS}"],
                             capture_output=True)
    except FileNotFoundError:
        raise SystemExit("git isn't on PATH: it's needed to read the committed hospital list")
    if got.returncode != 0:
        print(f"\nThe hospital list isn't committed yet ({HOSPITALS}): {HOSPITALS_URL_PATH.as_posix()} not written.")
        return None
    data = got.stdout
    if len(data) > HOSPITALS_MAX_BYTES:
        raise SystemExit(f"{HOSPITALS} is {len(data)} bytes: phones refuse a list over {HOSPITALS_MAX_BYTES}")
    new = parse_hospitals(data, HOSPITALS)
    local = REPO / HOSPITALS
    if local.exists() and local.read_bytes().replace(b"\r\n", b"\n") != data:
        print(f"\nNote: {HOSPITALS} has uncommitted changes. The committed version is published.")
    current = out / HOSPITALS_URL_PATH
    if current.exists() and current.read_bytes().replace(b"\r\n", b"\n") != data:
        old = parse_hospitals(current.read_bytes(), f"the website's {HOSPITALS_URL_PATH.as_posix()}")
        if new["version"] <= old["version"]:
            raise SystemExit(f"the hospital list changed but its version didn't go up ({old['version']} → "
                             f"{new['version']}): phones would keep their copy. Raise the version in the CSV (B)")
        if len(new["hospitals"]) * 5 < len(old["hospitals"]) * 4:
            raise SystemExit(f"the new hospital list has {len(new['hospitals'])} hospitals, under 80% of the "
                             f"website's {len(old['hospitals'])}: phones would refuse it")
    print(f"\nHospital list: version {new['version']}, {len(new['hospitals'])} hospitals.")
    return data


def committed(path: str) -> bytes | None:
    """A file as committed in the app repo (HEAD), or None if it isn't."""
    try:
        got = subprocess.run(["git", "-C", str(REPO), "cat-file", "blob", f"HEAD:{path}"], capture_output=True)
    except FileNotFoundError:
        raise SystemExit(f"git isn't on PATH: it's needed to read the committed {path}")
    return got.stdout if got.returncode == 0 else None


def parse_fingerprint(text: str) -> str | None:
    """The certificate SHA-256 in A's file, as AA:BB:…; None while it says NOT-SET; stop if it's neither."""
    lines = [l.strip() for l in text.splitlines() if l.strip() and not l.lstrip().startswith("#")]
    if lines == ["NOT-SET"]:
        return None
    digits = lines[0].replace(":", "").replace(" ", "") if len(lines) == 1 else ""
    if not re.fullmatch(r"[0-9A-Fa-f]{64}", digits):
        raise SystemExit(f"{RELEASE_CERT} needs one SHA-256 (64 hex digits, with or without colons) or NOT-SET")
    digits = digits.upper()
    return ":".join(digits[i:i + 2] for i in range(0, 64, 2))


def assetlinks(fingerprint: str) -> str:
    """Digital Asset Links: the app with this signing certificate may open this site's links (App Links)."""
    return json.dumps([{
        "relation": ["delegate_permission/common.handle_all_urls"],
        "target": {"namespace": "android_app", "package_name": APP_ID,
                   "sha256_cert_fingerprints": [fingerprint]},
    }], indent=2) + "\n"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("out", type=pathlib.Path, help="the website repo's folder")
    ap.add_argument("--date", required=True, help="effective date of the Privacy Policy and Terms, YYYY-MM-DD")
    ap.add_argument("--publisher", default="")
    ap.add_argument("--publisher-bn", default="", help="the publisher in Bangla (default: --publisher)")
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
            sources[pathlib.Path(prefix + name)] = HERE / lang / name
        sources[pathlib.Path(prefix + "download.md")] = (
            HERE / lang / "download.md" if a.released else HERE / "site" / "prerelease" / lang / "download.md")

    files: dict[pathlib.Path, str | bytes] = {}
    problems: list[str] = []
    urls: list[tuple[str, str, str]] = []

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
            "[PUBLISHER]": (a.publisher_bn or a.publisher) if lang == "bn" else a.publisher,
            "[COUNTRY OR POSTAL ADDRESS]": (a.address_bn or a.address) if lang == "bn" else a.address,
        }, lang)
        left = sorted(set(PLACEHOLDER.findall(page)))
        if left:
            problems.append(f"{rel}: {', '.join(left)}")
        write(rel, page)
        urls.append((front(page, "permalink"), lang, front(page, "alternate")))

    config = (HERE / "site" / "_config.yml").read_text(encoding="utf-8")
    config = re.sub(r"(?m)^released: \w+$", f"released: {'true' if a.released else 'false'}", config)
    css = hashlib.sha256((HERE / "site" / "assets" / "site.css").read_bytes()).hexdigest()[:10]
    config, n = re.subn(r"(?m)^css_version: \S+$", f"css_version: {css}", config)
    if n != 1:
        raise SystemExit("site/_config.yml needs a css_version: line")
    write(pathlib.Path("_config.yml"), config)
    site_url = re.search(r"(?m)^url: (\S+)$", config).group(1)
    write(pathlib.Path("sitemap.xml"), sitemap(site_url, urls))
    write(pathlib.Path("robots.txt"), f"# Every page may be crawled. The request-link page (/r/) and the 404 page\n"
                                      f"# ask not to be indexed (noindex), and aren't in the sitemap.\n"
                                      f"User-agent: *\nDisallow:\n\nSitemap: {site_url}/sitemap.xml\n")
    # The request-link page (D31) has its own layout: the only one that allows a script (C25 = a).
    for rel in ("_layouts/default.html", "_layouts/request.html", "r/index.html", "404.md", "README.md"):
        write(pathlib.Path(rel), (HERE / "site" / rel).read_text(encoding="utf-8"))
    # The 1 January rebuild that moves the footer's copyright year (C15).
    write(pathlib.Path(".github/workflows/yearly-rebuild.yml"),
          (HERE / "site" / "workflows" / "yearly-rebuild.yml").read_text(encoding="utf-8"))
    # Everything in site/assets: the stylesheet, the icons, the share images and the PNG icons.
    assets = HERE / "site" / "assets"
    for f in sorted(assets.rglob("*")):
        rel = f.relative_to(assets)
        # The app screenshots appear only after the release (the maintainer, 16 Nov 2026).
        if f.is_file() and (a.released or rel.parts[0] != "screens"):
            write(pathlib.Path("assets") / rel, f.read_bytes())
    for logo in LOGOS:
        write(pathlib.Path("assets") / logo, (REPO / "branding" / logo).read_bytes())
    # The hospital list for the app's daily refresh (D14), unchanged. No page links to it.
    hospitals = hospital_list(out)
    if hospitals is not None:
        write(HOSPITALS_URL_PATH, hospitals)
    # App Links for /r/ (D31), from the committed fingerprint file, so the site and release.yml agree.
    cert = committed(RELEASE_CERT)
    fingerprint = parse_fingerprint(cert.decode("utf-8")) if cert is not None else None
    if fingerprint is None:
        print(f"\nThe release key's fingerprint isn't set yet ({RELEASE_CERT}, A48): "
              f"{ASSETLINKS_URL_PATH.as_posix()} not written, so Android opens /r/ in the browser.")
    else:
        write(ASSETLINKS_URL_PATH, assetlinks(fingerprint))
        print(f"\nApp Links: {ASSETLINKS_URL_PATH.as_posix()} for {APP_ID}, {fingerprint[:8]}…")

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
