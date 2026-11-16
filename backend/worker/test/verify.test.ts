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
    ["signed in in the future (auth_time)", { auth_time: Math.floor(Date.now() / 1000) + 3600 }],
    ["a uid over 128 characters", { sub: "u".repeat(129) }],
    ["a uid that isn't a string", { sub: 42 }],
    ["no expiry", { exp: undefined }],
  ])("rejected: %s", async (_name, claims) => {
    await fakeGoogle();
    await expect(verifyIdToken(await idToken("alice", claims), PROJECT)).rejects.toThrow();
  });

  test("within the 60 s clock-skew leeway, a token is still accepted", async () => {
    await fakeGoogle();
    const now = Math.floor(Date.now() / 1000);
    await expect(verifyIdToken(await idToken("alice", { exp: now - 30, iat: now + 30 }), PROJECT)).resolves.toMatchObject({ uid: "alice" });
  });

  test("a token over 4 KB is refused before any key is fetched", async () => {
    const calls = await fakeGoogle();
    await expect(verifyIdToken(await idToken("alice", { pad: "x".repeat(4096) }), PROJECT)).rejects.toThrow("malformed");
    expect(calls.idJwks).toBe(0);
  });

  test("an email that isn't verified is reported, so the route can refuse it", async () => {
    await fakeGoogle();
    expect((await verifyIdToken(await idToken("alice", { email_verified: false }), PROJECT)).emailVerified).toBe(false);
    expect((await verifyIdToken(await idToken("alice", { email_verified: "true" }), PROJECT)).emailVerified).toBe(false); // only a real true counts
  });

  test("a token signed with another key is rejected", async () => {
    await fakeGoogle();
    await expect(verifyIdToken(await idToken("alice", {}, keys.rogue.privateKey), PROJECT)).rejects.toThrow("bad signature");
  });

  test("only the keys that tokens name are imported, once each", async () => {
    await fakeGoogle(); // lists 2 ID-token keys and 5 App Check keys, like Google
    const importKey = vi.spyOn(crypto.subtle, "importKey");
    const jwkImports = () => importKey.mock.calls.filter((c) => c[0] === "jwk").length;
    await verifyIdToken(await idToken("alice"), PROJECT);
    await verifyIdToken(await idToken("bob"), PROJECT);
    await verifyAppCheck(await appCheckToken(), PROJECT_NUMBER, [APP_ID]);
    await verifyAppCheck(await appCheckToken(), PROJECT_NUMBER, [APP_ID]);
    expect(jwkImports()).toBe(2);
    // Another listed kid (e.g. after Google rotates): imported when first named, no refetch.
    await expect(verifyIdToken(await idToken("carol", {}, keys.spare.privateKey, "id-0"), PROJECT)).resolves.toMatchObject({ uid: "carol" });
    expect(jwkImports()).toBe(3);
    importKey.mockRestore();
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
    await expect(verifyAppCheck(await appCheckToken(), PROJECT_NUMBER, [APP_ID])).resolves.toBeUndefined();
  });

  test("a list of allowed apps accepts any of them, and an empty list accepts any app", async () => {
    await fakeGoogle();
    const other = "1:123456789:android:devbuild";
    await expect(verifyAppCheck(await appCheckToken(), PROJECT_NUMBER, [other, APP_ID])).resolves.toBeUndefined();
    await expect(verifyAppCheck(await appCheckToken({ sub: other }), PROJECT_NUMBER, [other, APP_ID])).resolves.toBeUndefined();
    await expect(verifyAppCheck(await appCheckToken({ sub: other }), PROJECT_NUMBER, [APP_ID])).rejects.toThrow("sub");
    await expect(verifyAppCheck(await appCheckToken({ sub: other }), PROJECT_NUMBER, [])).resolves.toBeUndefined();
  });

  test.each([
    ["another project", { aud: ["projects/999"] }],
    ["another issuer", { iss: "https://firebaseappcheck.googleapis.com/999" }],
    ["another app", { sub: "1:123456789:android:other" }],
    ["expired", { exp: Math.floor(Date.now() / 1000) - 3600 }],
  ])("rejected: %s", async (_name, claims) => {
    await fakeGoogle();
    await expect(verifyAppCheck(await appCheckToken(claims), PROJECT_NUMBER, [APP_ID])).rejects.toThrow();
  });

  test("a forged token is rejected", async () => {
    await fakeGoogle();
    await expect(verifyAppCheck(await appCheckToken({}, keys.rogue.privateKey), PROJECT_NUMBER, [APP_ID])).rejects.toThrow();
  });

  test("the header must say typ JWT, and an ID token is not an App Check token", async () => {
    await fakeGoogle();
    const claims = {
      iss: `https://firebaseappcheck.googleapis.com/${PROJECT_NUMBER}`, aud: [`projects/${PROJECT_NUMBER}`],
      sub: APP_ID, exp: Math.floor(Date.now() / 1000) + 3000,
    };
    const jose = await signJwt(keys.appCheck.privateKey, { alg: "RS256", kid: "ac-1", typ: "JOSE" }, claims);
    await expect(verifyAppCheck(jose, PROJECT_NUMBER, [APP_ID])).rejects.toThrow("typ");
    await expect(verifyAppCheck(await idToken("alice"), PROJECT_NUMBER, [APP_ID])).rejects.toThrow("unknown kid");
  });
});

describe("emulator ID token", () => {
  test("decodes the unsigned token and still checks aud, iss and exp", () => {
    expect(decodeEmulatorIdToken(emulatorToken("alice"), "demo-liquidlyf").uid).toBe("alice");
    expect(() => decodeEmulatorIdToken(emulatorToken("alice"), "liquidlyf-app")).toThrow("aud");
    expect(() => decodeEmulatorIdToken(emulatorToken("a", { exp: 1 }), "demo-liquidlyf")).toThrow("expired");
  });
});
