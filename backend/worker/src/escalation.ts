// The escalation sweep's logic (phase-05 5.5, doc 10 §6.4), shared by the Worker's 5-minute
// cron (sweep.ts) and the Apps Script backup (backend/apps-script, bundled into Shared.js by
// `npm run build:apps-script`).
//
// sweepSteps() is a generator: it yields each Firestore or FCM call it needs and receives the
// result (or the error, through gen.throw). The Worker answers with async fetch, Apps Script with
// its synchronous UrlFetchApp, so both run exactly this code. Nothing here may use fetch,
// WebCrypto, TextEncoder, btoa or timers: Apps Script has none of them.
//
// What a run does: picks up OPEN requests whose nextTierAt has passed and sends the next tier,
// unless enough donors have already offered. It also sends tier 1 for a request whose app never
// called dispatch (the rules make every new request due at once: fanoutTier 0, nextTierAt =
// create time), after the same daily-cap check as dispatch.
//
// Idempotent: every claim carries the doc's updateTime, so a second sweep (the backup, or a late
// dispatch) loses cleanly and nobody is alerted twice for a tier.
// Free plan: 50 subrequests per invocation, so one run makes at most SEND_BUDGET sends + counts.
import { capCountFailed, capQuery } from "./cap";
import { addMinutes, escalationMinutes, FAR_FUTURE, fanoutWrite, requestPushData } from "./fanout";
import type { SendResult } from "./fcm";
import { type BatchResult, type DocNames, fieldsOf, type FsDoc, type StructuredQuery } from "./firestore";
import { neighboursOf } from "./neighbours";
import { nextTier, tierConditions, type TierRequest } from "./tiers";

export const SWEEP_LIMIT = 25;       // requests looked at per run (each costs 1 read)
export const SEND_BUDGET = 40;       // FCM sends + cap counts per run; leaves room for token + query + 2 batches
export const RECHECK_MINUTES = 60;   // enough offers for now: look again later
export const CAPPED = "daily alert cap";
const ACTIVE = ["OFFERED", "CONFIRMED"];

export const SWEEP_FIELDS = ["status", "fanoutTier", "nextTierAt", "expiresAt", "unitsNeeded", "unitsFulfilled",
  "responders", "bloodGroup", "component", "acceptCompatible", "acceptedGroups", "division", "district",
  "upazila", "urgency", "requesterUid", "createdAt"];

export type Plan =
  | { kind: "send"; tier: number; conditions: string[]; nextTierAt: Date }
  | { kind: "wait"; nextTierAt: Date }          // enough donors are on it: check again later
  | { kind: "done"; reason: string };           // all tiers sent, expired, over the cap, or unusable data

