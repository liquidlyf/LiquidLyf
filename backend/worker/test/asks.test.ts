// POST /v1/requests/{rid}/asks/{donorUid}/notify (phase-06 6.2): proof of the ask, the daily cap, the push.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { ASK_TYPE, DAILY_ASK_CAP, askCapQuery } from "../src/asks";
import worker from "../src/index";
import { fakeGoogle, idToken, makeEnv, post, requestRest, resetCaches } from "./helpers";

const RID = "req123";
const path = `/v1/requests/${RID}/asks/bob/notify`;
const askPath = `users/bob/notifications/ask_${RID}`;
const ask = (o: Record<string, unknown> = {}) =>
  requestRest({ type: ASK_TYPE, fromUid: "alice", requestId: RID, createdAt: new Date(Date.now() - 5_000), ...o });
const call = async (uid: string) =>
  worker.fetch(post(path, { authorization: `Bearer ${await idToken(uid)}` }), await makeEnv());
const docs = (o: { request?: Record<string, unknown>; ask?: Record<string, unknown> | null } = {}) => ({
  [`requests/${RID}`]: requestRest(o.request ?? {}),
  ...(o.ask === null ? {} : { [askPath]: ask(o.ask ?? {}) }),
});

describe("POST .../asks/{donorUid}/notify", () => {
  beforeEach(() => resetCaches());
  afterEach(() => vi.unstubAllGlobals());

  test("the requester's ask is pushed to the donor, with nothing personal in it", async () => {
    const calls = await fakeGoogle({ docs: docs(), count: () => 1 });
    const res = await call("alice");
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ accepted: true, type: ASK_TYPE });
    expect(calls.fcm).toHaveLength(1);
    expect(calls.fcm[0].condition).toBe("'usr_bob' in topics");
    expect(calls.fcm[0].data).toEqual({ type: ASK_TYPE, requestId: RID });
    expect(calls.reads).toEqual([`requests/${RID}`, askPath]);
    expect(calls.counts).toHaveLength(1); // 3 reads in all
  });

  test("the daily cap counts the caller's asks of the last 24 h in every inbox", async () => {
    const now = new Date("2026-11-15T10:00:00Z");
    expect(askCapQuery("alice", now)).toEqual({
      collection: "notifications",
      allDescendants: true,
      where: [
        ["fromUid", "EQUAL", "alice"],
        ["type", "EQUAL", ASK_TYPE],
        ["createdAt", "GREATER_THAN", new Date("2026-11-14T10:00:00Z")],
      ],
    });
    const calls = await fakeGoogle({ docs: docs(), count: () => DAILY_ASK_CAP });
    expect((await call("alice")).status).toBe(202); // the 30th ask (this one included) is still pushed
    const q = calls.counts[0];
    expect(q.structuredQuery.from).toEqual([{ collectionId: "notifications", allDescendants: true }]);
  });

  test("over the cap: 429 with dailyAskCap, and no push (the ask stays in the inbox)", async () => {
    const calls = await fakeGoogle({ docs: docs(), count: () => DAILY_ASK_CAP + 1 });
    const res = await call("alice");
    expect(res.status).toBe(429);
    expect(await res.json()).toEqual({ error: "daily ask limit reached", dailyAskCap: true });
    expect(calls.fcm).toHaveLength(0);
  });

  test("a failed count doesn't block the ask (fails open, logged)", async () => {
    const err = vi.spyOn(console, "error").mockImplementation(() => {});
    const calls = await fakeGoogle({ docs: docs(), count: () => ({ status: 400, grpc: "FAILED_PRECONDITION" }) });
    expect((await call("alice")).status).toBe(202);
    expect(calls.fcm).toHaveLength(1);
    expect(err.mock.calls.some(([m]) => String(m).includes("askCap"))).toBe(true);
  });

  test("only the request's owner, only while it's open", async () => {
    let calls = await fakeGoogle({ docs: docs() });
    expect((await call("eve")).status).toBe(403);
    expect(calls.fcm).toHaveLength(0);
    resetCaches();
    calls = await fakeGoogle({ docs: docs({ request: { status: "FULFILLED" } }) });
    expect((await call("alice")).status).toBe(409);
    resetCaches();
    await fakeGoogle();
    expect((await call("alice")).status).toBe(404);
  });

  test("only an ask that really exists, from the caller, and is fresh", async () => {
    for (const a of [null, { fromUid: "eve" }, { type: "NEW_OFFER" }, { createdAt: new Date(Date.now() - 11 * 60_000) }]) {
      resetCaches();
      const calls = await fakeGoogle({ docs: docs({ ask: a }) });
      const res = await call("alice");
      expect(res.status, JSON.stringify(a)).toBe(409);
      expect(calls.fcm).toHaveLength(0);
    }
  });

  test("FCM failing is a 503 so the app retries", async () => {
    await fakeGoogle({ docs: docs(), fcmStatus: [503, 503, 503] });
    expect((await call("alice")).status).toBe(503);
  });
});
