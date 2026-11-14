// New-report alert to the admins topic (phase-07 7.3): cursor in KV, one COUNT, one push.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { ADMINS_CONDITION, alertNewReports, REPORT_COUNT_UPTO, REPORTS_CURSOR_KEY } from "../src/reports";
import { fakeGoogle, fakeKv, makeEnv, resetCaches } from "./helpers";

const T0 = new Date("2026-11-20T06:02:00Z");
const T1 = new Date("2026-11-20T06:07:00Z");
const cursorOf = (kv: ReturnType<typeof fakeKv>) => JSON.parse(kv.store.get(REPORTS_CURSOR_KEY) ?? "null");

async function envWithCursor(at: Date | string | null) {
  const kv = fakeKv();
  if (at !== null) await kv.put(REPORTS_CURSOR_KEY, JSON.stringify(at instanceof Date ? at.toISOString() : at));
  return { kv, env: await makeEnv({ CACHE: kv }) };
}

describe("report alerts", () => {
  beforeEach(() => resetCaches());
  afterEach(() => vi.unstubAllGlobals());

  test("first run: starts the cursor at now, alerts nothing and reads nothing", async () => {
    const calls = await fakeGoogle({ count: () => 5 });
    const { kv, env } = await envWithCursor(null);
    expect(await alertNewReports(env, T1)).toEqual({ new: 0, sent: false, started: true });
    expect(cursorOf(kv)).toBe(T1.toISOString());
    expect(calls.counts).toHaveLength(0);
    expect(calls.fcm).toHaveLength(0);
  });

  test("no new reports: one COUNT over (cursor, now], no push, cursor kept (no KV write)", async () => {
    const calls = await fakeGoogle({ count: () => 0 });
    const { kv, env } = await envWithCursor(T0);
    const put = vi.spyOn(kv, "put");
    expect(await alertNewReports(env, T1)).toEqual({ new: 0, sent: false });
    expect(calls.counts).toHaveLength(1);
    const q = calls.counts[0].structuredQuery;
    expect(q.from).toEqual([expect.objectContaining({ collectionId: "reports", allDescendants: false })]);
    const filters = JSON.stringify(q.where);
    expect(filters).toContain(`"GREATER_THAN","value":{"timestampValue":"${T0.toISOString()}"}`);
    expect(filters).toContain(`"LESS_THAN_OR_EQUAL","value":{"timestampValue":"${T1.toISOString()}"}`);
    expect(calls.counts[0].aggregations[0].count.upTo).toBe(String(REPORT_COUNT_UPTO));
    expect(calls.fcm).toHaveLength(0);
    expect(put.mock.calls.filter(([key]) => key === REPORTS_CURSOR_KEY)).toHaveLength(0); // (the token cache may write)
  });

  test("new reports: one push to the admins topic with only a count, then the cursor moves to now", async () => {
    const calls = await fakeGoogle({ count: () => 3 });
    const { kv, env } = await envWithCursor(T0);
    expect(await alertNewReports(env, T1)).toEqual({ new: 3, sent: true });
    expect(calls.fcm).toHaveLength(1);
    expect(calls.fcm[0].condition).toBe(ADMINS_CONDITION);
    expect(calls.fcm[0].data).toEqual({ type: "NEW_REPORTS", count: "3" }); // no IDs, names or notes
    expect(calls.fcm[0].android.collapse_key).toBe("admin_reports");
    expect(cursorOf(kv)).toBe(T1.toISOString());
  });

  test("100 or more says 100+", async () => {
    const calls = await fakeGoogle({ count: () => REPORT_COUNT_UPTO });
    const { env } = await envWithCursor(T0);
    await alertNewReports(env, T1);
    expect(calls.fcm[0].data.count).toBe(`${REPORT_COUNT_UPTO}+`);
  });

  test("FCM busy (503): the cursor stays, so the next run alerts the same reports", async () => {
    const calls = await fakeGoogle({ count: () => 2, fcmStatus: [503, 200] });
    const { kv, env } = await envWithCursor(T0);
    expect(await alertNewReports(env, T1)).toEqual({ new: 2, sent: false, retry: true });
    expect(cursorOf(kv)).toBe(T0.toISOString());
    const T2 = new Date(T1.getTime() + 5 * 60_000);
    expect(await alertNewReports(env, T2)).toEqual({ new: 2, sent: true });
    expect(JSON.stringify(calls.counts[1].structuredQuery.where)).toContain(T0.toISOString());
    expect(cursorOf(kv)).toBe(T2.toISOString());
  });

  test("FCM rejects the message (400): the cursor moves on, so it can't fail every 5 minutes", async () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => {});
    await fakeGoogle({ count: () => 1, fcmStatus: [400] });
    const { kv, env } = await envWithCursor(T0);
    expect(await alertNewReports(env, T1)).toEqual({ new: 1, sent: false });
    expect(cursorOf(kv)).toBe(T1.toISOString());
    expect(error).toHaveBeenCalled();
  });

  test.each([["not a date"], ["2099-01-01T00:00:00.000Z"]])("a bad or future cursor (%s) restarts at now", async (bad) => {
    const calls = await fakeGoogle({ count: () => 4 });
    const { kv, env } = await envWithCursor(bad);
    expect(await alertNewReports(env, T1)).toMatchObject({ started: true });
    expect(cursorOf(kv)).toBe(T1.toISOString());
    expect(calls.counts).toHaveLength(0);
  });

  test("the COUNT fails: the run fails (dashboard) and the cursor stays", async () => {
    await fakeGoogle({ count: () => ({ status: 503, grpc: "UNAVAILABLE" }) });
    const { kv, env } = await envWithCursor(T0);
    await expect(alertNewReports(env, T1)).rejects.toThrow();
    expect(cursorOf(kv)).toBe(T0.toISOString());
  });
});
