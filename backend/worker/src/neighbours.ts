// District -> neighbouring districts, for tier 4 (doc 10 §6.3). Generated from the app's
// bd-geo.json by scripts/gen-neighbours.mjs; test/neighbours.test.ts keeps them in step.
import data from "../../shared/district-neighbours.json";

const districts = data.districts as Record<string, { division: string; neighbours: string[] }>;

/** Neighbouring district codes; empty for an unknown district (tier 4 then covers only its own district). */
export function neighboursOf(district: string): string[] {
  return districts[district]?.neighbours ?? [];
}
