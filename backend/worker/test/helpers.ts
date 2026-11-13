// Test doubles: real RSA keys and signed JWTs, a fake KV, and a fetch router that plays
// Google's token, JWKS, Firestore and FCM endpoints while counting reads and writes.
import { vi } from "vitest";
import type { Env, KV } from "../src/env";
import { b64url, resetAuthCacheForTests } from "../src/google-auth";
import { resetJwksCacheForTests } from "../src/verify";
import { resetRateLimitForTests } from "../src/auth";

export const PROJECT = "liquidlyf-test";
export const PROJECT_NUMBER = "123456789";
export const APP_ID = "1:123456789:android:abc";

const RSA = { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" };
const genKey = () => crypto.subtle.generateKey(RSA, true, ["sign", "verify"]) as Promise<CryptoKeyPair>;

export const keys = {
  id: await genKey(),       // "Google" ID-token signing key
  appCheck: await genKey(), // "Firebase App Check" signing key
  sa: await genKey(),       // the service account's key
  rogue: await genKey(),    // an attacker's key
};

export async function jwk(pair: CryptoKeyPair, kid: string) {
  return { ...(await crypto.subtle.exportKey("jwk", pair.publicKey)), kid, alg: "RS256", use: "sig" };
}

export async function signJwt(key: CryptoKey, header: object, claims: object): Promise<string> {
  const input = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(input));
  return `${input}.${b64url(sig)}`;
}

const nowSec = () => Math.floor(Date.now() / 1000);

export function idToken(uid: string, claims: Record<string, unknown> = {}, key = keys.id.privateKey, kid = "id-1") {
  return signJwt(key, { alg: "RS256", kid, typ: "JWT" }, {
    iss: `https://securetoken.google.com/${PROJECT}`, aud: PROJECT, sub: uid, user_id: uid,
    iat: nowSec() - 10, auth_time: nowSec() - 100, exp: nowSec() + 3000, email_verified: true, ...claims,
  });
}

export function appCheckToken(claims: Record<string, unknown> = {}, key = keys.appCheck.privateKey) {
  return signJwt(key, { alg: "RS256", kid: "ac-1", typ: "JWT" }, {
    iss: `https://firebaseappcheck.googleapis.com/${PROJECT_NUMBER}`, aud: [`projects/${PROJECT_NUMBER}`, `projects/${PROJECT}`],
    sub: APP_ID, iat: nowSec() - 10, exp: nowSec() + 3000, ...claims,
  });
}

export function emulatorToken(uid: string, claims: Record<string, unknown> = {}) {
  const payload = {
    iss: "https://securetoken.google.com/demo-liquidlyf", aud: "demo-liquidlyf", sub: uid,
    iat: nowSec() - 10, auth_time: nowSec() - 100, exp: nowSec() + 3000, email_verified: true, ...claims,
  };
  return `${b64url(JSON.stringify({ alg: "none", typ: "JWT" }))}.${b64url(JSON.stringify(payload))}.`;
}

export async function serviceAccountJson() {
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", keys.sa.privateKey));
  const b64 = btoa(String.fromCharCode(...pkcs8)).replace(/(.{64})/g, "$1\n");
  return JSON.stringify({
    type: "service_account", project_id: PROJECT, client_email: `liquidlyf-worker@${PROJECT}.iam.gserviceaccount.com`,
    private_key: `-----BEGIN PRIVATE KEY-----\n${b64}\n-----END PRIVATE KEY-----\n`,
  });
}

export function fakeKv(): KV & { store: Map<string, string> } {
  const store = new Map<string, string>();
  return {
    store,
    async get(key: string) { const v = store.get(key); return v ? JSON.parse(v) : null; },
    async put(key: string, value: string) { store.set(key, value); },
  };
}

export async function makeEnv(o: Partial<Env> = {}): Promise<Env> {
  return {
    GCP_SA_JSON: await serviceAccountJson(),
    FIREBASE_PROJECT_ID: PROJECT,
    FIREBASE_PROJECT_NUMBER: PROJECT_NUMBER,
    ANDROID_APP_ID: APP_ID,
    ENFORCE_APP_CHECK: "false",
    ESCALATION_MINUTES: { CRITICAL: 15, URGENT: 30, PLANNED: 120 },
    CACHE: fakeKv(),
    ...o,
  };
}

// ------------------------------------------------------------------ fake Google

export interface Calls {
  token: number; idJwks: number; appCheckJwks: number;
  reads: string[]; writes: any[][]; fcm: any[]; other: string[];
}

