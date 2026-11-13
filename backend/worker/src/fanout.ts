// Shared by dispatch (tier 1), the escalation sweep (later tiers) and the Apps Script backup.
// Pure: no Web APIs.
import type { DocNames } from "./firestore";

/** Fan-out finished (or the request expired): never due again for the sweep. */
export const FAR_FUTURE = new Date("2100-01-01T00:00:00Z");
export const DEFAULT_ESCALATION_MINUTES = 30;

/**
 * Set fanoutTier + nextTierAt only, guarded by the doc's updateTime, so a concurrent or
 * repeated claim loses cleanly. updatedAt stays: fan-out isn't a change the feed must re-download.
 */
export function fanoutWrite(names: Pick<DocNames, "docName">, rid: string, tier: number, nextTierAt: Date, updateTime: string) {
  return {
    update: {
      name: names.docName(`requests/${rid}`),
      fields: {
        fanoutTier: { integerValue: String(tier) },
        nextTierAt: { timestampValue: nextTierAt.toISOString() },
      },
    },
    updateMask: { fieldPaths: ["fanoutTier", "nextTierAt"] },
    currentDocument: { updateTime },
  };
}

/** The topic push. Public data only: anyone can subscribe to a topic (doc 10 §6.3). */
export function requestPushData(rid: string, r: Record<string, any>, tier: number): Record<string, string> {
  return {
    type: "NEW_REQUEST", requestId: rid, bloodGroup: String(r.bloodGroup),
    district: String(r.district), urgency: String(r.urgency), tier: String(tier),
  };
}

export function escalationMinutes(minutes: Record<string, number> | undefined, urgency: unknown): number {
  const m = minutes?.[String(urgency)];
  return typeof m === "number" && m > 0 ? m : DEFAULT_ESCALATION_MINUTES;
}

export const addMinutes = (d: Date, m: number) => new Date(d.getTime() + m * 60_000);
