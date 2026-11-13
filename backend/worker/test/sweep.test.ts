// Escalation: the pure plan, then a whole sweep run against mocked Google endpoints.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { FAR_FUTURE } from "../src/fanout";
import { neighboursOf } from "../src/neighbours";
import { planEscalation, RECHECK_MINUTES, SEND_BUDGET, sweep } from "../src/sweep";
import { docName, fakeGoogle, makeEnv, requestRest, resetCaches } from "./helpers";

const NOW = new Date("2026-11-20T06:00:00Z");
const MIN = { CRITICAL: 15, URGENT: 30, PLANNED: 120 };
const inMin = (m: number) => new Date(NOW.getTime() + m * 60_000);

/** A request as fieldsOf() returns it. */
const req = (o: Record<string, unknown> = {}) => ({
  status: "OPEN", fanoutTier: 0, nextTierAt: NOW, expiresAt: inMin(2 * 24 * 60), unitsNeeded: 1, unitsFulfilled: 0,
  responders: {}, bloodGroup: "B_POS", component: "WHOLE_BLOOD", acceptCompatible: false, acceptedGroups: ["B_POS"],
  division: "BD30", district: "BD3026", upazila: "BD30262000", urgency: "URGENT", ...o,
});

describe("planEscalation", () => {
  test("a request the app never dispatched gets tier 1 now, next check after the urgency's minutes", () => {
    expect(planEscalation(req(), NOW, MIN)).toEqual({
      kind: "send", tier: 1, conditions: ["'up_BD30262000_B_POS' in topics"], nextTierAt: inMin(30),
    });
    expect(planEscalation(req({ urgency: "CRITICAL" }), NOW, MIN)).toMatchObject({ nextTierAt: inMin(15) });
  });

  test("exact group: 1 -> 3 (district) -> 4 (neighbouring districts) -> done", () => {
    expect(planEscalation(req({ fanoutTier: 1 }), NOW, MIN)).toMatchObject({ tier: 3, conditions: ["'di_BD3026_B_POS' in topics"] });
    const t4 = planEscalation(req({ fanoutTier: 3 }), NOW, MIN);
    expect(t4.kind).toBe("send");
    const topics = (t4 as any).conditions.join(" || ");
    for (const d of neighboursOf("BD3026")) expect(topics).toContain(`'di_${d}_B_POS' in topics`);
    expect(planEscalation(req({ fanoutTier: 4 }), NOW, MIN)).toEqual({ kind: "done", reason: "all tiers sent" });
  });

  test("compatible groups add tier 2 (the other groups in the upazila)", () => {
    const r = req({ bloodGroup: "AB_POS", acceptCompatible: true, acceptedGroups: ["AB_POS", "A_POS", "O_POS"], fanoutTier: 1 });
    expect(planEscalation(r, NOW, MIN)).toMatchObject({
      tier: 2, conditions: ["'up_BD30262000_A_POS' in topics || 'up_BD30262000_O_POS' in topics"],
    });
  });

  test("Rh-negative starts at the district (tier 3), then the division (tier 4)", () => {
    const r = req({ bloodGroup: "O_NEG", acceptedGroups: ["O_NEG"] });
    expect(planEscalation(r, NOW, MIN)).toMatchObject({ tier: 3, conditions: ["'di_BD3026_O_NEG' in topics"] });
    expect(planEscalation({ ...r, fanoutTier: 3 }, NOW, MIN)).toMatchObject({ tier: 4, conditions: ["'dv_BD30_O_NEG' in topics"] });
  });

  test("enough active offers: no alert, look again later; withdrawn or declined ones don't count", () => {
    const offered = { a: { status: "OFFERED" }, b: { status: "WITHDRAWN" }, c: { status: "DECLINED" } };
    expect(planEscalation(req({ fanoutTier: 1, responders: offered }), NOW, MIN))
      .toEqual({ kind: "wait", nextTierAt: inMin(RECHECK_MINUTES) });
    expect(planEscalation(req({ fanoutTier: 1, unitsNeeded: 2, responders: offered }), NOW, MIN).kind).toBe("send");
    // A donated unit counts through unitsFulfilled.
    expect(planEscalation(req({ fanoutTier: 1, unitsNeeded: 2, unitsFulfilled: 1, responders: offered }), NOW, MIN).kind).toBe("wait");
  });

  test("expired, closed or broken requests are finished, never sent", () => {
    expect(planEscalation(req({ expiresAt: NOW }), NOW, MIN)).toEqual({ kind: "done", reason: "expired" });
    expect(planEscalation(req({ status: "CANCELLED" }), NOW, MIN).kind).toBe("done");
    expect(planEscalation(req({ upazila: "bad code!", district: "x y" }), NOW, MIN)).toEqual({ kind: "done", reason: "bad area or group codes" });
  });
});

