// New-report alert for admins (phase-07 7.3): within 5 minutes of a new report, one push to
// the `admins` topic, which admin devices subscribe to.
//
// Its own timer, "2,7,12,…,57 * * * *": two minutes after each sweep, so the escalation sweep
// keeps its whole 50-subrequest budget (Free plan) and the Apps Script backup's shared code
// stays unchanged. An explicit minute list, because Cloudflare's docs don't show range/step.
//
// Each run: 1 KV read + 1 COUNT read. Only when there are new reports: 1 FCM send + 1 KV write.
// The push carries only a count, never IDs or text: anyone can subscribe to a topic. The
// admin's app then reads the reports, which the rules allow for admins only.
import type { Env } from "./env";
import { sendToCondition } from "./fcm";
import { Firestore, type StructuredQuery } from "./firestore";
import { getAccessToken } from "./google-auth";

export const REPORTS_CURSOR_KEY = "reports_cursor";
export const ADMINS_CONDITION = "'admins' in topics";
export const REPORT_COUNT_UPTO = 100; // the push says "100+" beyond this; 1 read per 1000 entries anyway

export interface ReportAlertResult {
  /** New reports since the last alert (capped at REPORT_COUNT_UPTO). */
  new: number;
  sent: boolean;
  /** First run: the cursor was set to now; reports filed before it don't alert. */
  started?: boolean;
  /** FCM failed and will be retried next run (the cursor didn't move). */
  retry?: boolean;
}

/** Reports filed in (after, upTo]. Single-field range on createdAt: Firestore's automatic index. */
export function newReportsQuery(after: Date, upTo: Date): StructuredQuery {
  return {
    collection: "reports",
    where: [["createdAt", "GREATER_THAN", after], ["createdAt", "LESS_THAN_OR_EQUAL", upTo]],
  };
}

export async function alertNewReports(env: Env, now = new Date()): Promise<ReportAlertResult> {
  const stored = await env.CACHE.get(REPORTS_CURSOR_KEY, "json");
  const after = typeof stored === "string" ? new Date(stored) : null;
  if (!after || Number.isNaN(after.getTime()) || after > now) {
    await env.CACHE.put(REPORTS_CURSOR_KEY, JSON.stringify(now.toISOString()));
    return log({ new: 0, sent: false, started: true });
  }

  const token = await getAccessToken(env, "cron");
  const n = await new Firestore(env, token).count(newReportsQuery(after, now), REPORT_COUNT_UPTO);
  if (n === 0) return log({ new: 0, sent: false });

  const outcome = await sendToCondition(env, token, {
    condition: ADMINS_CONDITION,
    data: { type: "NEW_REPORTS", count: n >= REPORT_COUNT_UPTO ? `${REPORT_COUNT_UPTO}+` : String(n) },
    collapseKey: "admin_reports", // a newer count replaces an unread older one
  });
  // A retry keeps the cursor, so the next run counts these reports again. A fatal error
  // (bad payload or role, already logged by fcm.ts) moves on, so it can't repeat every run.
  if (outcome === "retry") return log({ new: n, sent: false, retry: true });
  await env.CACHE.put(REPORTS_CURSOR_KEY, JSON.stringify(now.toISOString()));
  return log({ new: n, sent: outcome === "ok" });
}

function log(out: ReportAlertResult): ReportAlertResult {
  console.log(JSON.stringify({ reportAlerts: out }));
  return out;
}