export interface GoogleOpts {
  docs?: Record<string, any>;            // path -> Firestore REST doc (fields + updateTime)
  fcmStatus?: number[];                  // successive FCM response codes (default 200)
  commitError?: { status: number; grpc: string } | null;
}

/** Install a fetch mock; returns the live call log. */
export async function fakeGoogle(opts: GoogleOpts = {}): Promise<Calls> {
  const calls: Calls = { token: 0, idJwks: 0, appCheckJwks: 0, reads: [], writes: [], fcm: [], other: [] };
  const idKeys = { keys: [await jwk(keys.id, "id-1")] };
  const acKeys = { keys: [await jwk(keys.appCheck, "ac-1")] };
  const fcmStatus = [...(opts.fcmStatus ?? [])];
  const base = `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents/`;

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const json = (b: unknown, status = 200, headers: Record<string, string> = {}) =>
      new Response(JSON.stringify(b), { status, headers: { "content-type": "application/json", ...headers } });

    if (url === "https://oauth2.googleapis.com/token") {
      calls.token++;
      const assertion = new URLSearchParams(String(init?.body)).get("assertion")!;
      const [h, p, s] = assertion.split(".");
      const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", keys.sa.publicKey,
        Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "==".slice(0, (4 - s.length % 4) % 4)), (c) => c.charCodeAt(0)),
        new TextEncoder().encode(`${h}.${p}`));
      if (!ok) return json({ error: "invalid_grant" }, 400);
      return json({ access_token: `ya29.test-${calls.token}`, expires_in: 3599, token_type: "Bearer" });
    }
    if (url.includes("securetoken@system.gserviceaccount.com")) {
      calls.idJwks++;
      return json(idKeys, 200, { "cache-control": "public, max-age=19000" });
    }
    if (url === "https://firebaseappcheck.googleapis.com/v1/jwks") {
      calls.appCheckJwks++;
      return json(acKeys, 200, { "cache-control": "public, max-age=21600" });
    }
    if (url.startsWith(`https://fcm.googleapis.com/v1/projects/${PROJECT}/messages:send`)) {
      const body = JSON.parse(String(init?.body));
      calls.fcm.push(body.message);
      const status = fcmStatus.shift() ?? 200;
      return status === 200 ? json({ name: `projects/${PROJECT}/messages/1` }) : json({ error: { code: status } }, status);
    }
    if (url === `${base.slice(0, -1)}:commit`) {
      const { writes } = JSON.parse(String(init?.body));
      calls.writes.push(writes);
      if (opts.commitError) return json({ error: { status: opts.commitError.grpc } }, opts.commitError.status);
      return json({ writeResults: writes.map(() => ({ updateTime: new Date().toISOString() })), commitTime: new Date().toISOString() });
    }
    if (url.startsWith(base) && (!init?.method || init.method === "GET")) {
      const path = decodeURIComponent(url.slice(base.length).split("?")[0]);
      calls.reads.push(path);
      const d = opts.docs?.[path];
      return d ? json({ name: `projects/${PROJECT}/databases/(default)/documents/${path}`, ...d })
        : json({ error: { code: 404, status: "NOT_FOUND" } }, 404);
    }
    calls.other.push(url);
    return json({ error: "unexpected" }, 500);
  }));
  return calls;
}

export function resetCaches() {
  resetAuthCacheForTests();
  resetJwksCacheForTests();
  resetRateLimitForTests();
}

/** A requests/{rid} doc in Firestore REST form. */
export function requestRest(o: Record<string, unknown> = {}) {
  const v: Record<string, unknown> = {
    requesterUid: "alice", status: "OPEN", fanoutTier: 0, createdAt: new Date(Date.now() - 60_000),
    bloodGroup: "B_POS", component: "WHOLE_BLOOD", acceptCompatible: false, acceptedGroups: ["B_POS"],
    division: "BD30", district: "BD3026", upazila: "BD30262000", urgency: "URGENT", ...o,
  };
  const enc = (x: unknown): any => x instanceof Date ? { timestampValue: x.toISOString() }
    : Array.isArray(x) ? { arrayValue: { values: x.map(enc) } }
    : typeof x === "number" ? { integerValue: String(x) }
    : typeof x === "boolean" ? { booleanValue: x }
    : x === null ? { nullValue: null } : { stringValue: String(x) };
  return {
    fields: Object.fromEntries(Object.entries(v).filter(([, x]) => x !== undefined).map(([k, x]) => [k, enc(x)])),
    updateTime: "2026-11-13T10:00:00.123456Z",
  };
}

export function post(path: string, headers: Record<string, string> = {}) {
  return new Request(`https://worker.test${path}`, { method: "POST", headers });
}
