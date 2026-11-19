// The crons against the real Firestore emulator: runQuery, batchWrite preconditions, transforms,
// field deletion and cursors behave as the Worker expects. FCM is a logged dry run in emulator mode.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { expireOverdue } from "../../src/expiry";
import { FAR_FUTURE } from "../../src/fanout";
import { fieldsOf } from "../../src/firestore";
import { alertNewReports } from "../../src/reports";
import { CURSOR_KEY, retention } from "../../src/retention";
import { sweep } from "../../src/sweep";
import { clearFirestore, emulatorEnv, getDoc, newRequest, putDoc } from "./emulators";

const MIN = 60_000;
const DAY = 86_400_000;
const NOW = new Date(Date.now() + 5 * MIN); // all docs below are "due" by then
const at = (ms: number) => new Date(NOW.getTime() + ms);
const fields = async (path: string) => { const d = await getDoc(path); return d ? fieldsOf(d) : null; };

/** Capture the emulator-mode FCM dry runs. */
function pushes() {
  const log = vi.spyOn(console, "log");
  return () => log.mock.calls.map((c) => { try { return JSON.parse(String(c[0])); } catch { return null; } })
    .filter((x) => x?.fcmDryRun);
}

beforeEach(clearFirestore);
afterEach(() => vi.restoreAllMocks());

describe("escalation sweep (emulator)", () => {
  test("two racing sweeps alert once; then tiers 3 and 4 follow on schedule; then it stops", async () => {
    const env = emulatorEnv();
    const sent = pushes();
    await putDoc("requests/r1", newRequest("alice", { nextTierAt: new Date(), fanoutTier: 0 }));

    const [a, b] = await Promise.all([sweep(env, NOW), sweep(env, NOW)]);
    expect(a.sent + b.sent).toBe(1);                 // the updateTime precondition picked one winner
    expect(sent()).toHaveLength(1);
    expect(sent()[0]).toMatchObject({ condition: "'up_BD30262000_B_POS' in topics", data: { requestId: "r1", tier: "1" } });
    let r = (await fields("requests/r1"))!;
    expect(r.fanoutTier).toBe(1);
    expect(r.nextTierAt).toEqual(at(30 * MIN));    // URGENT: 30 minutes

    expect((await sweep(env, NOW)).due).toBe(0);   // not due again yet

    await sweep(env, at(31 * MIN));
    r = (await fields("requests/r1"))!;
    expect(r.fanoutTier).toBe(3);                  // exact group: no tier 2
    expect(sent()[1].condition).toBe("'di_BD3026_B_POS' in topics");

    await sweep(env, at(62 * MIN));
    expect((await fields("requests/r1"))!.fanoutTier).toBe(4);
    expect(sent().slice(2).map((p) => p.condition).join(" || ")).toContain("'di_BD3033_B_POS' in topics"); // Gazipur

    await sweep(env, at(93 * MIN));
    r = (await fields("requests/r1"))!;
    expect(r.fanoutTier).toBe(4);
    expect(r.nextTierAt).toEqual(FAR_FUTURE);      // finished: never due again
    const total = sent().length;
    expect((await sweep(env, at(200 * MIN))).due).toBe(0);
    expect(sent()).toHaveLength(total);
  });

  test("enough offers: no alert, checked again in an hour; updatedAt untouched", async () => {
    const env = emulatorEnv();
    const sent = pushes();
    const created = newRequest("alice", { nextTierAt: new Date(), fanoutTier: 1, responders: { bob: { name: "Bob T.", group: "B_POS", status: "OFFERED" } } });
    await putDoc("requests/r1", created);
    expect(await sweep(env, NOW)).toMatchObject({ waiting: 1, sent: 0 });
    const r = (await fields("requests/r1"))!;
    expect(r.fanoutTier).toBe(1);
    expect(r.nextTierAt).toEqual(at(60 * MIN));
    expect(r.updatedAt).toEqual(created.updatedAt);
    expect(sent()).toHaveLength(0);
  });

  test("closed requests are never picked up", async () => {
    await putDoc("requests/r1", newRequest("alice", { status: "CANCELLED", nextTierAt: new Date() }));
    expect((await sweep(emulatorEnv(), NOW)).due).toBe(0);
  });
});

