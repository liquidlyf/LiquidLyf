"""Builds app/src/main/assets/bd-geo.json, the bundled list of Bangladesh areas (Phase 2.4).

Sources (decided by the maintainer, A6 = a, 13 Nov 2026):
  * Codes, English names and boundaries: Bangladesh Bureau of Statistics (BBS) via UN OCHA HDX,
    "Bangladesh - Subnational Administrative Boundaries" (cod-ab-bgd), licence CC BY-IGO.
    https://data.humdata.org/dataset/cod-ab-bgd  -> download bgd_admin_boundaries.geojson.zip
  * Bangla names: ifahimreza/bangladesh-geojson, src/data/*.json (MIT).
    https://github.com/ifahimreza/bangladesh-geojson
  * Bangla names that match nothing there, or that it misspells: app/scripts/bd_geo_bn_overrides.json,
    written by us and checked by the maintainer (16 Nov 2026). Codes in its "_needsReview" list are
    marked bnNeedsReview in the output until a native speaker checks them.
  * English names the BBS list misspells or has outdated: EN_OVERRIDES below.

Neighbouring districts (tier 4 of the fan-out) are computed from the HDX district polygons: two
districts are neighbours when their borders share at least two points.

Usage (download and unzip the sources outside the repo first):
  python -I app/scripts/build_bd_geo.py <hdx-geojson-dir> <mit-data-dir> [--report]
Needs only the Python standard library.
"""
import difflib
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/bd-geo.json"
OVERRIDES = pathlib.Path(__file__).with_name("bd_geo_bn_overrides.json")

# District names the MIT list spells differently from HDX (after normalise()). Districts only:
# "Nawabganj" is also an upazila in Dhaka and Dinajpur.
DISTRICT_ALIASES = {
    "nawabganj": "chapainababganj", "chapainawabganj": "chapainababganj",
    "chittagong": "chattogram", "barisal": "barishal", "comilla": "cumilla", "jessore": "jashore",
    "bogra": "bogura", "jhalakathi": "jhalokati", "maulvibazar": "moulvibazar",
    "netrakona": "netrokona", "kishorgonj": "kishoreganj",
}

# English names that replace the BBS spelling, each checked against the upazila's own portal
# (16 Nov 2026). The code never changes, so saved profiles and requests just show the new name.
EN_OVERRIDES = {
    "BD10790090": "Zianagar",  # BBS "Indurkani" (ইন্দুরকানী), renamed back in 2026: https://zianagar.pirojpur.gov.bd/
    "BD30930019": "Bhuapur",  # BBS "Bhuanpur", a typo: https://bhuapur.tangail.gov.bd/
}


def closest(key, candidates):
    """The candidate key for [key]: exact, else the closest spelling, else None."""
    if key in candidates:
        return key
    close = difflib.get_close_matches(key, list(candidates), n=1, cutoff=0.84)
    return close[0] if close else None

CITY = re.compile(r"\bCity Corporation\b")


def normalise(name: str) -> str:
    return re.sub(r"[^a-z]", "", name.lower().replace("sadar", ""))


def district_key(name: str) -> str:
    n = normalise(name)
    return DISTRICT_ALIASES.get(n, n)


def load(path):
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))


def features(hdx_dir, level):
    return load(pathlib.Path(hdx_dir) / f"bgd_admin{level}.geojson")["features"]


def rings(geometry):
    polys = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
    for poly in polys:
        yield from poly


def neighbours_of(districts):
    """District pcode -> sorted pcodes of districts sharing a border."""
    points = {}
    for f in districts:
        code = f["properties"]["adm2_pcode"]
        points[code] = {(round(x, 5), round(y, 5)) for ring in rings(f["geometry"]) for x, y in ring}
    result = {}
    for a in points:
        result[a] = sorted(b for b in points if b != a and len(points[a] & points[b]) >= 2)
    return result


