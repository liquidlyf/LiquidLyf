// Retention clean-up, cron 0 3 * * * = 09:00 Dhaka (phase-05 5.7; doc 03 §5; B6 = a, 13 Nov 2026):
//   - 90 days after a request closes (last updatedAt): delete private/contact, the donor phone
//     and message in its responses, and the request's notes and closing reason. Short names
//     ("Alice T."), blood group, area, dates and outcome stay, for history and statistics;
//   - inbox items (users/*/notifications) after 60 days;
//   - reports and the moderators' action log (adminLog, phase-07 7.4) after 1 year (doc 03 §5).
// Old requests are walked in updatedAt order from a cursor kept in KV, so each is handled once.
// Free plan: 50 subrequests per run, so each part does a bounded number of pages.
import type { Env } from "./env";
import { deleteWrite, Firestore, fieldsOf, updateWrite, type FsDoc } from "./firestore";
import { getAccessToken } from "./google-auth";

export const RETENTION_DAYS = { closedRequest: 90, inbox: 60, report: 365, adminLog: 365 };
export const PAGE = 100;       // docs per query
export const MAX_PAGES = 4;    // per part per run: 4 parts x 4 pages x 2 subrequests + token < 50
export const CURSOR_KEY = "retention_cursor";
const UID = /^[A-Za-z0-9_-]{1,128}$/;
const DAY = 86_400_000;

interface Cursor { t: string; name: string }
export interface RetentionResult { requests: number; responses: number; inbox: number; reports: number; adminLog: number }

export async function retention(env: Env, now = new Date()): Promise<RetentionResult> {
  const token = await getAccessToken(env, "cron");
  const fs = new Firestore(env, token);
  const out: RetentionResult = { requests: 0, responses: 0, inbox: 0, reports: 0, adminLog: 0 };
  await purgeClosedRequests(env, fs, now, out);
  out.inbox = await deleteOlderThan(fs, { collection: "notifications", allDescendants: true }, "createdAt",
    new Date(now.getTime() - RETENTION_DAYS.inbox * DAY));
  out.reports = await deleteOlderThan(fs, { collection: "reports" }, "createdAt",
    new Date(now.getTime() - RETENTION_DAYS.report * DAY));
  out.adminLog = await deleteOlderThan(fs, { collection: "adminLog" }, "createdAt",
    new Date(now.getTime() - RETENTION_DAYS.adminLog * DAY));
  console.log(JSON.stringify({ retention: out }));
  return out;
}

/** Writes that strip one closed request of its personal data. Pure. */
export function purgeWrites(fs: Firestore, d: FsDoc): { writes: unknown[]; responses: number } {
  const path = fs.pathOf(d.name);
  const r = fieldsOf(d);
  const donors = Object.keys(r.responders ?? {}).filter((u) => UID.test(u));
  return {
    responses: donors.length,
    writes: [
      deleteWrite(fs.docName(`${path}/private/contact`)),
      // Listed in the mask but not given a value = deleted. exists: never create a doc.
      updateWrite(d.name, ["notes", "closedReason"], {}, { exists: true }),
      ...donors.map((u) => updateWrite(fs.docName(`${path}/responses/${u}`), ["donorPhone", "message"], {}, { exists: true })),
    ],
  };
}

async function purgeClosedRequests(env: Env, fs: Firestore, now: Date, out: RetentionResult) {
  const cutoff = new Date(now.getTime() - RETENTION_DAYS.closedRequest * DAY);
  let cursor = (await env.CACHE.get(CURSOR_KEY, "json")) as Cursor | null;
  for (let page = 0; page < MAX_PAGES; page++) {
    const docs = await fs.runQuery({
      collection: "requests",
      where: [["updatedAt", "LESS_THAN_OR_EQUAL", cutoff]],
      orderBy: [["updatedAt", "asc"], ["__name__", "asc"]],
      limit: PAGE,
      select: ["status", "updatedAt", "responders"],
      ...(cursor ? { startAfter: [new Date(cursor.t), { ref: cursor.name }] } : {}),
    });
    if (docs.length === 0) break;
    const writes: unknown[] = [];
    for (const d of docs) {
      // OPEN past 90 days shouldn't exist (expiry closes them); leave it alone if it does.
      if (fieldsOf(d).status === "OPEN") continue;
      const p = purgeWrites(fs, d);
      writes.push(...p.writes);
      out.requests++;
      out.responses += p.responses;
    }
    for (let i = 0; i < writes.length; i += 500) await fs.batchWrite(writes.slice(i, i + 500));
    const last = docs[docs.length - 1];
    const t = fieldsOf(last).updatedAt;
    cursor = { t: (t instanceof Date ? t : cutoff).toISOString(), name: last.name };
    await env.CACHE.put(CURSOR_KEY, JSON.stringify(cursor));
    if (docs.length < PAGE) break;
  }
}

async function deleteOlderThan(fs: Firestore, from: { collection: string; allDescendants?: boolean },
  field: string, cutoff: Date): Promise<number> {
  let deleted = 0;
  for (let page = 0; page < MAX_PAGES; page++) {
    const docs = await fs.runQuery({
      ...from, where: [[field, "LESS_THAN_OR_EQUAL", cutoff]], orderBy: [[field, "asc"]], limit: PAGE, select: ["__name__"],
    });
    if (docs.length === 0) break;
    const results = await fs.batchWrite(docs.map((d) => deleteWrite(d.name)));
    deleted += results.filter((r) => r.ok).length;
    if (docs.length < PAGE) break;
  }
  return deleted;
}
