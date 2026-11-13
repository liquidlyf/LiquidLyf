// Config wiring: the crons in wrangler.jsonc match the handler, each runs its job, and the
// generated neighbours map matches the app's bd-geo.json.
import { readFileSync } from "node:fs";
import { afterEach, describe, expect, test, vi } from "vitest";
import worker, { CRONS } from "../src/index";
import neighbours from "../../shared/district-neighbours.json";
import { fakeGoogle, makeEnv, resetCaches } from "./helpers";

const stripJsonc = (s: string) => s.replace(/\/\/.*$/gm, "").replace(/,(\s*[}\]])/g, "$1");

describe("crons", () => {
  afterEach(() => vi.unstubAllGlobals());

  test("wrangler.jsonc triggers are exactly the handler's crons", () => {
    const config = JSON.parse(stripJsonc(readFileSync(new URL("../wrangler.jsonc", import.meta.url), "utf8")));
    expect([...config.triggers.crons].sort()).toEqual(Object.values(CRONS).sort());
  });

  test.each([[CRONS.sweep, "requests", "nextTierAt"], [CRONS.expiry, "requests", "expiresAt"], [CRONS.retention, "requests", "updatedAt"]])(
    "%s runs its job", async (cron, collection, field) => {
      resetCaches();
      const calls = await fakeGoogle();
      await worker.scheduled({ cron, scheduledTime: Date.parse("2026-11-20T03:00:00Z") }, await makeEnv());
      expect(calls.queries[0].from[0].collectionId).toBe(collection);
      expect(JSON.stringify(calls.queries[0].where)).toContain(`"${field}"`);
    });

  test("an unknown cron does nothing", async () => {
    resetCaches();
    const calls = await fakeGoogle();
    await worker.scheduled({ cron: "1 1 1 1 1", scheduledTime: Date.now() }, await makeEnv());
    expect(calls.queries).toHaveLength(0);
  });
});

describe("district neighbours (tier 4)", () => {
  test("backend/shared/district-neighbours.json matches app/src/main/assets/bd-geo.json (else: npm run gen:neighbours)", () => {
    const geo = JSON.parse(readFileSync(new URL("../../../app/src/main/assets/bd-geo.json", import.meta.url), "utf8"));
    const expected: Record<string, { division: string; neighbours: string[] }> = {};
    for (const v of geo.divisions) for (const d of v.districts) expected[d.code] = { division: v.code, neighbours: [...(d.neighbours ?? [])].sort() };
    expect(neighbours.districts).toEqual(expected);
    expect(neighbours.geoVersion).toBe(geo.version);
  });

  test("neighbourhood is mutual and every neighbour is a real district", () => {
    const d = neighbours.districts as Record<string, { neighbours: string[] }>;
    for (const [code, { neighbours: ns }] of Object.entries(d)) {
      for (const n of ns) {
        expect(d[n], `${code} -> ${n}`).toBeDefined();
        expect(d[n].neighbours, `${n} should list ${code}`).toContain(code);
      }
    }
  });
});
