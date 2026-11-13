// The dispatch endpoint end to end through worker.fetch, with Google mocked.
// Phase 2 acceptance: owner 202 + the tier-1 push, non-owner 403, invalid token 401,
// and each dispatch costs at most 2 Firestore reads + 1 write.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import worker from "../src/index";
import {
  APP_ID, appCheckToken, fakeGoogle, idToken, keys, makeEnv, post, requestRest, resetCaches,
} from "./helpers";

const RID = "req123";
const PATH = `/v1/requests/${RID}/dispatch`;

beforeEach(resetCaches);
afterEach(() => vi.unstubAllGlobals());

async function call(headers: Record<string, string>, envOverrides = {}, path = PATH) {
  return worker.fetch(post(path, headers), await makeEnv(envOverrides));
}
const bearer = async (uid: string, claims = {}) => ({ authorization: `Bearer ${await idToken(uid, claims)}` });

describe("dispatch: the owner", () => {
  test("gets 202 and the tier-1 topic message is sent, for 1 read + 1 write", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    const res = await call(await bearer("alice"));
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ accepted: true, tier: 1, messages: 1 });

    expect(calls.fcm).toHaveLength(1);
    expect(calls.fcm[0].condition).toBe("'up_BD30262000_B_POS' in topics");
    expect(calls.fcm[0].data).toEqual({
      type: "NEW_REQUEST", requestId: RID, bloodGroup: "B_POS", district: "BD3026", urgency: "URGENT", tier: "1",
    });
    expect(calls.fcm[0].android).toMatchObject({ priority: "HIGH", collapse_key: RID });

    expect(calls.reads).toEqual([`requests/${RID}`]);
    expect(calls.writes).toHaveLength(1);
    expect(calls.reads.length).toBeLessThanOrEqual(2);
  });

  test("the claim write uses an updateTime precondition and touches only the fan-out fields", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    await call(await bearer("alice"));
    const [w] = calls.writes[0];
    expect(w.currentDocument).toEqual({ updateTime: requestRest().updateTime });
    expect(w.updateMask.fieldPaths).toEqual(["fanoutTier", "nextTierAt"]);
    expect(w.update.fields.fanoutTier).toEqual({ integerValue: "1" });
    const next = new Date(w.update.fields.nextTierAt.timestampValue).getTime();
    expect(next - Date.now()).toBeGreaterThan(29 * 60_000); // URGENT: 30 minutes
    expect(next - Date.now()).toBeLessThan(31 * 60_000);
  });

  test("an Rh-negative request starts at tier 3 (district)", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest({ bloodGroup: "O_NEG", acceptedGroups: ["O_NEG"], urgency: "CRITICAL" }) } });
    const res = await call(await bearer("alice"));
    expect(await res.json()).toMatchObject({ tier: 3 });
    expect(calls.fcm[0].condition).toBe("'di_BD3026_O_NEG' in topics");
  });

  test("a repeated dispatch is accepted but sends nothing", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest({ fanoutTier: 1 }) } });
    const res = await call(await bearer("alice"));
    expect(res.status).toBe(202);
    expect(await res.json()).toMatchObject({ alreadyDispatched: true });
    expect(calls.fcm).toHaveLength(0);
    expect(calls.writes).toHaveLength(0);
  });

  test("losing the precondition race sends nothing", async () => {
    const calls = await fakeGoogle({
      docs: { [`requests/${RID}`]: requestRest() },
      commitError: { status: 400, grpc: "FAILED_PRECONDITION" },
    });
    const res = await call(await bearer("alice"));
    expect(res.status).toBe(202);
    expect(await res.json()).toMatchObject({ alreadyDispatched: true });
    expect(calls.fcm).toHaveLength(0);
  });

  test("if FCM fails, the claim is released and the app is told to retry", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() }, fcmStatus: [503] });
    const res = await call(await bearer("alice"));
    expect(res.status).toBe(503);
    expect(calls.writes).toHaveLength(2);
    const [release] = calls.writes[1];
    expect(release.update.fields.fanoutTier).toEqual({ integerValue: "0" });
    expect(release.currentDocument.updateTime).toBeDefined();
  });

  test("a closed request is refused", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest({ status: "CANCELLED" }) } });
    expect((await call(await bearer("alice"))).status).toBe(409);
    expect(calls.fcm).toHaveLength(0);
  });

  test("a request older than 10 minutes is left to the sweep", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest({ createdAt: new Date(Date.now() - 11 * 60_000) }) } });
    expect((await call(await bearer("alice"))).status).toBe(409);
    expect(calls.fcm).toHaveLength(0);
  });

  test("a missing request is 404", async () => {
    await fakeGoogle({ docs: {} });
    expect((await call(await bearer("alice"))).status).toBe(404);
  });

  test("bad area codes in the stored request are refused, not sent", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest({ upazila: "x' in topics || 'y" }) } });
    expect((await call(await bearer("alice"))).status).toBe(422);
    expect(calls.fcm).toHaveLength(0);
  });
});

