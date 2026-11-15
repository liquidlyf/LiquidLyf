# 0015: Area list: official BBS codes, with Bangla names added

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

LiquidLyf matches donors and requests by area, not on a map ([0006](0006-no-embedded-map.md)). Every
profile and request stores a division, a district and an upazila or city corporation. The alert
topics are named after those areas too (`up_<area code>_<blood group>`).

So the app needs a list of all of Bangladesh's areas that:
- has **codes that never change**, because they are stored in Firestore and in topic names. A changed
  code would cut donors off from alerts;
- has English and Bangla names;
- has a licence that lets an AGPL app ship it;
- has district borders, to find neighbouring districts for the widest round of alerts (tier 4).

## Options considered

1. **A community-made list only,** such as
   [`ifahimreza/bangladesh-geojson`](https://github.com/ifahimreza/bangladesh-geojson) (MIT): it has
   Bangla names, but it uses its own IDs, not the official codes.
2. **The official list of the Bangladesh Bureau of Statistics (BBS),** as published by UN OCHA's
   [Humanitarian Data Exchange](https://data.humdata.org/dataset/cod-ab-bgd) (HDX, `cod-ab-bgd`,
   CC BY-IGO): official codes, English names and borders. The Bangla names are added from the
   community list where the names match, and translated where they don't.

## Decision

Option 2. The app ships [`app/src/main/assets/bd-geo.json`](../../app/src/main/assets/bd-geo.json),
built by [`app/scripts/build_bd_geo.py`](../../app/scripts/build_bd_geo.py) from:
- **codes, English names and borders:** BBS via HDX `cod-ab-bgd` (CC BY-IGO);
- **Bangla names:** `ifahimreza/bangladesh-geojson` (MIT), matched by name;
- **Bangla names with no match:** LiquidLyf's own, in `app/scripts/bd_geo_bn_overrides.json`. They are
  marked `bnNeedsReview` until a native speaker checks them.

The list has **8 divisions, 64 districts and 507 local areas** (495 upazilas and 12 city
corporations). A city corporation is one area: city thanas aren't listed separately.

The codes are the BBS codes, for example `BD30` (a division), `BD3026` (a district) and `BD30262000`
(an area). Two districts are neighbours when their borders, in the BBS data, share at least two points.

The app credits the data on its About screen, and [LICENSING.md](../../LICENSING.md) lists both
licences.

## Consequences

- **Good:**
  - the codes are the ones the government and aid agencies use, and they stay the same between
    releases;
  - the list ships inside the app, so choosing an area costs no Firestore reads;
  - both licences allow the list in an AGPL app, with credit.
- **Bad, or trade-offs:**
  - 67 Bangla names (1 district and 66 local areas) are LiquidLyf's own translations, not yet
    reviewed;
  - in a city, a donor chooses the whole city corporation, not their thana, so the first round of
    alerts goes to the whole city corporation.
- **Follow-ups:**
  - a native Bangla speaker reviews the 67 names, together with the website's Bangla pages (Phase 8;
    the list is in [docs/translation/bangla-area-names.md](../translation/bangla-area-names.md));
  - the About screen credits "Area data: BBS via UN OCHA HDX (CC BY-IGO)", and the Open-source
    licences screen includes the MIT notice of the Bangla name list;
  - when BBS publishes a new version, rebuild the file with the script. Never change or reuse an
    existing code.
