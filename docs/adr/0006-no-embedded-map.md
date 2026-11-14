# 0006: No embedded map in v1

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

The university version embedded a Google Map to pick a location. Its API key ended up public on
GitHub, the Maps SDK can bill once usage grows, and several crashes and wrong-location bugs came from
the map screen. In Bangladesh, people describe places by district, upazila or thana and hospital, not
by a pin on a map.

## Options considered

1. **Keep an embedded Google Map:** familiar, but it needs a protected API key, may cost money, adds
   download size and brings back a class of bugs.
2. **An open map library (for example OpenStreetMap tiles):** no Google key, but still extra size and
   work, and tile servers have usage limits.
3. **No map in v1:** pick the area from lists, pick the hospital from a curated list, and open the
   phone's maps app when someone needs directions.

## Decision

v1 has **no embedded map**:
- areas come from a bundled list of divisions, districts, and upazilas or city corporations (English
  and Bangla; see [0015](0015-area-data.md));
- hospitals come from a curated list, with "Other" as free text;
- "Open in Maps" opens the phone's own maps app with a link or a `geo:` intent, which needs **no API key**.

## Consequences

- **Good:** no API key to leak, no Maps bill, a smaller app, and fewer screens that can crash.
  Matching by area works without any location permission.
- **Bad, or trade-offs:** no "nearest first" sorting by distance in v1.
- **Follow-ups:**
  - v1.1 may add optional approximate location (foreground only) for sorting by distance;
  - if a map is ever added, use a new, restricted key kept out of git;
  - the hospital list must be seeded and kept up to date for each launch district.