describe("daily alert cap (emulator)", () => {
  test("the COUNT runs on real Firestore: 5 earlier requests in 24 h park the 6th; older or other people's don't count", async () => {
    const env = emulatorEnv();
    const sent = pushes();
    const t = (minAgo: number) => new Date(Date.now() - minAgo * MIN);
    // alice: 5 requests in the last 24 h (cancelled ones count too), one 2 days ago
    for (let i = 1; i <= 5; i++) {
      await putDoc(`requests/a${i}`, newRequest("alice", { status: i <= 3 ? "CANCELLED" : "OPEN", fanoutTier: 1, nextTierAt: FAR_FUTURE, createdAt: t(60 * i) }));
    }
    await putDoc("requests/a_old", newRequest("alice", { status: "FULFILLED", nextTierAt: FAR_FUTURE, createdAt: t(48 * 60) }));
    await putDoc("requests/a6", newRequest("alice", { createdAt: t(2), nextTierAt: t(2) }));    // the 6th: capped
    await putDoc("requests/b1", newRequest("bob", { createdAt: t(2), nextTierAt: t(2) }));      // someone else: sent
    // carol: 5 requests, but the oldest is just outside the 24 h before her new one
    for (let i = 1; i <= 4; i++) {
      await putDoc(`requests/c${i}`, newRequest("carol", { status: "CANCELLED", nextTierAt: FAR_FUTURE, createdAt: t(60 * i) }));
    }
    await putDoc("requests/c5", newRequest("carol", { status: "CANCELLED", nextTierAt: FAR_FUTURE, createdAt: t(24 * 60 + 5) }));
    await putDoc("requests/c6", newRequest("carol", { createdAt: t(2), nextTierAt: t(2) }));    // 4 in the window: sent

    const out = await sweep(env, NOW);
    expect(out).toMatchObject({ due: 3, sent: 2, capped: 1 });
    expect(sent().map((p) => p.data.requestId).sort()).toEqual(["b1", "c6"]);
    const a6 = (await fields("requests/a6"))!;
    expect(a6.fanoutTier).toBe(0);
    expect(a6.nextTierAt).toEqual(FAR_FUTURE);                 // never due again; still OPEN in the feed
    expect(a6.status).toBe("OPEN");
    expect((await sweep(env, NOW)).due).toBe(0);               // a6 parked; b1 and c6 are due again in 30 min
  });
});

describe("expiry (emulator)", () => {
  test("EXPIRED + userStats -1 + fresh updatedAt; a missing userStats isn't created", async () => {
    const env = emulatorEnv();
    const past = new Date(Date.now() - DAY);
    const before = newRequest("alice", { expiresAt: past });
    await putDoc("requests/r1", before);
    await putDoc("userStats/alice", { openRequests: 1, totalRequests: 3, lastRequestId: "r1" });
    await putDoc("requests/r2", newRequest("ghost", { expiresAt: past }));
    await putDoc("requests/r3", newRequest("alice", { expiresAt: new Date(Date.now() + DAY) })); // not yet

    const out = await expireOverdue(env, NOW);
    expect(out).toMatchObject({ due: 2, expired: 2, withoutStats: 1 });
    const r1 = (await fields("requests/r1"))!;
    expect(r1.status).toBe("EXPIRED");
    expect(r1.nextTierAt).toEqual(FAR_FUTURE);
    expect(r1.updatedAt.getTime()).toBeGreaterThan(before.updatedAt.getTime());
    expect((await fields("userStats/alice"))!.openRequests).toBe(0);
    expect((await fields("requests/r2"))!.status).toBe("EXPIRED");
    expect(await getDoc("userStats/ghost")).toBeNull();
    expect((await fields("requests/r3"))!.status).toBe("OPEN");
  });
});

describe("retention clean-up (emulator)", () => {
  test("90/60/365 days: strips and deletes what B6 says, keeps names and recent data", async () => {
    const env = emulatorEnv();
    const old = new Date(Date.now() - 100 * DAY);
    await putDoc("requests/old", newRequest("alice", {
      status: "FULFILLED", closedReason: "Thanks Bob, 01700000099", notes: "Ward 5", updatedAt: old,
      responders: { bob: { name: "Bob T.", group: "B_POS", status: "DONATED" } },
    }));
    await putDoc("requests/old/private/contact", { requesterUid: "alice", contactName: "Alice Test", contactPhone: "+8801700000099" });
    await putDoc("requests/old/responses/bob", { donorUid: "bob", donorDisplayName: "Bob T.", donorPhone: "+8801700000011", message: "call me", status: "DONATED" });
    await putDoc("requests/recent", newRequest("alice", { status: "CANCELLED", notes: "keep", updatedAt: new Date(Date.now() - 10 * DAY) }));
    await putDoc("users/u1/notifications/old", { type: "NEW_OFFER", createdAt: new Date(Date.now() - 70 * DAY) });
    await putDoc("users/u1/notifications/new", { type: "NEW_OFFER", createdAt: new Date(Date.now() - DAY) });
    await putDoc("reports/a_old", { reporterUid: "a", createdAt: new Date(Date.now() - 400 * DAY) });
    await putDoc("reports/b_new", { reporterUid: "b", createdAt: new Date(Date.now() - 10 * DAY) });
    await putDoc("adminLog/old", { adminUid: "mod", action: "RESTORE_REQUEST", createdAt: new Date(Date.now() - 400 * DAY) });
    await putDoc("adminLog/new", { adminUid: "mod", action: "RESTORE_REQUEST", createdAt: new Date(Date.now() - 10 * DAY) });

    const out = await retention(env, NOW);
    expect(out).toEqual({ requests: 1, responses: 1, inbox: 1, reports: 1, adminLog: 1, unlisted: 0 });
    expect(await getDoc("adminLog/old")).toBeNull();
    expect(await getDoc("adminLog/new")).not.toBeNull();

    const r = (await fields("requests/old"))!;
    expect(r.notes).toBeUndefined();
    expect(r.closedReason).toBeUndefined();
    expect(r.requesterDisplayName).toBe("Alice T.");          // short names stay (B6 = a)
    expect(r.responders.bob.name).toBe("Bob T.");
    expect(r.updatedAt).toEqual(old);                          // not "changed" for the feed
    expect(await getDoc("requests/old/private/contact")).toBeNull();
    const resp = (await fields("requests/old/responses/bob"))!;
    expect(resp.donorPhone).toBeUndefined();
    expect(resp.message).toBeUndefined();
    expect(resp.donorDisplayName).toBe("Bob T.");
    expect(resp.status).toBe("DONATED");
    expect((await fields("requests/recent"))!.notes).toBe("keep");
    expect(await getDoc("users/u1/notifications/old")).toBeNull();
    expect(await getDoc("users/u1/notifications/new")).not.toBeNull();
    expect(await getDoc("reports/a_old")).toBeNull();
    expect(await getDoc("reports/b_new")).not.toBeNull();

    // A second run starts after the cursor and finds nothing more to do.
    expect(JSON.parse((await env.CACHE.get(CURSOR_KEY, "json")) ? JSON.stringify(await env.CACHE.get(CURSOR_KEY, "json")) : "null").name).toContain("requests/old");
    expect(await retention(env, NOW)).toEqual({ requests: 0, responses: 0, inbox: 0, reports: 0, adminLog: 0, unlisted: 0 });
  });
});

