// Tiered fan-out over FCM topics (doc 06 §4.3, doc 10 §6.3). Pure functions.
// Mirrors core/domain/Tiers.kt; both are tested against backend/shared/tier-vectors.json.
import compat from "../../shared/blood-compatibility.json";

export type Group = string;
export interface TierRequest {
  bloodGroup: Group;
  component: string;
  acceptCompatible: boolean;
  acceptedGroups: Group[];
  division: string;
  district: string;
  upazila?: string | null;
}

export const GROUPS: readonly Group[] = compat.groups;
export const MAX_TOPICS_PER_CONDITION = 5; // FCM limit
const CODE = /^[A-Za-z0-9_-]{1,40}$/;

const isRhNegative = (g: Group) => g.endsWith("_NEG");
const canonical = (gs: Iterable<Group>) => GROUPS.filter((g) => new Set(gs).has(g));

/** Area codes become topic names and FCM conditions: never let anything else through. */
function code(v: unknown, what: string): string {
  if (typeof v !== "string" || !CODE.test(v)) throw new Error(`bad ${what} code`);
  return v;
}

export function topic(level: "up" | "di" | "dv", area: string, group: Group): string {
  if (!GROUPS.includes(group)) throw new Error("bad group");
  return `${level}_${code(area, level)}_${group}`;
}

/** Accepted groups, limited to the compatibility table, in canonical order. */
function accepted(r: TierRequest): Group[] {
  if (!r.acceptCompatible) return [r.bloodGroup];
  const table = (compat.compatibleDonors as Record<string, Record<string, Group[]>>)[r.component];
  const allowed = new Set(table?.[r.bloodGroup] ?? [r.bloodGroup]);
  return canonical([r.bloodGroup, ...r.acceptedGroups.filter((g) => allowed.has(g))]);
}
const others = (r: TierRequest) => accepted(r).filter((g) => g !== r.bloodGroup);

/** The topics one tier sends to. Empty means "nothing to send at this tier". */
export function tierTopics(r: TierRequest, tier: number, neighbours: string[] = []): string[] {
  const hasUpazila = typeof r.upazila === "string" && r.upazila.length > 0;
  const rhNeg = isRhNegative(r.bloodGroup);
  switch (tier) {
    case 1:
      return !rhNeg && hasUpazila ? [topic("up", r.upazila!, r.bloodGroup)] : [];
    case 2:
      return !rhNeg && hasUpazila ? others(r).map((g) => topic("up", r.upazila!, g)) : [];
    case 3:
      return [topic("di", r.district, r.bloodGroup)];
    case 4:
      if (rhNeg) return accepted(r).map((g) => topic("dv", r.division, g));
      return [
        ...others(r).map((g) => topic("di", r.district, g)),
        ...neighbours.filter((d) => d !== r.district)
          .flatMap((d) => accepted(r).map((g) => topic("di", d, g))),
      ];
    default:
      return [];
  }
}

/** FCM conditions, at most 5 topics each. */
export function toConditions(topics: string[]): string[] {
  const out: string[] = [];
  for (let i = 0; i < topics.length; i += MAX_TOPICS_PER_CONDITION) {
    out.push(topics.slice(i, i + MAX_TOPICS_PER_CONDITION).map((t) => `'${t}' in topics`).join(" || "));
  }
  return out;
}

export function tierConditions(r: TierRequest, tier: number, neighbours: string[] = []): string[] {
  return toConditions(tierTopics(r, tier, neighbours));
}

/** The next tier after `after` that sends something, or null when fan-out is finished. */
export function nextTier(r: TierRequest, after: number, neighbours: string[] = []): number | null {
  for (let t = after + 1; t <= 4; t++) if (tierTopics(r, t, neighbours).length) return t;
  return null;
}

/** The first tier to send: 1, or 3 for Rh-negative requests and requests without an upazila. */
export function firstTier(r: TierRequest, neighbours: string[] = []): number | null {
  return nextTier(r, 0, neighbours);
}