/** What to do with one due request. Pure: no I/O. */
export function planEscalation(r: Record<string, any>, now: Date, minutes?: Record<string, number>): Plan {
  if (r.status !== "OPEN") return { kind: "done", reason: "not open" };
  if (r.expiresAt instanceof Date && r.expiresAt <= now) return { kind: "done", reason: "expired" };

  const unitsLeft = Number(r.unitsNeeded ?? 1) - Number(r.unitsFulfilled ?? 0);
  const activeOffers = Object.values(r.responders ?? {}).filter((x: any) => ACTIVE.includes(x?.status)).length;
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

/** OPEN requests due at or before `dueBefore`, longest-waiting first. */
export function dueQuery(dueBefore: Date): StructuredQuery {
  return {
    collection: "requests",
    where: [["status", "EQUAL", "OPEN"], ["nextTierAt", "LESS_THAN_OR_EQUAL", dueBefore]],
    orderBy: [["nextTierAt", "asc"]],
    limit: SWEEP_LIMIT,
    select: SWEEP_FIELDS,
  };
}

/** One call the sweep needs made, and (in the comment) what the driver hands back. */
export type Step =
  | { op: "query"; query: StructuredQuery }                                                // FsDoc[]
  | { op: "count"; query: StructuredQuery; upTo: number }                                  // number
  | { op: "batchWrite"; writes: unknown[] }                                                // BatchResult[]
  | { op: "send"; condition: string; data: Record<string, string>; collapseKey: string };  // SendResult

export interface SweepResult {
  due: number; sent: number; waiting: number; finished: number; capped: number; lost: number; failed: number; deferred: number;
}

export interface SweepOptions {
  now: Date;
  /** Due at or before this: `now` for the Worker; now − 10 min for the backup, so it steps in only when the Worker is behind. */
  dueBefore: Date;
  /** ESCALATION_MINUTES per urgency. */
  minutes?: Record<string, number>;
  /** DAILY_ALERT_CAP. */
  cap: number;
  names: Pick<DocNames, "docName" | "pathOf">;
}

export function* sweepSteps(o: SweepOptions): Generator<Step, SweepResult, any> {
  const docs: FsDoc[] = yield { op: "query", query: dueQuery(o.dueBefore) };

  const out: SweepResult = { due: docs.length, sent: 0, waiting: 0, finished: 0, capped: 0, lost: 0, failed: 0, deferred: 0 };
  const items: { rid: string; r: Record<string, any>; plan: Plan; updateTime: string }[] = [];
  let budget = SEND_BUDGET;
  for (const d of docs) {
    const r = fieldsOf(d);
    let plan = planEscalation(r, o.now, o.minutes);
    if (plan.kind === "send") {
      // A request's first alert (the app never dispatched it) obeys the daily cap like dispatch does.
      const first = Number(r.fanoutTier ?? 0) === 0;
      const cost = plan.conditions.length + (first ? 1 : 0);
      if (cost > budget) { out.deferred++; continue; } // next run
      budget -= cost;
      if (first) {
        let over = false;
        try {
          const createdAt = r.createdAt instanceof Date ? r.createdAt : o.now;
          const n: number = yield { op: "count", query: capQuery(String(r.requesterUid), createdAt), upTo: o.cap };
          over = n >= o.cap;
        } catch (e) {
          capCountFailed(e); // fail open: an error never silences a blood request
        }
        if (over) {
          budget += plan.conditions.length;
          plan = { kind: "done", reason: CAPPED };     // parked at tier 0: it stays in the feed, unalerted
        }
      }
    }
    items.push({ rid: o.names.pathOf(d.name).split("/")[1], r, plan, updateTime: d.updateTime });
  }
  if (items.length === 0) return out;

  // Claim everything in one batch (one subrequest); each write stands or falls on its own.
  const claims: BatchResult[] = yield {
    op: "batchWrite",
    writes: items.map(({ rid, r, plan, updateTime }) => fanoutWrite(
      o.names, rid,
      plan.kind === "send" ? plan.tier : Number(r.fanoutTier ?? 0),
      plan.kind === "done" ? FAR_FUTURE : plan.nextTierAt,
      updateTime,
    )),
  };

  const releases: unknown[] = [];
  for (let i = 0; i < items.length; i++) {
    const { rid, r, plan } = items[i];
    const claim = claims[i];
    if (!claim.ok) { out.lost++; continue; }          // changed meanwhile: someone else handled it
    if (plan.kind === "wait") { out.waiting++; continue; }
    if (plan.kind === "done") { if (plan.reason === CAPPED) out.capped++; else out.finished++; continue; }
    const data = requestPushData(rid, r, plan.tier);
    let ok = true;
    for (const condition of plan.conditions) {
      let sent: SendResult;
      try {
        sent = yield { op: "send", condition, data, collapseKey: rid };
      } catch (e) {
        console.error(JSON.stringify({ sweepSendFailed: rid, error: String(e) }));
        sent = "retry";
      }
      if (sent !== "ok") { ok = false; break; }
    }
    if (ok) { out.sent++; continue; }
    out.failed++;
    // Put it back as it was, so the next run tries this tier again.
    if (claim.updateTime) {
      const was = r.nextTierAt instanceof Date ? r.nextTierAt : o.now;
      releases.push(fanoutWrite(o.names, rid, Number(r.fanoutTier ?? 0), was, claim.updateTime));
    }
  }
  if (releases.length) {
    try {
      yield { op: "batchWrite", writes: releases };
    } catch (e) {
      console.error(JSON.stringify({ sweepReleaseFailed: String(e) }));
    }
  }
  return out;
}
