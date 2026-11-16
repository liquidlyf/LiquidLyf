// Caller checks on EVERY route the app calls (phase-09 9.1 "auth checks"), not only dispatch:
// each must refuse a bad caller before it costs a Firestore read, an OAuth mint or a push.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import worker from "../src/index";
import {
  appCheckToken, emulatorToken, fakeGoogle, idToken, keys, makeEnv, post, requestRest, resetCaches,
} from "./helpers";

const RID = "req123";
const ROUTES = [
  ["dispatch", `/v1/requests/${RID}/dispatch`],
  ["notify (one response)", `/v1/requests/${RID}/responses/bob/notify`],
  ["notify-responders", `/v1/requests/${RID}/notify-responders`],
  ["ask notify", `/v1/requests/${RID}/asks/bob/notify`],
] as const;

// Docs a route would read if the caller got through: a leak would show up as reads or pushes.
const docs = {
  [`requests/${RID}`]: requestRest({ status: "CANCELLED", responders: { bob: { status: "OFFERED" } } }),
  [`requests/${RID}/responses/bob`]: requestRest({ donorUid: "bob", requesterUid: "alice", status: "OFFERED" }),
  [`users/bob/notifications/ask_${RID}`]: requestRest({ type: "ASKED_TO_DONATE", fromUid: "alice", requestId: RID, createdAt: new Date() }),
};
const bearer = async (uid: string, claims = {}) => `Bearer ${await idToken(uid, claims)}`;
const call = async (path: string, headers: Record<string, string>, env = {}) =>
  worker.fetch(post(path, headers), await makeEnv(env));

/** Nothing behind the caller check ran: no read, query, count, write, OAuth token or push. */
function expectUntouched(calls: Awaited<ReturnType<typeof fakeGoogle>>) {
  expect(calls.reads).toHaveLength(0);
  expect(calls.queries).toHaveLength(0);
  expect(calls.counts).toHaveLength(0);
  expect(calls.writes).toHaveLength(0);
  expect(calls.token).toBe(0);
  expect(calls.fcm).toHaveLength(0);
}

beforeEach(resetCaches);
afterEach(() => vi.unstubAllGlobals());

describe.each(ROUTES)("%s: the caller is checked first", (_name, path) => {
  test("no ID token, garbage, or a token signed by someone else: 401", async () => {
    const calls = await fakeGoogle({ docs });
    expect((await call(path, {})).status).toBe(401);
    expect((await call(path, { authorization: "Bearer garbage" })).status).toBe(401);
    expect((await call(path, { authorization: "Basic YWxpY2U6eA==" })).status).toBe(401);
    expect((await call(path, { authorization: `Bearer ${await idToken("alice", {}, keys.rogue.privateKey)}` })).status).toBe(401);
    expect((await call(path, { authorization: `Bearer ${emulatorToken("alice", { aud: "liquidlyf-test" })}` })).status).toBe(401); // unsigned
    expect((await call(path, { authorization: await bearer("alice", { aud: "another-project" }) })).status).toBe(401);
    expectUntouched(calls);
  });

  test("an unverified email or a banned account: 403", async () => {
    const calls = await fakeGoogle({ docs });
    expect((await call(path, { authorization: await bearer("alice", { email_verified: false }) })).status).toBe(403);
    expect((await call(path, { authorization: await bearer("alice", { banned: true }) })).status).toBe(403);
    expectUntouched(calls);
  });

  test("App Check enforced: no token, a forged one, or another app's: 401", async () => {
    const calls = await fakeGoogle({ docs });
    const enforce = { ENFORCE_APP_CHECK: "true" };
    const authorization = await bearer("alice");
    expect((await call(path, { authorization }, enforce)).status).toBe(401);
    expect((await call(path, { authorization, "x-firebase-appcheck": await appCheckToken({}, keys.rogue.privateKey) }, enforce)).status).toBe(401);
    expect((await call(path, { authorization, "x-firebase-appcheck": await appCheckToken({ sub: "1:123456789:android:other" }) }, enforce)).status).toBe(401);
    expectUntouched(calls);
  });

  test("a misconfigured live Worker (emulator host set on a real project) accepts no token at all", async () => {
    const calls = await fakeGoogle({ docs });
    const misconfigured = { FIRESTORE_EMULATOR_HOST: "127.0.0.1:8085" }; // FIREBASE_PROJECT_ID stays a real one
    const unsigned = await call(path, { authorization: `Bearer ${emulatorToken("alice", { aud: "liquidlyf-test" })}` }, misconfigured);
    const signed = await call(path, { authorization: await bearer("alice") }, misconfigured);
    expect([unsigned.status, signed.status]).toEqual([500, 500]);
    expect(await unsigned.json()).toEqual({ error: "internal error" }); // no details
    expectUntouched(calls);
  });
});

describe("rate limit", () => {
  test("is per account: one account at its limit doesn't block another", async () => {
    await fakeGoogle({ docs: { [`requests/${RID}`]: requestRest({ fanoutTier: 1 }) } }); // already sent: cheap 202
    const env = await makeEnv();
    const [alice, bob] = [await bearer("alice"), await bearer("bob")];
    const path = ROUTES[0][1];
    for (let i = 0; i < 10; i++) await worker.fetch(post(path, { authorization: alice }), env);
    expect((await worker.fetch(post(path, { authorization: alice }), env)).status).toBe(429);
    expect((await worker.fetch(post(path, { authorization: bob }), env)).status).toBe(403); // checked normally: not bob's request
  });

  test("applies to the notify routes too", async () => {
    const calls = await fakeGoogle({ docs });
    const env = await makeEnv();
    const authorization = await bearer("eve");
    const codes: number[] = [];
    for (let i = 0; i < 11; i++) codes.push((await worker.fetch(post(ROUTES[1][1], { authorization }), env)).status);
    expect(codes.slice(0, 10).every((c) => c === 403)).toBe(true); // a stranger, refused after 1 read each
    expect(codes[10]).toBe(429);
    expect(calls.reads).toHaveLength(10);
  });
});
