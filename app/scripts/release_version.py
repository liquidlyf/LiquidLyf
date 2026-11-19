"""The app's version from a release tag (Phase 9.2), for release.yml.

    python app/scripts/release_version.py v1.2.3-beta2
    -> version_name=1.2.3-beta2
       version_code=1020302
       prerelease=true

versionCode must grow with every release: Android never installs a lower one, so there's no going
back, only forward. It's made from the tag, so the same tag always gives the same code:

    major * 1_000_000 + minor * 10_000 + patch * 100 + stage

stage: -betaN is N (1-49), -rcN is 50 + N (1-48), and the final release is 99. So every beta and
release candidate of a version comes before the version itself, and a patch comes after both.
Semver's dotted form (-beta.1, -rc.1) works too, and gives the same code:

>>> code("v1.0.0-beta1") < code("v1.0.0-beta2") < code("v1.0.0-rc1") < code("v1.0.0") < code("v1.0.1-beta1")
True
>>> code("v1.0.0"), code("v1.2.3-beta2"), code("v2.0.0-rc3")
(1000099, 1020302, 2000053)
>>> parse("v1.0.0-rc1")
('1.0.0-rc1', 1000051, True)
>>> parse("v1.0.0")
('1.0.0', 1000099, False)
>>> parse("v1.0.0-beta.1")
('1.0.0-beta.1', 1000001, True)
>>> parse("1.0.0")
Traceback (most recent call last):
SystemExit: release tag '1.0.0' must look like v1.2.3, v1.2.3-beta.1 or v1.2.3-rc.1
>>> parse("v1.0.0-beta50")
Traceback (most recent call last):
SystemExit: release tag 'v1.0.0-beta50': a beta number goes from 1 to 49
"""
import re
import sys

TAG = re.compile(r"v(\d{1,3})\.(\d{1,2})\.(\d{1,2})(?:-(beta|rc)\.?(\d{1,2}))?")


def parse(tag: str) -> tuple[str, int, bool]:
    """(versionName, versionCode, is a pre-release) for a tag such as v1.2.3-beta2."""
    m = TAG.fullmatch(tag)
    if not m:
        raise SystemExit(f"release tag {tag!r} must look like v1.2.3, v1.2.3-beta.1 or v1.2.3-rc.1")
    major, minor, patch = (int(x) for x in m.group(1, 2, 3))
    kind, n = m.group(4), m.group(5)
    if kind == "beta":
        if not 1 <= int(n) <= 49:
            raise SystemExit(f"release tag {tag!r}: a beta number goes from 1 to 49")
        stage = int(n)
    elif kind == "rc":
        if not 1 <= int(n) <= 48:
            raise SystemExit(f"release tag {tag!r}: a release candidate number goes from 1 to 48")
        stage = 50 + int(n)
    else:
        stage = 99
    return tag[1:], major * 1_000_000 + minor * 10_000 + patch * 100 + stage, kind is not None


def code(tag: str) -> int:
    return parse(tag)[1]


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__.split(">>>")[0])
    name, version_code, pre = parse(sys.argv[1])
    print(f"version_name={name}\nversion_code={version_code}\nprerelease={'true' if pre else 'false'}")
