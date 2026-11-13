// Expiry: OPEN past expiresAt -> EXPIRED + userStats -1 in one atomic commit.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { expireOverdue } from "../src/expiry";
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
});
