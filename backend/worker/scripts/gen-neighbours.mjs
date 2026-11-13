// Generates backend/shared/district-neighbours.json from the app's area list
// (app/src/main/assets/bd-geo.json, owned by the Android app). The Worker needs only
// district -> its division and neighbouring districts, for tier 4 (doc 10 §6.3).
//
//   npm run gen:neighbours        (test/neighbours.test.ts fails when the two drift apart)
import { readFileSync, writeFileSync } from "node:fs";

const geo = JSON.parse(readFileSync(new URL("../../../app/src/main/assets/bd-geo.json", import.meta.url), "utf8"));
const districts = {};
for (const division of geo.divisions) {
  for (const d of division.districts) {
    districts[d.code] = { division: division.code, neighbours: [...(d.neighbours ?? [])].sort() };
  }
}
const sorted = Object.fromEntries(Object.entries(districts).sort(([a], [b]) => a.localeCompare(b)));
const out = {
  note: "Generated from app/src/main/assets/bd-geo.json by backend/worker/scripts/gen-neighbours.mjs. Don't edit by hand.",
  geoVersion: geo.version,
  districts: sorted,
};
writeFileSync(new URL("../../shared/district-neighbours.json", import.meta.url), JSON.stringify(out, null, 1) + "\n");
console.log(`wrote ${Object.keys(sorted).length} districts`);
