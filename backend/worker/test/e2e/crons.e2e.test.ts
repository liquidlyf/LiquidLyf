// The crons against the real Firestore emulator: runQuery, batchWrite preconditions, transforms,
// field deletion and cursors behave as the Worker expects. FCM is a logged dry run in emulator mode.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { expireOverdue } from "../../src/expiry";
import { FAR_FUTURE } from "../../src/fanout";
import { fieldsOf } from "../../src/firestore";
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

    const out = await retention(env, NOW);
    expect(out).toEqual({ requests: 1, responses: 1, inbox: 1, reports: 1 });

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
    expect(await retention(env, NOW)).toEqual({ requests: 0, responses: 0, inbox: 0, reports: 0 });
  });
});
