// Helpers for the Firebase emulators (started by `npm run test:e2e`).
import type { Env } from "../../src/env";
import { encode } from "../../src/firestore";

export const PROJECT = "demo-liquidlyf";
export const FIRESTORE = process.env.FIRESTORE_EMULATOR_HOST ?? "127.0.0.1:8085";
export const AUTH = process.env.FIREBASE_AUTH_EMULATOR_HOST ?? "127.0.0.1:9099";
const DOCS = `http://${FIRESTORE}/v1/projects/${PROJECT}/databases/(default)/documents`;

export function emulatorEnv(): Env {
  const store = new Map<string, string>();
  return {
    FIREBASE_PROJECT_ID: PROJECT,
    FIREBASE_PROJECT_NUMBER: "0",
    ANDROID_APP_ID: "",
    ENFORCE_APP_CHECK: "false",
    ESCALATION_MINUTES: { CRITICAL: 15, URGENT: 30, PLANNED: 120 },
    FIRESTORE_EMULATOR_HOST: FIRESTORE,
    CACHE: {
      async get(k: string) { const v = store.get(k); return v ? JSON.parse(v) : null; },
      async put(k: string, v: string) { store.set(k, v); },
    },
  };
}

export async function clearFirestore() {
  await fetch(`http://${FIRESTORE}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`, { method: "DELETE" });
}

/** Write a doc as admin (bypasses rules). */
export async function putDoc(path: string, data: Record<string, unknown>) {
  const fields = Object.fromEntries(Object.entries(data).map(([k, v]) => [k, encode(v)]));
  const res = await fetch(`${DOCS}/${path}`, {
    method: "PATCH",
    headers: { authorization: "Bearer owner", "content-type": "application/json" },
    body: JSON.stringify({ fields }),
  });
  if (!res.ok) throw new Error(`putDoc ${res.status} ${await res.text()}`);
  return res.json() as Promise<{ updateTime: string }>;
}

export async function getDoc(path: string) {
  const res = await fetch(`${DOCS}/${path}`, { headers: { authorization: "Bearer owner" } });
  return res.ok ? res.json() as Promise<any> : null;
}

/** Create a verified Auth-emulator account and return its ID token. */
export async function signedInUser(uid: string, { verified = true } = {}): Promise<string> {
  const email = `${uid}@example.test`;
  const admin = `http://${AUTH}/identitytoolkit.googleapis.com/v1/projects/${PROJECT}/accounts`;
  await fetch(admin, {
    method: "POST",
    headers: { authorization: "Bearer owner", "content-type": "application/json" },
    body: JSON.stringify({ localId: uid, email, password: "password123", emailVerified: verified }),
  });
  const res = await fetch(`http://${AUTH}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email, password: "password123", returnSecureToken: true }),
  });
  if (!res.ok) throw new Error(`sign-in ${res.status} ${await res.text()}`);
  return (await res.json() as { idToken: string }).idToken;
}

export function newRequest(requesterUid: string, o: Record<string, unknown> = {}) {
  const neededBy = new Date(Date.now() + 2 * 86_400_000);
  return {
    requesterUid, requesterDisplayName: "Alice T.", bloodGroup: "B_POS", component: "WHOLE_BLOOD",
    acceptCompatible: false, acceptedGroups: ["B_POS"], unitsNeeded: 1, unitsFulfilled: 0,
    hospitalName: "Demo Hospital", division: "30", district: "3026", upazila: "302616",
    urgency: "URGENT", neededBy, expiresAt: new Date(neededBy.getTime() + 86_400_000),
    notes: "", flagged: false, status: "OPEN", responseCount: 0, reportCount: 0, askCount: 0,
    responders: {}, fanoutTier: 0, nextTierAt: new Date(), createdAt: new Date(), updatedAt: new Date(),
    ...o,
  };
}
