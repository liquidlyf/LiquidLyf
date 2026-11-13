// notify endpoints through worker.fetch: roles checked against the CURRENT state, pushes to usr_ topics.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import worker from "../src/index";
import { fakeGoogle, idToken, makeEnv, post, requestRest, resetCaches } from "./helpers";

const RID = "req123";
const responseRest = (status: string) => requestRest({ donorUid: "bob", requesterUid: "alice", status });
const bearer = async (uid: string) => ({ authorization: `Bearer ${await idToken(uid)}` });
const call = async (path: string, uid: string) => worker.fetch(post(path, await bearer(uid)), await makeEnv());
const notifyPath = `/v1/requests/${RID}/responses/bob/notify`;
const respondersPath = `/v1/requests/${RID}/notify-responders`;

describe("POST .../responses/{uid}/notify", () => {
  beforeEach(() => resetCaches());
  afterEach(() => vi.unstubAllGlobals());

  test("the donor announces a new offer to the requester", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}/responses/bob`]: responseRest("OFFERED") } });
    const res = await call(notifyPath, "bob");
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ accepted: true, type: "NEW_OFFER" });
    expect(calls.fcm[0].condition).toBe("'usr_alice' in topics");
    expect(calls.fcm[0].data).toEqual({ type: "NEW_OFFER", requestId: RID }); // nothing personal
    expect(calls.reads).toHaveLength(1);
  });

  test.each([["CONFIRMED", "OFFER_CONFIRMED"], ["DECLINED", "OFFER_DECLINED"], ["DONATED", "DONATION_RECORDED"]])(
    "the requester announces %s to the donor", async (status, type) => {
      const calls = await fakeGoogle({ docs: { [`requests/${RID}/responses/bob`]: responseRest(status) } });
      const res = await call(notifyPath, "alice");
      expect(res.status).toBe(202);
      expect(calls.fcm[0]).toMatchObject({ condition: "'usr_bob' in topics", data: { type } });
    });

  test("only what really happened: wrong status 409, a stranger 403, no doc 404", async () => {
    await fakeGoogle({ docs: { [`requests/${RID}/responses/bob`]: responseRest("CONFIRMED") } });
    expect((await call(notifyPath, "bob")).status).toBe(409);   // the donor can't announce a confirmation
    resetCaches();
    const calls = await fakeGoogle({ docs: { [`requests/${RID}/responses/bob`]: responseRest("OFFERED") } });
    expect((await call(notifyPath, "alice")).status).toBe(409); // nothing for the requester to announce yet
    expect((await call(notifyPath, "eve")).status).toBe(403);
    expect(calls.fcm).toHaveLength(0);
    resetCaches();
    await fakeGoogle();
    expect((await call(notifyPath, "bob")).status).toBe(404);
  });
});

describe("POST .../notify-responders", () => {
  beforeEach(() => resetCaches());
  afterEach(() => vi.unstubAllGlobals());

  const closedRequest = (status: string) => requestRest({
    status, responders: { bob: { status: "CONFIRMED" }, carol: { status: "OFFERED" }, dan: { status: "DONATED" }, eve: { status: "WITHDRAWN" } },
  });

  test("a fulfilled request tells the donors still waiting, in one condition", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: closedRequest("FULFILLED") } });
    const res = await call(respondersPath, "alice");
    expect(await res.json()).toEqual({ accepted: true, type: "REQUEST_FULFILLED", recipients: 2 });
    expect(calls.fcm).toHaveLength(1);
    expect(calls.fcm[0].condition).toBe("'usr_bob' in topics || 'usr_carol' in topics");
    expect(calls.fcm[0].data).toEqual({ type: "REQUEST_FULFILLED", requestId: RID });
  });

  test("more than 5 waiting donors: several conditions (FCM allows 5 topics each)", async () => {
    const responders = Object.fromEntries(Array.from({ length: 7 }, (_, i) => [`d${i}`, { status: "OFFERED" }]));
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest({ status: "CANCELLED", responders }) } });
    expect((await call(respondersPath, "alice")).status).toBe(202);
    expect(calls.fcm).toHaveLength(2);
    expect(calls.fcm[0].data.type).toBe("REQUEST_CANCELLED");
  });

  test("only the requester, only once it's closed", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: closedRequest("OPEN") } });
    expect((await call(respondersPath, "alice")).status).toBe(409);
    expect((await call(respondersPath, "bob")).status).toBe(403);
    expect(calls.fcm).toHaveLength(0);
  });
});
