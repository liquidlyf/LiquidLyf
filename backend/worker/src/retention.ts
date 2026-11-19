// Retention clean-up, cron 0 3 * * * = 09:00 Dhaka (phase-05 5.7; doc 03 §5; B6 = a, 13 Nov 2026):
//   - 90 days after a request closes (last updatedAt): delete private/contact, the donor phone
//     and message in its responses, and the request's notes and closing reason. Short names
//     ("Alice T."), blood group, area, dates and outcome stay, for history and statistics;
//   - inbox items (users/*/notifications) after 60 days;
//   - reports and the moderators' action log (adminLog, phase-07 7.4) after 1 year (doc 03 §5);
//   - D23: donors who can't give blood any more leave Find donors (see unlistIneligible).
// Old requests are walked in updatedAt order from a cursor kept in KV, so each is handled once.
// Free plan: 50 subrequests per run, so each part does a bounded number of pages.
import type { Env } from "./env";
import { deleteWrite, Firestore, fieldsOf, updateWrite, type FilterOp, type FsDoc } from "./firestore";
import { getAccessToken } from "./google-auth";

export const RETENTION_DAYS = { closedRequest: 90, inbox: 60, report: 365, adminLog: 365 };
export const PAGE = 100;       // docs per query
export const MAX_PAGES = 4;    // per part per run: 4 parts x 4 pages x 2 subrequests ...
export const UNLIST_PAGES = 2; // ... + 2 unlist queries x 2 pages x 2 + token = 41 < 50
export const MAX_DONOR_AGE = 60; // firestore.rules canBeListed and the app's EligibilityRules.maxAge
export const CURSOR_KEY = "retention_cursor";
const UID = /^[A-Za-z0-9_-]{1,128}$/;
const DAY = 86_400_000;

interface Cursor { t: string; name: string }
export interface RetentionResult {
  requests: number; responses: number; inbox: number; reports: number; adminLog: number; unlisted: number;
}

export async function retention(env: Env, now = new Date()): Promise<RetentionResult> {
  const token = await getAccessToken(env, "cron");
  const fs = new Firestore(env, token);
  const out: RetentionResult = { requests: 0, responses: 0, inbox: 0, reports: 0, adminLog: 0, unlisted: 0 };
  await purgeClosedRequests(env, fs, now, out);
  out.inbox = await deleteOlderThan(fs, { collection: "notifications", allDescendants: true }, "createdAt",
    new Date(now.getTime() - RETENTION_DAYS.inbox * DAY));
  out.reports = await deleteOlderThan(fs, { collection: "reports" }, "createdAt",
    new Date(now.getTime() - RETENTION_DAYS.report * DAY));
  out.adminLog = await deleteOlderThan(fs, { collection: "adminLog" }, "createdAt",
    new Date(now.getTime() - RETENTION_DAYS.adminLog * DAY));
  out.unlisted = await unlistIneligible(fs, now);
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

// D23 (the maintainer, 19 Nov 2026): only someone who can give blood is listed in Find donors.
// The rules check it when a donor lists themselves or changes their birth year or weight
// answer. This part unlists those who can't any more: over 60 ("this year minus the birth
// year" in UTC, like the rules and the app, so it happens on 1 January), and weight না
// listings made before D23. The over-60 query needs the users (showInDonorSearch, birthYear)
// index; the weight one is two equalities, which need none.
async function unlistIneligible(fs: Firestore, now: Date): Promise<number> {
  const queries: [string, FilterOp, unknown][][] = [
    [["showInDonorSearch", "EQUAL", true], ["birthYear", "LESS_THAN", now.getUTCFullYear() - MAX_DONOR_AGE]],
    [["showInDonorSearch", "EQUAL", true], ["weightOk", "EQUAL", false]],
  ];
  let unlisted = 0;
  for (const where of queries) {
    for (let page = 0; page < UNLIST_PAGES; page++) {
      // Unlisted docs drop out of the query, so each page starts from the top again.
      const docs = await fs.runQuery({ collection: "users", where, limit: PAGE, select: ["__name__"] });
      if (docs.length === 0) break;
      await fs.commit(docs.flatMap((d) => unlistWrites(fs, d.name)));
      unlisted += docs.length;
      if (docs.length < PAGE) break;
    }
  }
  return unlisted;
}

/**
 * One donor leaves the list, as the app's own unlist batch does: showInDonorSearch false and
 * the public card deleted, in one commit so they can't come apart. `available` stays. Pure.
 */
export function unlistWrites(fs: Firestore, userName: string): unknown[] {
  const uid = fs.pathOf(userName).split("/")[1];
  return [
    updateWrite(userName, ["showInDonorSearch"], { showInDonorSearch: false }, { exists: true }, ["updatedAt"]),
    deleteWrite(fs.docName(`donors/${uid}`)),
  ];
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