describe("sweep run", () => {
  beforeEach(() => resetCaches());
  afterEach(() => vi.unstubAllGlobals());

  const due = (rid: string, o: Record<string, unknown> = {}) => ({ name: docName(`requests/${rid}`), ...requestRest({
    nextTierAt: NOW, expiresAt: inMin(2 * 24 * 60), unitsNeeded: 1, unitsFulfilled: 0, responders: {}, ...o,
  }), updateTime: `2026-11-20T05:00:00.00000${rid.length}Z` });

  test("queries only OPEN + due, claims in one batch with preconditions, sends, and stores the next time", async () => {
    const calls = await fakeGoogle({ query: () => [due("r1"), due("r2", { fanoutTier: 1, responders: { x: { status: "OFFERED" } } })] });
    const out = await sweep(await makeEnv({ ESCALATION_MINUTES: MIN }), NOW);

    expect(out).toMatchObject({ due: 2, sent: 1, waiting: 1, lost: 0, failed: 0 });
    const where = JSON.stringify(calls.queries[0].where);
    expect(where).toContain('"status"');
    expect(where).toContain('"nextTierAt"');
    expect(calls.queries[0].limit).toBeGreaterThan(0);
    expect(calls.batches).toHaveLength(1);
    const [claim1, claim2] = calls.batches[0];
    expect(claim1.currentDocument.updateTime).toBe("2026-11-20T05:00:00.000002Z");
    expect(claim1.update.fields.fanoutTier).toEqual({ integerValue: "1" });
    expect(claim1.update.fields.nextTierAt).toEqual({ timestampValue: inMin(30).toISOString() });
    expect(claim2.update.fields.fanoutTier).toEqual({ integerValue: "1" }); // tier unchanged while waiting
    expect(calls.fcm).toHaveLength(1);
    expect(calls.fcm[0]).toMatchObject({ condition: "'up_BD30262000_B_POS' in topics", data: { type: "NEW_REQUEST", requestId: "r1", tier: "1" } });
    expect(calls.writes).toHaveLength(0); // no atomic commits: claims are per-write
  });

  test("a claim another sweep won first is skipped: nobody is alerted twice", async () => {
    const calls = await fakeGoogle({ query: () => [due("r1")], batchCodes: [[9]] }); // FAILED_PRECONDITION
    const out = await sweep(await makeEnv(), NOW);
    expect(out).toMatchObject({ lost: 1, sent: 0 });
    expect(calls.fcm).toHaveLength(0);
  });

  test("if FCM fails, the claim is put back so the next run retries", async () => {
    const calls = await fakeGoogle({ query: () => [due("r1")], fcmStatus: [503] });
    const out = await sweep(await makeEnv(), NOW);
    expect(out).toMatchObject({ failed: 1, sent: 0 });
    expect(calls.batches).toHaveLength(2);
    const release = calls.batches[1][0];
    expect(release.update.fields.fanoutTier).toEqual({ integerValue: "0" });
    expect(release.update.fields.nextTierAt).toEqual({ timestampValue: NOW.toISOString() });
    expect(release.currentDocument.updateTime).toBeTruthy(); // guarded by the claim's own updateTime
  });

  test("finished requests are parked far in the future; nothing is sent", async () => {
    const calls = await fakeGoogle({ query: () => [due("r1", { fanoutTier: 4 })] });
    const out = await sweep(await makeEnv(), NOW);
    expect(out).toMatchObject({ finished: 1, sent: 0 });
    expect(calls.batches[0][0].update.fields.nextTierAt).toEqual({ timestampValue: FAR_FUTURE.toISOString() });
    expect(calls.fcm).toHaveLength(0);
  });

  test("stays inside the free plan's subrequest limit: extra work waits for the next run", async () => {
    const many = Array.from({ length: SEND_BUDGET + 5 }, (_, i) => due(`r${i}`, { fanoutTier: 1 }));
    const calls = await fakeGoogle({ query: () => many });
    const out = await sweep(await makeEnv(), NOW);
    expect(calls.fcm).toHaveLength(SEND_BUDGET);
    expect(calls.counts).toHaveLength(0);               // later tiers skip the daily-cap count
    expect(out.deferred).toBe(5);
    expect(calls.batches[0]).toHaveLength(SEND_BUDGET); // deferred ones aren't claimed
  });

  test("a first alert costs a daily-cap count too, and that counts against the same budget", async () => {
    const many = Array.from({ length: SEND_BUDGET }, (_, i) => due(`r${i}`)); // all at tier 0
    const calls = await fakeGoogle({ query: () => many });
    const out = await sweep(await makeEnv(), NOW);
    expect(calls.fcm.length + calls.counts.length).toBe(SEND_BUDGET);
    expect(calls.fcm).toHaveLength(SEND_BUDGET / 2);
    expect(out.deferred).toBe(SEND_BUDGET / 2);
  });

  test("daily cap: a never-dispatched request from someone over the cap is parked unalerted at tier 0", async () => {
    const created = new Date(NOW.getTime() - 3 * 60_000);
    const calls = await fakeGoogle({ query: () => [due("r1", { createdAt: created })], count: () => 5 });
    const out = await sweep(await makeEnv(), NOW);
    expect(out).toMatchObject({ due: 1, sent: 0, capped: 1, finished: 0 });
    expect(calls.fcm).toHaveLength(0);
    const claim = calls.batches[0][0];
    expect(claim.update.fields).toEqual({
      fanoutTier: { integerValue: "0" }, nextTierAt: { timestampValue: FAR_FUTURE.toISOString() },
    });
    expect(claim.currentDocument).toEqual({ updateTime: expect.any(String) });
    // The count: this requester's requests in the 24 h before this one, stopped at the cap.
    const { structuredQuery: q, aggregations } = calls.counts[0];
    expect(aggregations).toEqual([{ alias: "n", count: { upTo: "5" } }]);
    expect(q.where.compositeFilter.filters.map((f: any) => [f.fieldFilter.field.fieldPath, f.fieldFilter.op, Object.values(f.fieldFilter.value)[0]]))
      .toEqual([
        ["requesterUid", "EQUAL", "alice"],
        ["createdAt", "GREATER_THAN_OR_EQUAL", new Date(created.getTime() - 86_400_000).toISOString()],
        ["createdAt", "LESS_THAN", created.toISOString()],
      ]);
    expect(q.orderBy).toEqual([{ field: { fieldPath: "createdAt" }, direction: "DESCENDING" }]); // the my-requests index
  });

  test("daily cap: under the cap it's sent; a broken count fails open (alerts are never silenced by an error)", async () => {
    let calls = await fakeGoogle({ query: () => [due("r1")], count: () => 4 });
    expect(await sweep(await makeEnv(), NOW)).toMatchObject({ sent: 1, capped: 0 });
    vi.unstubAllGlobals();
    vi.spyOn(console, "error").mockImplementation(() => {});
    calls = await fakeGoogle({ query: () => [due("r1")], count: () => ({ status: 400, grpc: "FAILED_PRECONDITION" }) });
    expect(await sweep(await makeEnv(), NOW)).toMatchObject({ sent: 1, capped: 0 });
    expect(calls.fcm).toHaveLength(1);
  });

  test("daily cap: DAILY_ALERT_CAP sets the limit; later tiers aren't counted", async () => {
    let calls = await fakeGoogle({ query: () => [due("r1")], count: () => 2 });
    expect(await sweep(await makeEnv({ DAILY_ALERT_CAP: 2 }), NOW)).toMatchObject({ capped: 1 });
    expect(calls.counts[0].aggregations[0].count.upTo).toBe("2");
    vi.unstubAllGlobals();
    calls = await fakeGoogle({ query: () => [due("r1", { fanoutTier: 1 })], count: () => 99 });
    expect(await sweep(await makeEnv({ DAILY_ALERT_CAP: 2 }), NOW)).toMatchObject({ sent: 1, capped: 0 });
    expect(calls.counts).toHaveLength(0);
  });

  test("a send that throws (network) is released like a failed one; the rest of the run goes on", async () => {
    const calls = await fakeGoogle({ query: () => [due("r1", { fanoutTier: 1 }), due("r2", { fanoutTier: 1 })] });
    const real = globalThis.fetch;
    let first = true;
    vi.stubGlobal("fetch", (u: RequestInfo | URL, i?: RequestInit) => {
      if (String(u).includes("fcm.googleapis.com") && first) { first = false; return Promise.reject(new TypeError("network")); }
      return real(u, i);
    });
    vi.spyOn(console, "error").mockImplementation(() => {});
    const out = await sweep(await makeEnv(), NOW);
    expect(out).toMatchObject({ sent: 1, failed: 1 });
    expect(calls.batches[1]).toHaveLength(1);                       // r1's claim put back
    expect(calls.batches[1][0].update.fields.fanoutTier).toEqual({ integerValue: "1" });
  });

  test("nothing due: one query, no writes, no pushes", async () => {
    const calls = await fakeGoogle();
    const out = await sweep(await makeEnv(), NOW);
    expect(out.due).toBe(0);
    expect(calls.batches).toHaveLength(0);
    expect(calls.fcm).toHaveLength(0);
  });
});