describe("dispatch: everyone else", () => {
  test("a non-owner gets 403, with no write and no push", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    expect((await call(await bearer("mallory"))).status).toBe(403);
    expect(calls.writes).toHaveLength(0);
    expect(calls.fcm).toHaveLength(0);
  });

  test("an invalid token gets 401 before any Firestore call", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    expect((await call({ authorization: "Bearer garbage" })).status).toBe(401);
    expect((await call({ authorization: `Bearer ${await idToken("alice", {}, keys.rogue.privateKey)}` })).status).toBe(401);
    expect((await call({})).status).toBe(401);
    expect(calls.reads).toHaveLength(0);
    expect(calls.token).toBe(0);
  });

  test("an unverified email gets 403", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    expect((await call(await bearer("alice", { email_verified: false }))).status).toBe(403);
    expect(calls.reads).toHaveLength(0);
  });

  test("a banned account gets 403", async () => {
    await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    expect((await call(await bearer("alice", { banned: true }))).status).toBe(403);
  });

  test("more than 10 calls a minute get 429, before any Firestore call", async () => {
    const calls = await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest({ fanoutTier: 1 }) } });
    const env = await makeEnv();
    const h = await bearer("alice");
    const codes = [];
    for (let i = 0; i < 12; i++) codes.push((await worker.fetch(post(PATH, h), env)).status);
    expect(codes.slice(0, 10).every((c) => c === 202)).toBe(true);
    expect(codes.slice(10)).toEqual([429, 429]);
    expect(calls.reads).toHaveLength(10);
  });

  test("the Rate Limiting binding is used when present", async () => {
    await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    const RATE_LIMITER = { limit: vi.fn(async () => ({ success: false })) };
    expect((await call(await bearer("alice"), { RATE_LIMITER })).status).toBe(429);
    expect(RATE_LIMITER.limit).toHaveBeenCalledWith({ key: "alice" });
  });
});

describe("App Check", () => {
  test("monitor mode: a missing or bad token is logged, not blocked", async () => {
    await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    expect((await call(await bearer("alice"))).status).toBe(202);
    resetCaches();
    await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    expect((await call({ ...(await bearer("alice")), "x-firebase-appcheck": "bad" })).status).toBe(202);
  });

  test("enforced: no token or a forged one gets 401; a valid one passes", async () => {
    await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    const enforce = { ENFORCE_APP_CHECK: "true" };
    expect((await call(await bearer("alice"), enforce)).status).toBe(401);
    expect((await call({ ...(await bearer("alice")), "x-firebase-appcheck": await appCheckToken({}, keys.rogue.privateKey) }, enforce)).status).toBe(401);
    expect((await call({ ...(await bearer("alice")), "x-firebase-appcheck": await appCheckToken() }, enforce)).status).toBe(202);
  });

  test("enforced: ANDROID_APP_ID may list several apps; a token from any other app gets 401", async () => {
    await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    const listed = { ENFORCE_APP_CHECK: "true", ANDROID_APP_ID: `1:123456789:android:devbuild , ${APP_ID}` };
    expect((await call({ ...(await bearer("alice")), "x-firebase-appcheck": await appCheckToken({ sub: "1:123456789:android:stranger" }) }, listed)).status).toBe(401);
    expect((await call({ ...(await bearer("alice")), "x-firebase-appcheck": await appCheckToken() }, listed)).status).toBe(202);
  });
});

describe("routing", () => {
  test("GET /health is 200 and needs no auth", async () => {
    const calls = await fakeGoogle();
    const res = await worker.fetch(new Request("https://worker.test/health"), await makeEnv());
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true, service: "liquidlyf-worker" });
    expect(calls.reads).toHaveLength(0);
  });

  test("unknown paths, odd IDs and wrong methods are rejected before auth", async () => {
    const calls = await fakeGoogle();
    expect((await call({}, {}, "/v1/requests/a%2Fb/dispatch")).status).toBe(404);
    expect((await call({}, {}, "/v1/requests/../dispatch")).status).toBe(404);
    expect((await call({}, {}, "/admin")).status).toBe(404);
    expect((await worker.fetch(new Request(`https://worker.test${PATH}`), await makeEnv())).status).toBe(405);
    expect(calls.idJwks).toBe(0);
  });

  test("an internal error is a plain 500 without details", async () => {
    await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest() } });
    const res = await call(await bearer("alice"), { GCP_SA_JSON: undefined });
    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ error: "internal error" });
  });
});
