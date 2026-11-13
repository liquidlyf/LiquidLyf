import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { decodeEmulatorIdToken, verifyAppCheck, verifyIdToken } from "../src/verify";
import {
  APP_ID, PROJECT, PROJECT_NUMBER, appCheckToken, emulatorToken, fakeGoogle, idToken, keys, resetCaches, signJwt,
} from "./helpers";

beforeEach(resetCaches);
afterEach(() => vi.unstubAllGlobals());

describe("Firebase ID token", () => {
  test("a valid token verifies", async () => {
    await fakeGoogle();
    const u = await verifyIdToken(await idToken("alice"), PROJECT);
    expect(u).toMatchObject({ uid: "alice", emailVerified: true });
  });

  test("the JWKS is fetched once and cached", async () => {
    const calls = await fakeGoogle();
    await verifyIdToken(await idToken("alice"), PROJECT);
    await verifyIdToken(await idToken("bob"), PROJECT);
    expect(calls.idJwks).toBe(1);
  });

  test.each([
    ["another project (aud)", { aud: "someone-else" }],
    ["another issuer", { iss: "https://securetoken.google.com/someone-else" }],
    ["expired", { exp: Math.floor(Date.now() / 1000) - 3600 }],
    ["issued in the future", { iat: Math.floor(Date.now() / 1000) + 3600 }],
    ["empty uid", { sub: "" }],
  ])("rejected: %s", async (_name, claims) => {
    await fakeGoogle();
    await expect(verifyIdToken(await idToken("alice", claims), PROJECT)).rejects.toThrow();
  });

  test("a token signed with another key is rejected", async () => {
    await fakeGoogle();
    await expect(verifyIdToken(await idToken("alice", {}, keys.rogue.privateKey), PROJECT)).rejects.toThrow("bad signature");
  });

  test("an unknown kid is rejected", async () => {
    await fakeGoogle();
    await expect(verifyIdToken(await idToken("alice", {}, keys.id.privateKey, "nope"), PROJECT)).rejects.toThrow("unknown kid");
  });

  test("alg none and HS256 tokens are rejected", async () => {
    await fakeGoogle();
    await expect(verifyIdToken(emulatorToken("alice", { aud: PROJECT }), PROJECT)).rejects.toThrow();
    const hs = await signJwt(keys.id.privateKey, { alg: "HS256", kid: "id-1" }, { sub: "alice" });
    await expect(verifyIdToken(hs, PROJECT)).rejects.toThrow("bad header");
  });

  test("garbage is rejected", async () => {
    await fakeGoogle();
    await expect(verifyIdToken("not-a-jwt", PROJECT)).rejects.toThrow();
  });
});

describe("App Check token", () => {
  test("a valid token verifies", async () => {
    await fakeGoogle();
    await expect(verifyAppCheck(await appCheckToken(), PROJECT_NUMBER, APP_ID)).resolves.toBeUndefined();
  });

  test.each([
    ["another project", { aud: ["projects/999"] }],
    ["another issuer", { iss: "https://firebaseappcheck.googleapis.com/999" }],
    ["another app", { sub: "1:123456789:android:other" }],
    ["expired", { exp: Math.floor(Date.now() / 1000) - 3600 }],
  ])("rejected: %s", async (_name, claims) => {
    await fakeGoogle();
    await expect(verifyAppCheck(await appCheckToken(claims), PROJECT_NUMBER, APP_ID)).rejects.toThrow();
  });

  test("a forged token is rejected", async () => {
    await fakeGoogle();
    await expect(verifyAppCheck(await appCheckToken({}, keys.rogue.privateKey), PROJECT_NUMBER, APP_ID)).rejects.toThrow();
  });
});

describe("emulator ID token", () => {
  test("decodes the unsigned token and still checks aud, iss and exp", () => {
    expect(decodeEmulatorIdToken(emulatorToken("alice"), "demo-liquidlyf").uid).toBe("alice");
    expect(() => decodeEmulatorIdToken(emulatorToken("alice"), "liquidlyf-app")).toThrow("aud");
    expect(() => decodeEmulatorIdToken(emulatorToken("a", { exp: 1 }), "demo-liquidlyf")).toThrow("expired");
  });
});
