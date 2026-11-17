"""Writes update.json for a GitHub release (Phase 9.4; the format is in core/data/update/AppUpdate.kt).

    python app/scripts/make_update_json.py --apk LiquidLyf.apk --version-code 12 --version-name 1.0.0 \\
        --tag v1.0.0 [--rollout 100] [--min-supported 0] [--out update.json]

The app downloads it from releases/latest/download/update.json, which follows the newest stable
(non-pre-release) release. apkUrl points at this tag's own APK, so its sha256 always matches.
"""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

RELEASES = "https://github.com/liquidlyf/LiquidLyf/releases"


def make(apk: Path, version_code: int, version_name: str, tag: str, rollout: int, min_supported: int) -> dict:
    if version_code <= 0:
        sys.exit("--version-code must be a positive integer")
    if not 0 <= rollout <= 100:
        sys.exit("--rollout must be 0-100")
    if not 0 <= min_supported <= version_code:
        sys.exit("--min-supported must be 0..version-code")
    if not re.fullmatch(r"v[0-9][0-9A-Za-z.\-]*", tag):
        sys.exit(f"--tag {tag!r} doesn't look like a release tag (v1.0.0)")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    return {
        "versionCode": version_code,
        "versionName": version_name,
        "apkUrl": f"{RELEASES}/download/{tag}/{apk.name}",
        "sha256": digest,
        "notesUrl": f"{RELEASES}/tag/{tag}",
        "rolloutPercent": rollout,
        "minSupportedVersionCode": min_supported,
        "apkSizeBytes": apk.stat().st_size,
    }


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--apk", required=True, type=Path)
    p.add_argument("--version-code", required=True, type=int)
    p.add_argument("--version-name", required=True)
    p.add_argument("--tag", required=True)
    p.add_argument("--rollout", type=int, default=100)
    p.add_argument("--min-supported", type=int, default=0)
    p.add_argument("--out", type=Path, default=Path("update.json"))
    a = p.parse_args()
    info = make(a.apk, a.version_code, a.version_name, a.tag, a.rollout, a.min_supported)
    a.out.write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    print(a.out.read_text(encoding="utf-8"), end="")


if __name__ == "__main__":
    main()