def bangla_index(mit_dir):
    mit = pathlib.Path(mit_dir)
    divisions = load(mit / "bd-divisions.json")["divisions"]
    districts = load(mit / "bd-districts.json")["districts"]
    upazilas = load(mit / "bd-upazilas.json")["upazilas"]
    div = {normalise(d["name"]): d["bn_name"].strip() for d in divisions}
    dis = {district_key(d["name"]): (d["id"], d["bn_name"].strip()) for d in districts}
    upa = {}
    for u in upazilas:
        upa.setdefault(u["district_id"], {})[normalise(u["name"])] = u["bn_name"].strip()
    return div, dis, upa


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    hdx_dir, mit_dir, report = sys.argv[1], sys.argv[2], "--report" in sys.argv
    overrides_file = load(OVERRIDES)
    overrides = {k: v for k, v in overrides_file.items() if not k.startswith("_")}
    needs_review = set(overrides_file.get("_needsReview", []))
    if needs_review - set(overrides):
        raise SystemExit(f"_needsReview lists codes without a name: {sorted(needs_review - set(overrides))}")
    bn_div, bn_dis, bn_upa = bangla_index(mit_dir)
    missing, fuzzy, used_overrides = [], [], set()

    def area(code, name_en, key, candidates, **extra):
        """An area entry. Bangla: our override, else the MIT name (exact, then close spelling)."""
        entry = {"code": code, "nameEn": EN_OVERRIDES.get(code, name_en)}
        match = closest(key, candidates)
        if code in overrides:
            used_overrides.add(code)
            entry["nameBn"] = overrides[code]
            if code in needs_review:
                entry["bnNeedsReview"] = True
        elif match is not None:
            if match != key:
                fuzzy.append((code, name_en, match, candidates[match]))
            entry["nameBn"] = candidates[match]
        else:
            missing.append((code, name_en))
        return entry | extra

    adm2 = features(hdx_dir, 2)
    neighbours = neighbours_of(adm2)
    divisions = {}
    for f in features(hdx_dir, 1):
        p = f["properties"]
        divisions[p["adm1_pcode"]] = area(p["adm1_pcode"], p["adm1_name"], normalise(p["adm1_name"]), bn_div,
                                          districts=[])
    district_by_code, mit_district_id = {}, {}
    district_bn = {k: v[1] for k, v in bn_dis.items()}
    for f in sorted(adm2, key=lambda f: f["properties"]["adm2_name"]):
        p = f["properties"]
        key = district_key(p["adm2_name"])
        d = area(p["adm2_pcode"], p["adm2_name"], key, district_bn,
                 neighbours=neighbours[p["adm2_pcode"]], upazilas=[])
        divisions[p["adm1_pcode"]]["districts"].append(d)
        district_by_code[d["code"]] = d
        match = closest(key, district_bn)
        mit_district_id[d["code"]] = bn_dis[match][0] if match else None
    adm3 = features(hdx_dir, 3)
    for f in sorted(adm3, key=lambda f: EN_OVERRIDES.get(f["properties"]["adm3_pcode"], f["properties"]["adm3_name"])):
        p = f["properties"]
        is_city = bool(CITY.search(p["adm3_name"]))
        candidates = {} if is_city else bn_upa.get(mit_district_id[p["adm2_pcode"]], {})
        district_by_code[p["adm2_pcode"]]["upazilas"].append(
            area(p["adm3_pcode"], p["adm3_name"], normalise(p["adm3_name"]), candidates,
                 type="CITY_CORPORATION" if is_city else "UPAZILA"))
    unused = set(overrides) - used_overrides
    if unused:
        raise SystemExit(f"overrides for unknown codes: {sorted(unused)}")
    unknown_en = set(EN_OVERRIDES) - {f["properties"]["adm3_pcode"] for f in adm3}
    if unknown_en:
        raise SystemExit(f"EN_OVERRIDES for unknown codes: {sorted(unknown_en)}")

    for d in divisions.values():
        d["districts"].sort(key=lambda x: x["nameEn"])
    out = {
        "version": 1,
        "source": "Bangladesh Bureau of Statistics (BBS), via UN OCHA HDX cod-ab-bgd (2026-01-26), CC BY-IGO; "
                  f"{len(EN_OVERRIDES)} English names corrected from the upazilas' own gov.bd portals",
        "bnSource": "ifahimreza/bangladesh-geojson (MIT), plus LiquidLyf's own names where it has none or "
                    "misspells one, checked by the maintainer; bnNeedsReview marks any not yet checked",
        "divisions": sorted(divisions.values(), key=lambda x: x["nameEn"]),
    }
    if report:
        print("fuzzy matches (check these):")
        for row in fuzzy:
            print("  ", row)
        print("missing Bangla names (add to bd_geo_bn_overrides.json):")
        for row in missing:
            print("  ", row)
        lonely = [c for c, n in neighbours.items() if not n]
        print("districts without neighbours:", lonely)
        return
    if missing:
        raise SystemExit(f"{len(missing)} areas have no Bangla name; run with --report")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8", newline="\n")
    areas = sum(len(d["upazilas"]) for v in out["divisions"] for d in v["districts"])
    print(f"wrote {OUT.relative_to(ROOT)}: {len(divisions)} divisions, {len(district_by_code)} districts, "
          f"{areas} upazilas/cities, {len(fuzzy)} close-spelling matches, {len(overrides)} of our own names")


if __name__ == "__main__":
    main()
