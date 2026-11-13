// Bindings and vars (wrangler.jsonc). Only GCP_SA_JSON is secret (`wrangler secret put`).

export interface RateLimiter {
  limit(o: { key: string }): Promise<{ success: boolean }>;
}

export interface KV {
  get(key: string, type: "json"): Promise<any>;
  put(key: string, value: string, opts?: { expirationTtl?: number }): Promise<void>;
}

export interface Env {
  /** Secret: the whole `liquidlyf-worker` service-account JSON. Not needed in emulator mode. */
  GCP_SA_JSON?: string;
  FIREBASE_PROJECT_ID: string;
  /** App Check tokens name the project by number. */
  FIREBASE_PROJECT_NUMBER: string;
  /** e.g. 1:123456789:android:abc123; App Check `sub`. Empty = don't check the app ID. */
  ANDROID_APP_ID: string;
  /** "true" once App Check metrics look clean (Phase 9). Until then: monitor mode. */
  ENFORCE_APP_CHECK: string;
  /** Minutes until the next tier, per urgency. Keep in sync with Remote Config escalation_minutes_*. */
  ESCALATION_MINUTES: Record<string, number>;
  /** Requests per uid per rolling 24 h that alert donors (src/cap.ts). Default 5. */
  DAILY_ALERT_CAP?: number;
  /** Local development only: "127.0.0.1:8085". Allowed only for demo- projects. */
  FIRESTORE_EMULATOR_HOST?: string;
  CACHE: KV;
  RATE_LIMITER?: RateLimiter;
}

/**
 * Emulator mode: Firestore emulator, unsigned Auth-emulator ID tokens, no App Check,
 * FCM sends logged instead of sent. Refused for any project that isn't `demo-*`,
 * so a misconfigured production Worker can never accept unsigned tokens.
 */
export function emulatorMode(env: Env): boolean {
  if (!env.FIRESTORE_EMULATOR_HOST) return false;
  if (!env.FIREBASE_PROJECT_ID.startsWith("demo-")) {
    throw new Error("FIRESTORE_EMULATOR_HOST is set for a non-demo project");
  }
  return true;
}
