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

  test("inbox items after 60 days (all users), reports and the admin log after 1 year: deleted", async () => {
    const calls = await fakeGoogle({
      query: (q) => q.from[0].collectionId === "notifications" ? [{ name: docName("users/u1/notifications/n1"), fields: {}, updateTime: "x" }]
        : q.from[0].collectionId === "reports" ? [{ name: docName("reports/a_r1"), fields: {}, updateTime: "x" }]
        : q.from[0].collectionId === "adminLog" ? [{ name: docName("adminLog/l1"), fields: {}, updateTime: "x" }] : [],
    });
    const out = await retention(await makeEnv(), NOW);
    expect(out).toMatchObject({ inbox: 1, reports: 1, adminLog: 1 });
    const logQuery = calls.queries.find((q) => q.from[0].collectionId === "adminLog");
    expect(logQuery.from[0].allDescendants).toBe(false);
    expect(JSON.stringify(logQuery.where)).toContain(daysAgo(365).toISOString());
    const inboxQuery = calls.queries.find((q) => q.from[0].collectionId === "notifications");
    expect(inboxQuery.from[0].allDescendants).toBe(true);
    expect(JSON.stringify(inboxQuery.where)).toContain(daysAgo(60).toISOString());
    const reportQuery = calls.queries.find((q) => q.from[0].collectionId === "reports");
    expect(JSON.stringify(reportQuery.where)).toContain(daysAgo(365).toISOString());
    expect(calls.batches.flat()).toEqual([{ delete: docName("users/u1/notifications/n1") }, { delete: docName("reports/a_r1") },
      { delete: docName("adminLog/l1") }]);
  });

  describe("D23: donors who can't give blood leave Find donors", () => {
    const user = (uid: string) => ({ name: docName(`users/${uid}`), fields: {}, updateTime: "x" });
    const usersQuery = (q: any, field: string) =>
      q.from[0].collectionId === "users" && JSON.stringify(q.where).includes(`"${field}"`);

    test("over 60 (UTC year) and weight না: the profile update and the card delete go in one commit each", async () => {
      const calls = await fakeGoogle({
        query: (q) => usersQuery(q, "birthYear") ? [user("old1")] : usersQuery(q, "weightOk") ? [user("light1")] : [],
      });
      const out = await retention(await makeEnv(), NOW);
      expect(out.unlisted).toBe(2);

      const [age, weight] = calls.queries.filter((q) => q.from[0].collectionId === "users");
      expect(age.where.compositeFilter.filters).toEqual([
        { fieldFilter: { field: { fieldPath: "showInDonorSearch" }, op: "EQUAL", value: { booleanValue: true } } },
        { fieldFilter: { field: { fieldPath: "birthYear" }, op: "LESS_THAN", value: { integerValue: "1966" } } }, // 2026 − 60
      ]);
      expect(weight.where.compositeFilter.filters[1]).toEqual(
        { fieldFilter: { field: { fieldPath: "weightOk" }, op: "EQUAL", value: { booleanValue: false } } });

      expect(calls.writes).toHaveLength(2);
      for (const [i, uid] of ["old1", "light1"].entries()) {
        expect(calls.writes[i]).toEqual([
          {
            update: { name: docName(`users/${uid}`), fields: { showInDonorSearch: { booleanValue: false } } },
            updateMask: { fieldPaths: ["showInDonorSearch"] },
            updateTransforms: [{ fieldPath: "updatedAt", setToServerValue: "REQUEST_TIME" }],
            currentDocument: { exists: true }, // never creates a profile
          },
          { delete: docName(`donors/${uid}`) },
        ]);
      }
      expect(JSON.stringify(calls.writes)).not.toContain("available"); // availability stays as it was
    });

    test("nobody to unlist: no commit; a full page is followed by at most one more per query", async () => {
      let calls = await fakeGoogle();
      expect((await retention(await makeEnv(), NOW)).unlisted).toBe(0);
      expect(calls.writes).toHaveLength(0);

      resetCaches();
      vi.unstubAllGlobals();
      const page = (p: string) => Array.from({ length: 100 }, (_, i) => user(`${p}${i}`));
      calls = await fakeGoogle({ query: (q) => usersQuery(q, "birthYear") ? page("o") : usersQuery(q, "weightOk") ? page("w") : [] });
      expect((await retention(await makeEnv(), NOW)).unlisted).toBe(400);
      expect(calls.queries.filter((q) => q.from[0].collectionId === "users")).toHaveLength(4);
      expect(calls.writes.map((w) => w.length)).toEqual([200, 200, 200, 200]);
    });
  });
});