describe("D23 unlist (emulator, in the retention run)", () => {
  test("over 60 and weight না listings leave Find donors; 60, unlisted and requester profiles stay as they are", async () => {
    const env = emulatorEnv();
    const Y = NOW.getUTCFullYear();
    const listed = (birthYear: number, weightOk = true) =>
      ({ fullName: "Test", showInDonorSearch: true, available: true, birthYear, weightOk, updatedAt: new Date(Date.now() - DAY) });
    await putDoc("users/old", listed(Y - 61));
    await putDoc("users/sixty", listed(Y - 60));
    await putDoc("users/light", listed(1995, false));
    await putDoc("users/oldUnlisted", { ...listed(Y - 70), showInDonorSearch: false });
    await putDoc("users/requester", { fullName: "Test", showInDonorSearch: false, available: false });
    for (const uid of ["old", "sixty", "light"]) await putDoc(`donors/${uid}`, { displayName: "Test T.", available: true });

    expect((await retention(env, NOW)).unlisted).toBe(2);
    for (const uid of ["old", "light"]) {
      const u = (await fields(`users/${uid}`))!;
      expect(u.showInDonorSearch).toBe(false);
      expect(u.available).toBe(true);                                    // left as it was
      expect(u.updatedAt.getTime()).toBeGreaterThan(Date.now() - 60_000); // server time
      expect(await getDoc(`donors/${uid}`)).toBeNull();
    }
    expect((await fields("users/sixty"))!.showInDonorSearch).toBe(true);
    expect(await getDoc("donors/sixty")).not.toBeNull();
    expect((await fields("users/oldUnlisted"))!.updatedAt.getTime()).toBeLessThan(Date.now() - 60_000); // untouched
    expect((await retention(env, NOW)).unlisted).toBe(0);                // nothing left the next day
  });
});

describe("report alerts (emulator)", () => {
  test("counts only reports filed since the last alert, pushes the count to admins, then moves on", async () => {
    const env = emulatorEnv();
    const sent = pushes();
    const T0 = new Date(Date.now() - 10 * MIN);
    expect(await alertNewReports(env, T0)).toMatchObject({ started: true }); // first run: cursor only

    const report = (id: string, createdAt: Date) =>
      putDoc(`reports/${id}`, { reporterUid: id.split("_")[0], targetType: "REQUEST", targetId: "r1", reason: "SPAM", status: "OPEN", createdAt });
    await report("old_r1", new Date(T0.getTime() - MIN));   // before the cursor: already alerted
    await report("eve_r1", new Date(T0.getTime() + MIN));
    await report("bob_r1", new Date(T0.getTime() + 2 * MIN));
    await report("late_r1", at(MIN));                         // after this run's "now": next run

    const T1 = new Date(T0.getTime() + 5 * MIN);
    expect(await alertNewReports(env, T1)).toEqual({ new: 2, sent: true });
    expect(sent()).toEqual([{ fcmDryRun: true, condition: "'admins' in topics", data: { type: "NEW_REPORTS", count: "2" } }]);

    expect(await alertNewReports(env, new Date(T1.getTime() + 5 * MIN))).toEqual({ new: 0, sent: false });
    expect(await alertNewReports(env, at(2 * MIN))).toEqual({ new: 1, sent: true }); // the late one
    expect(sent()).toHaveLength(2);
  });
});
