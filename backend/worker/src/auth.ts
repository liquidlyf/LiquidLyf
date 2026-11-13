// Caller checks, cheapest first (doc 10 §6.5): App Check -> ID token -> rate limit.
// Junk traffic is rejected before it can cost a Firestore read.
import { emulatorMode, type Env } from "./env";
import { HttpError } from "./http";
import { decodeEmulatorIdToken, verifyAppCheck, verifyIdToken, type FirebaseUser } from "./verify";

export const RATE_LIMIT = { limit: 10, periodMs: 60_000 }; // matches wrangler.jsonc ratelimits

// Fallback when the Rate Limiting binding is unavailable: per isolate, so approximate.
const hits = new Map<string, number[]>();
export function resetRateLimitForTests() {
  hits.clear();
}
function memoryLimit(key: string): boolean {
  const now = Date.now();
  const recent = (hits.get(key) ?? []).filter((t) => now - t < RATE_LIMIT.periodMs);
  if (recent.length >= RATE_LIMIT.limit) return false;
  recent.push(now);
  hits.set(key, recent);
  if (hits.size > 10_000) hits.clear(); // bound memory
  return true;
}

async function checkAppCheck(req: Request, env: Env): Promise<void> {
  const enforce = env.ENFORCE_APP_CHECK === "true";
  const token = req.headers.get("x-firebase-appcheck");
  if (!token) {
    if (enforce) throw new HttpError(401, "app check required");
    console.log(JSON.stringify({ appCheck: "missing", enforced: false }));
    return;
  }
  try {
    // ANDROID_APP_ID: one Firebase App ID, or a comma-separated list (prod, later dev).
    const appIds = (env.ANDROID_APP_ID ?? "").split(",").map((s) => s.trim()).filter(Boolean);
    await verifyAppCheck(token, env.FIREBASE_PROJECT_NUMBER, appIds);
  } catch (e) {
    if (enforce) throw new HttpError(401, "bad app check token");
    // Monitor mode (until Phase 9): record it, let the call through.
    console.log(JSON.stringify({ appCheck: "invalid", reason: (e as Error).message, enforced: false }));
  }
}

export async function authenticate(req: Request, env: Env): Promise<FirebaseUser> {
  const emulator = emulatorMode(env);
  if (!emulator) await checkAppCheck(req, env);

  const m = /^Bearer ([A-Za-z0-9._-]+)$/.exec(req.headers.get("authorization") ?? "");
  if (!m) throw new HttpError(401, "missing ID token");
  let user: FirebaseUser;
  try {
    user = emulator
      ? decodeEmulatorIdToken(m[1], env.FIREBASE_PROJECT_ID)
      : await verifyIdToken(m[1], env.FIREBASE_PROJECT_ID);
  } catch {
    throw new HttpError(401, "invalid ID token");
  }
  if (!user.emailVerified) throw new HttpError(403, "verify your email first");
  if (user.claims.banned === true) throw new HttpError(403, "account suspended");

  const ok = env.RATE_LIMITER
    ? (await env.RATE_LIMITER.limit({ key: user.uid })).success
    : memoryLimit(user.uid);
  if (!ok) throw new HttpError(429, "too many requests");
  return user;
}
