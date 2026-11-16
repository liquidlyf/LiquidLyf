// Expiry: OPEN past expiresAt -> EXPIRED + userStats -1 in one atomic commit.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { EXPIRY_LIMIT, expireOverdue } from "../src/expiry";
import { FAR_FUTURE } from "../src/fanout";
import { docName, fakeGoogle, makeEnv, requestRest, resetCaches } from "./helpers";

const NOW = new Date("2026-11-20T06:07:00Z");
const overdue = (rid: string, uid = "alice") =>
  ({ name: docName(`requests/${rid}`), ...requestRest({ requesterUid: uid }), updateTime: "2026-11-19T00:00:00.000001Z" });
const PRECONDITION = { status: 400, grpc: "FAILED_PRECONDITION" };

describe("expiry", () => {
  beforeEach(() => resetCaches());
  afterEach(() => vi.unstubAllGlobals());

  test("expires with the userStats -1 in the same commit, guarded by updateTime; updatedAt = server time", async () => {
    const calls = await fakeGoogle({ query: () => [overdue("r1")] });
    const out = await expireOverdue(await makeEnv(), NOW);
    expect(out).toMatchObject({ due: 1, expired: 1, lost: 0, withoutStats: 0 });
    expect(JSON.stringify(calls.queries[0].where)).toContain('"expiresAt"');
    const [expire, stats] = calls.writes[0];
    expect(expire.update.fields.status).toEqual({ stringValue: "EXPIRED" });
    expect(expire.update.fields.nextTierAt).toEqual({ timestampValue: FAR_FUTURE.toISOString() });
    expect(expire.updateMask.fieldPaths.sort()).toEqual(["nextTierAt", "status"]);
    expect(expire.updateTransforms).toEqual([{ fieldPath: "updatedAt", setToServerValue: "REQUEST_TIME" }]);
    expect(expire.currentDocument).toEqual({ updateTime: "2026-11-19T00:00:00.000001Z" });
    expect(stats.transform.document).toBe(docName("userStats/alice"));
    expect(stats.transform.fieldTransforms).toEqual([{ fieldPath: "openRequests", increment: { integerValue: "-1" } }]);
    expect(stats.currentDocument).toEqual({ exists: true }); // never creates a userStats doc
  });

  test("the owner closed it meanwhile: both attempts lose, nothing changes", async () => {
    const calls = await fakeGoogle({ query: () => [overdue("r1")], commitErrors: [PRECONDITION, PRECONDITION] });
    const out = await expireOverdue(await makeEnv(), NOW);
    expect(out).toMatchObject({ expired: 0, lost: 1 });
    expect(calls.writes).toHaveLength(2);
  });

  test("userStats is missing: the request still expires, without the -1", async () => {
    // exists:true on a missing doc fails the commit with NOT_FOUND (seen on the emulator).
    const calls = await fakeGoogle({ query: () => [overdue("r1")], commitErrors: [{ status: 404, grpc: "NOT_FOUND" }, null] });
    const out = await expireOverdue(await makeEnv(), NOW);
    expect(out).toMatchObject({ expired: 1, withoutStats: 1 });
    expect(calls.writes[1]).toHaveLength(1);
  });

  test("a request with a strange requesterUid expires without touching userStats", async () => {
    const calls = await fakeGoogle({ query: () => [overdue("r1", "../evil")] });
    await expireOverdue(await makeEnv(), NOW);
    expect(calls.writes[0]).toHaveLength(1);
  });

  test("asks only for OPEN requests past expiresAt, oldest first, at most EXPIRY_LIMIT, reading only the owner", async () => {
    const calls = await fakeGoogle();
    await expireOverdue(await makeEnv(), NOW);
    const q = calls.queries[0];
    expect(q.from).toEqual([{ collectionId: "requests", allDescendants: false }]);
    // Only OPEN: a hidden (REMOVED) request keeps its slot until an admin acts (firebase/README.md).
    expect(q.where.compositeFilter.filters).toEqual([
      { fieldFilter: { field: { fieldPath: "status" }, op: "EQUAL", value: { stringValue: "OPEN" } } },
      { fieldFilter: { field: { fieldPath: "expiresAt" }, op: "LESS_THAN_OR_EQUAL", value: { timestampValue: NOW.toISOString() } } },
    ]);
    expect(q.orderBy).toEqual([{ field: { fieldPath: "expiresAt" }, direction: "ASCENDING" }]);
    expect(q.limit).toBe(EXPIRY_LIMIT);
    expect(EXPIRY_LIMIT).toBeLessThanOrEqual(40); // one commit each, inside the free plan's 50 subrequests
    expect(q.select).toEqual({ fields: [{ fieldPath: "requesterUid" }] });
  });

  test("nothing due: one query, no writes", async () => {
    const calls = await fakeGoogle();
    expect(await expireOverdue(await makeEnv(), NOW)).toEqual({ due: 0, expired: 0, lost: 0, withoutStats: 0 });
    expect(calls.queries).toHaveLength(1);
    expect(calls.writes).toHaveLength(0);
  });

  test("several due: each is its own commit, and one the owner closed doesn't stop the others", async () => {
    const calls = await fakeGoogle({
      query: () => [overdue("r1", "alice"), overdue("r2", "bob"), overdue("r3", "carol")],
      commitErrors: [null, PRECONDITION, PRECONDITION, null], // r2 lost both attempts
    });
    const out = await expireOverdue(await makeEnv(), NOW);
    expect(out).toEqual({ due: 3, expired: 2, lost: 1, withoutStats: 0 });
    expect(calls.writes.map((w) => w[0].update.name)).toEqual(
      [docName("requests/r1"), docName("requests/r2"), docName("requests/r2"), docName("requests/r3")]);
    expect(calls.writes[3][1].transform.document).toBe(docName("userStats/carol")); // each owner's own -1
  });

  test("any other Firestore error fails the run, so the dashboard shows it", async () => {
    await fakeGoogle({ query: () => [overdue("r1")], commitErrors: [{ status: 500, grpc: "INTERNAL" }] });
    await expect(expireOverdue(await makeEnv(), NOW)).rejects.toThrow();
  });
});
