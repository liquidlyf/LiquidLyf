import { describe, expect, test } from "vitest";
import vectors from "../../shared/tier-vectors.json";
import { firstTier, nextTier, tierConditions, tierTopics, toConditions, type TierRequest } from "../src/tiers";

describe("shared tier vectors (backend/shared/tier-vectors.json)", () => {
  for (const c of vectors.cases) {
    test(c.name, () => {
      const r = c.request as TierRequest;
      const got: { tier: number; conditions: string[] }[] = [];
      for (let t = firstTier(r, c.neighbours); t !== null; t = nextTier(r, t, c.neighbours)) {
        got.push({ tier: t, conditions: tierConditions(r, t, c.neighbours) });
      }
      expect(got).toEqual(c.tiers);
    });
  }
});

describe("tiers", () => {
  const base: TierRequest = {
    bloodGroup: "B_POS", component: "WHOLE_BLOOD", acceptCompatible: false, acceptedGroups: ["B_POS"],
    division: "V1", district: "D1", upazila: "U1",
  };

  test("every condition has at most 5 topics", () => {
    const topics = Array.from({ length: 12 }, (_, i) => `t${i}`);
    const conds = toConditions(topics);
    expect(conds).toHaveLength(3);
    for (const c of conds) expect(c.split(" || ").length).toBeLessThanOrEqual(5);
  });

  test("area codes that could break out of a condition are rejected", () => {
    expect(() => tierTopics({ ...base, upazila: "U1' in topics || 'x" }, 1)).toThrow();
    expect(() => tierTopics({ ...base, district: "D1/../x" }, 3)).toThrow();
    expect(() => tierTopics({ ...base, bloodGroup: "B+" }, 3)).toThrow();
  });

  test("accepted groups outside the compatibility table are ignored", () => {
    const r = { ...base, acceptCompatible: true, acceptedGroups: ["B_POS", "A_POS", "O_NEG"] };
    expect(tierTopics(r, 2)).toEqual(["up_U1_O_NEG"]);
  });

  test("exact match never sends tier 2", () => {
    expect(tierTopics(base, 2)).toEqual([]);
    expect(nextTier(base, 1)).toBe(3);
  });

  test("tier 4 never repeats the own district's exact group", () => {
    const r = { ...base, acceptCompatible: true, acceptedGroups: ["B_POS", "O_NEG"] };
    expect(tierTopics(r, 4, ["D1", "D2"])).toEqual(["di_D1_O_NEG", "di_D2_B_POS", "di_D2_O_NEG"]);
  });

  test("after tier 4 the fan-out is finished", () => {
    expect(nextTier(base, 4, ["D2"])).toBeNull();
  });
});
