// Retention clean-up (B6 = a): what is stripped, what stays, and the cursor.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { retention, CURSOR_KEY, RETENTION_DAYS } from "../src/retention";
import { docName, fakeGoogle, makeEnv, requestRest, resetCaches } from "./helpers";

const NOW = new Date("2026-11-20T03:00:00Z");
const daysAgo = (d: number) => new Date(NOW.getTime() - d * 86_400_000);
const closed = (rid: string, o: Record<string, unknown> = {}) => ({
  name: docName(`requests/${rid}`),
  ...requestRest({ status: "FULFILLED", updatedAt: daysAgo(100), responders: { bob: { status: "DONATED" }, carol: { status: "OFFERED" } }, ...o }),
});

describe("retention", () => {
  beforeEach(() => resetCaches());
  afterEach(() => vi.unstubAllGlobals());

  test("a request closed > 90 days ago loses contact, phones, messages, notes and closing reason; names stay", async () => {
    const calls = await fakeGoogle({ query: (q) => q.from[0].collectionId === "requests" ? [closed("r1")] : [] });
    const env = await makeEnv();
    const out = await retention(env, NOW);
    expect(out).toMatchObject({ requests: 1, responses: 2 });

    const q = calls.queries[0];
    expect(JSON.stringify(q.where)).toContain(daysAgo(RETENTION_DAYS.closedRequest).toISOString());
    const writes = calls.batches[0];
    expect(writes).toContainEqual({ delete: docName("requests/r1/private/contact") });
    const reqWrite = writes.find((w: any) => w.update?.name === docName("requests/r1"));
    expect(reqWrite.updateMask.fieldPaths).toEqual(["notes", "closedReason"]);
    expect(reqWrite.update.fields).toEqual({}); // in the mask, no value: deleted
    expect(reqWrite.currentDocument).toEqual({ exists: true });
    for (const u of ["bob", "carol"]) {
      const w = writes.find((x: any) => x.update?.name === docName(`requests/r1/responses/${u}`));
      expect(w.updateMask.fieldPaths).toEqual(["donorPhone", "message"]);
      expect(w.update.fields).toEqual({});
    }
    // Nothing touches names, updatedAt or the responders map.
    expect(JSON.stringify(writes)).not.toMatch(/requesterDisplayName|donorDisplayName|updatedAt|responders/);
    // The cursor moves past this request.
    expect(JSON.parse((env.CACHE as any).store.get(CURSOR_KEY))).toEqual({ t: daysAgo(100).toISOString(), name: docName("requests/r1") });
  });

  test("the next run starts after the cursor", async () => {
    const calls = await fakeGoogle();
    const env = await makeEnv();
    await env.CACHE.put(CURSOR_KEY, JSON.stringify({ t: daysAgo(100).toISOString(), name: docName("requests/r1") }));
    await retention(env, NOW);
    expect(calls.queries[0].startAt).toEqual({
      values: [{ timestampValue: daysAgo(100).toISOString() }, { referenceValue: docName("requests/r1") }], before: false,
    });
  });

  test("an OPEN request is never stripped", async () => {
    const calls = await fakeGoogle({ query: (q) => q.from[0].collectionId === "requests" ? [closed("r1", { status: "OPEN" })] : [] });
    const out = await retention(await makeEnv(), NOW);
    expect(out.requests).toBe(0);
    expect(calls.batches.flat()).toHaveLength(0);
  });

  test("inbox items after 60 days (all users), reports after 1 year: deleted", async () => {
    const calls = await fakeGoogle({
      query: (q) => q.from[0].collectionId === "notifications" ? [{ name: docName("users/u1/notifications/n1"), fields: {}, updateTime: "x" }]
        : q.from[0].collectionId === "reports" ? [{ name: docName("reports/a_r1"), fields: {}, updateTime: "x" }] : [],
    });
    const out = await retention(await makeEnv(), NOW);
    expect(out).toMatchObject({ inbox: 1, reports: 1 });
    const inboxQuery = calls.queries.find((q) => q.from[0].collectionId === "notifications");
    expect(inboxQuery.from[0].allDescendants).toBe(true);
    expect(JSON.stringify(inboxQuery.where)).toContain(daysAgo(60).toISOString());
    const reportQuery = calls.queries.find((q) => q.from[0].collectionId === "reports");
    expect(JSON.stringify(reportQuery.where)).toContain(daysAgo(365).toISOString());
    expect(calls.batches.flat()).toEqual([{ delete: docName("users/u1/notifications/n1") }, { delete: docName("reports/a_r1") }]);
  });
});
