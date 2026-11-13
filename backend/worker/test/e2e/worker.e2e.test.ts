// The bundled Worker in workerd (wrangler dev, env "local") against the Auth and
// Firestore emulators, over real HTTP. FCM is a logged dry run in emulator mode.
import { afterAll, beforeAll, beforeEach, describe, expect, test } from "vitest";
import { unstable_dev, type Unstable_DevWorker } from "wrangler";
import { clearFirestore, getDoc, newRequest, putDoc, signedInUser } from "./emulators";

let worker: Unstable_DevWorker;
let alice: string;
let mallory: string;
let unverified: string;

beforeAll(async () => {
  worker = await unstable_dev("src/index.ts", {
    config: "wrangler.jsonc",
    env: "local",
    ip: "127.0.0.1",
    logLevel: "warn",
    experimental: { disableExperimentalWarning: true },
  });
  alice = await signedInUser("e2e-alice");
  mallory = await signedInUser("e2e-mallory");
  unverified = await signedInUser("e2e-unverified", { verified: false });
}, 120_000);

afterAll(async () => {
  await worker?.stop();
});

beforeEach(clearFirestore);

const dispatch = (rid: string, token?: string) =>
  worker.fetch(`/v1/requests/${rid}/dispatch`, {
    method: "POST",
    headers: token ? { authorization: `Bearer ${token}` } : {},
  });

describe("Worker in workerd + emulators", () => {
  test("health", async () => {
    const res = await worker.fetch("/health");
    expect(res.status).toBe(200);
  });

  test("the owner gets 202 and the request moves to tier 1", async () => {
    await putDoc("requests/r1", newRequest("e2e-alice"));
    const res = await dispatch("r1", alice);
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ accepted: true, tier: 1, messages: 1 });
    const doc = await getDoc("requests/r1");
    expect(doc.fields.fanoutTier).toEqual({ integerValue: "1" });
    const next = new Date(doc.fields.nextTierAt.timestampValue).getTime() - Date.now();
    expect(next).toBeGreaterThan(29 * 60_000);
    expect(doc.fields.notes).toEqual({ stringValue: "" }); // nothing else touched
  });

  test("over the daily alert cap (5 in 24 h, from wrangler.jsonc): 429 dailyCap and the request is parked", async () => {
    for (let i = 1; i <= 5; i++) {
      await putDoc(`requests/old${i}`, newRequest("e2e-alice", {
        status: "CANCELLED", fanoutTier: 1, createdAt: new Date(Date.now() - i * 3_600_000),
      }));
    }
    await putDoc("requests/r6", newRequest("e2e-alice"));
    const res = await dispatch("r6", alice);
    expect(res.status).toBe(429);
    expect(await res.json()).toEqual({ error: "daily alert limit reached", dailyCap: true });
    const doc = await getDoc("requests/r6");
    expect(doc.fields.fanoutTier).toEqual({ integerValue: "0" });
    expect(doc.fields.nextTierAt).toEqual({ timestampValue: "2100-01-01T00:00:00Z" });
    expect(doc.fields.status).toEqual({ stringValue: "OPEN" });
  });

  test("a second dispatch sends nothing", async () => {
    await putDoc("requests/r1", newRequest("e2e-alice"));
    await dispatch("r1", alice);
    expect(await (await dispatch("r1", alice)).json()).toMatchObject({ alreadyDispatched: true });
  });

  test("two dispatches at once: exactly one claims the tier", async () => {
    await putDoc("requests/r1", newRequest("e2e-alice"));
    const bodies = await Promise.all([dispatch("r1", alice), dispatch("r1", alice)].map(async (p) => (await p).json()));
    expect(bodies.filter((b: any) => b.tier === 1)).toHaveLength(1);
    expect(bodies.filter((b: any) => b.alreadyDispatched)).toHaveLength(1);
  });

  test("a non-owner gets 403 and the request is unchanged", async () => {
    await putDoc("requests/r1", newRequest("e2e-alice"));
    expect((await dispatch("r1", mallory)).status).toBe(403);
    expect((await getDoc("requests/r1")).fields.fanoutTier).toEqual({ integerValue: "0" });
  });

  test("an invalid or missing token gets 401; an unverified email gets 403", async () => {
    await putDoc("requests/r1", newRequest("e2e-alice"));
    expect((await dispatch("r1", "garbage")).status).toBe(401);
    expect((await dispatch("r1")).status).toBe(401);
    expect((await dispatch("r1", unverified)).status).toBe(403);
  });

  test("an Rh-negative request starts at tier 3", async () => {
    await putDoc("requests/r2", newRequest("e2e-alice", { bloodGroup: "O_NEG", acceptedGroups: ["O_NEG"] }));
    expect(await (await dispatch("r2", alice)).json()).toMatchObject({ tier: 3 });
  });
});
