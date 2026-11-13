// Escalation sweep, cron */5 (phase-05 5.5, doc 10 §6.4). Picks up OPEN requests whose
// nextTierAt has passed and sends the next tier, unless enough donors have already offered.
// Also sends tier 1 for a request whose app never called dispatch (the rules make every new
// request due at once: fanoutTier 0, nextTierAt = create time).
//
// Idempotent: every claim carries the doc's updateTime, so a second sweep (or the Apps Script
// backup, or a late dispatch) loses cleanly and nobody is alerted twice for a tier.
// Free plan: 50 subrequests per invocation, so one run sends at most SEND_BUDGET messages.
import type { Env } from "./env";
import { sendToCondition } from "./fcm";
import { addMinutes, escalationMinutes, FAR_FUTURE, fanoutWrite, requestPushData } from "./fanout";
import { Firestore, fieldsOf } from "./firestore";
import { getAccessToken } from "./google-auth";
import { neighboursOf } from "./neighbours";
import { nextTier, tierConditions, type TierRequest } from "./tiers";

export const SWEEP_LIMIT = 25;       // requests looked at per run (each costs 1 read)
export const SEND_BUDGET = 40;       // FCM calls per run; leaves room for token + query + 2 batches
export const RECHECK_MINUTES = 60;   // enough offers for now: look again later
const ACTIVE = new Set(["OFFERED", "CONFIRMED"]);

export const SWEEP_FIELDS = ["status", "fanoutTier", "nextTierAt", "expiresAt", "unitsNeeded", "unitsFulfilled",
  "responders", "bloodGroup", "component", "acceptCompatible", "acceptedGroups", "division", "district",
  "upazila", "urgency"];

export type Plan =
  | { kind: "send"; tier: number; conditions: string[]; nextTierAt: Date }
  | { kind: "wait"; nextTierAt: Date }          // enough donors are on it: check again later
  | { kind: "done"; reason: string };           // all tiers sent, expired, or unusable data

/** What to do with one due request. Pure: no I/O. */
export function planEscalation(r: Record<string, any>, now: Date, minutes?: Record<string, number>): Plan {
  if (r.status !== "OPEN") return { kind: "done", reason: "not open" };
  if (r.expiresAt instanceof Date && r.expiresAt <= now) return { kind: "done", reason: "expired" };

  const unitsLeft = Number(r.unitsNeeded ?? 1) - Number(r.unitsFulfilled ?? 0);
  const activeOffers = Object.values(r.responders ?? {}).filter((x: any) => ACTIVE.has(x?.status)).length;
  if (activeOffers >= unitsLeft) return { kind: "wait", nextTierAt: addMinutes(now, RECHECK_MINUTES) };

  const neighbours = neighboursOf(String(r.district));
  let tier: number | null;
  let conditions: string[] = [];
  try {
    tier = nextTier(r as TierRequest, Number(r.fanoutTier ?? 0), neighbours);
    if (tier !== null) conditions = tierConditions(r as TierRequest, tier, neighbours);
  } catch {
    return { kind: "done", reason: "bad area or group codes" };
  }
  if (tier === null || conditions.length === 0) return { kind: "done", reason: "all tiers sent" };
  return { kind: "send", tier, conditions, nextTierAt: addMinutes(now, escalationMinutes(minutes, r.urgency)) };
}

export interface SweepResult { due: number; sent: number; waiting: number; finished: number; lost: number; failed: number; deferred: number }

export async function sweep(env: Env, now = new Date()): Promise<SweepResult> {
  const token = await getAccessToken(env);
  const fs = new Firestore(env, token);
  const docs = await fs.runQuery({
    collection: "requests",
    where: [["status", "EQUAL", "OPEN"], ["nextTierAt", "LESS_THAN_OR_EQUAL", now]],
    orderBy: [["nextTierAt", "asc"]],
    limit: SWEEP_LIMIT,
    select: SWEEP_FIELDS,
  });

  const out: SweepResult = { due: docs.length, sent: 0, waiting: 0, finished: 0, lost: 0, failed: 0, deferred: 0 };
  const items: { rid: string; r: Record<string, any>; plan: Plan; updateTime: string }[] = [];
  let budget = SEND_BUDGET;
  for (const d of docs) {
    const r = fieldsOf(d);
    const plan = planEscalation(r, now, env.ESCALATION_MINUTES);
    if (plan.kind === "send") {
      if (plan.conditions.length > budget) { out.deferred++; continue; } // next run
      budget -= plan.conditions.length;
    }
    items.push({ rid: fs.pathOf(d.name).split("/")[1], r, plan, updateTime: d.updateTime });
  }

  // Claim everything in one batch (one subrequest); each write stands or falls on its own.
  const claims = await fs.batchWrite(items.map(({ rid, r, plan, updateTime }) => fanoutWrite(
    fs, rid,
    plan.kind === "send" ? plan.tier : Number(r.fanoutTier ?? 0),
    plan.kind === "done" ? FAR_FUTURE : plan.nextTierAt,
    updateTime,
  )));

  const releases: unknown[] = [];
  for (let i = 0; i < items.length; i++) {
    const { rid, r, plan } = items[i];
    const claim = claims[i];
    if (!claim.ok) { out.lost++; continue; }          // changed meanwhile: someone else handled it
    if (plan.kind === "wait") { out.waiting++; continue; }
    if (plan.kind === "done") { out.finished++; continue; }
    const data = requestPushData(rid, r, plan.tier);
    let ok = true;
    for (const condition of plan.conditions) {
      if (await sendToCondition(env, token, { condition, data, collapseKey: rid }) !== "ok") { ok = false; break; }
    }
    if (ok) { out.sent++; continue; }
    out.failed++;
    // Put it back as it was, so the next run tries this tier again.
    if (claim.updateTime) {
      const was = r.nextTierAt instanceof Date ? r.nextTierAt : now;
      releases.push(fanoutWrite(fs, rid, Number(r.fanoutTier ?? 0), was, claim.updateTime));
    }
  }
  if (releases.length) {
    await fs.batchWrite(releases).catch((e) => console.error(JSON.stringify({ sweepReleaseFailed: String(e) })));
  }
  console.log(JSON.stringify({ sweep: out }));
  return out;
}
