import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { getAccessToken, resetAuthCacheForTests } from "../src/google-auth";
import { fakeGoogle, makeEnv, resetCaches } from "./helpers";

beforeEach(resetCaches);
afterEach(() => vi.unstubAllGlobals());

describe("service-account OAuth token", () => {
  test("mints a token with a JWT signed by the service-account key", async () => {
    const calls = await fakeGoogle();
    const token = await getAccessToken(await makeEnv());
    expect(token).toBe("ya29.test-1");
    expect(calls.token).toBe(1);
  });

  test("caches the token in memory", async () => {
    const calls = await fakeGoogle();
    const env = await makeEnv();
    await getAccessToken(env);
    await getAccessToken(env);
    expect(calls.token).toBe(1);
  });

  test("a new isolate reuses the token from KV", async () => {
    const calls = await fakeGoogle();
    const env = await makeEnv();
    await getAccessToken(env);
    resetAuthCacheForTests(); // simulate a fresh isolate
    expect(await getAccessToken(env)).toBe("ya29.test-1");
    expect(calls.token).toBe(1);
  });

  test("fails clearly when the secret is missing", async () => {
    await fakeGoogle();
    await expect(getAccessToken(await makeEnv({ GCP_SA_JSON: undefined }))).rejects.toThrow("GCP_SA_JSON");
  });

  test("emulator mode needs no key and calls no Google endpoint", async () => {
    const calls = await fakeGoogle();
    const env = await makeEnv({ FIREBASE_PROJECT_ID: "demo-liquidlyf", FIRESTORE_EMULATOR_HOST: "127.0.0.1:8085", GCP_SA_JSON: undefined });
    expect(await getAccessToken(env)).toBe("owner");
    expect(calls.token).toBe(0);
  });

  test("emulator mode is refused for a real project", async () => {
    await fakeGoogle();
    const env = await makeEnv({ FIRESTORE_EMULATOR_HOST: "127.0.0.1:8085" });
    await expect(getAccessToken(env)).rejects.toThrow("non-demo");
  });
});
