// Verify Google-issued RS256 JWTs (Firebase ID tokens, App Check tokens) with WebCrypto.

export const ID_TOKEN_JWKS = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
export const APP_CHECK_JWKS = "https://firebaseappcheck.googleapis.com/v1/jwks";
const LEEWAY = 60; // seconds of clock skew

type Jwk = JsonWebKey & { kid: string };
/** Per JWKS URL: the published keys, and the ones already imported (only kids a token named). */
const jwksCache = new Map<string, { jwks: Map<string, Jwk>; keys: Map<string, CryptoKey>; exp: number; fetchedAt: number }>();

export function resetJwksCacheForTests() {
  jwksCache.clear();
}

async function getKey(url: string, kid: string): Promise<CryptoKey | undefined> {
  const now = Date.now() / 1000;
  let entry = jwksCache.get(url);
  // Refetch when expired, or for an unknown kid (key rotation) at most once a minute,
  // so junk tokens with random kids can't make us fetch the JWKS on every request.
  if (!entry || entry.exp < now || (!entry.jwks.has(kid) && now - entry.fetchedAt > 60)) {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`JWKS ${res.status}`);
    const maxAge = Number(/max-age=(\d+)/.exec(res.headers.get("cache-control") ?? "")?.[1] ?? 3600);
    const { keys } = await res.json() as { keys: Jwk[] };
    entry = { jwks: new Map(keys.map((k) => [k.kid, k])), keys: new Map(), exp: now + maxAge, fetchedAt: now };
    jwksCache.set(url, entry);
  }
  // Import only the key this token names: App Check publishes 5 RSA-3072 keys, and importing
  // all of them in a new isolate was a large part of a cold call's CPU (Free plan: 10 ms).
  let key = entry.keys.get(kid);
  const jwk = entry.jwks.get(kid);
  if (!key && jwk) {
    key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    entry.keys.set(kid, key);
  }
  return key;
}

function b64urlDecode(s: string): Uint8Array {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}
const decodePart = (s: string) => JSON.parse(new TextDecoder().decode(b64urlDecode(s)));

async function verifyRs256(token: string, jwksUrl: string): Promise<{ header: any; claims: any }> {
  const parts = token.split(".");
  if (parts.length !== 3 || token.length > 4096) throw new Error("malformed");
  const [h, p, s] = parts;
  const header = decodePart(h);
  if (header.alg !== "RS256" || typeof header.kid !== "string") throw new Error("bad header");
  const key = await getKey(jwksUrl, header.kid);
  if (!key) throw new Error("unknown kid");
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64urlDecode(s),
    new TextEncoder().encode(`${h}.${p}`));
  if (!ok) throw new Error("bad signature");
  return { header, claims: decodePart(p) };
}

export interface FirebaseUser {
  uid: string;
  emailVerified: boolean;
  claims: Record<string, any>;
}

function checkIdClaims(c: any, projectId: string): FirebaseUser {
  const now = Date.now() / 1000;
  if (c.aud !== projectId) throw new Error("aud");
  if (c.iss !== `https://securetoken.google.com/${projectId}`) throw new Error("iss");
  if (typeof c.sub !== "string" || c.sub.length === 0 || c.sub.length > 128) throw new Error("sub");
  if (!(c.exp > now - LEEWAY)) throw new Error("expired");
  if (!(c.iat < now + LEEWAY) || !(c.auth_time < now + LEEWAY)) throw new Error("iat/auth_time");
  // Not checked: revocation and disabled accounts (would need an Identity Toolkit call).
  // ID tokens live 1 hour, and a ban also sets the `banned` claim.
  return { uid: c.sub, emailVerified: c.email_verified === true, claims: c };
}

export async function verifyIdToken(token: string, projectId: string): Promise<FirebaseUser> {
  const { claims } = await verifyRs256(token, ID_TOKEN_JWKS);
  return checkIdClaims(claims, projectId);
}

/**
 * Auth-emulator tokens are unsigned (alg "none"). Only called in emulator mode,
 * which env.emulatorMode() allows for demo- projects only.
 */
export function decodeEmulatorIdToken(token: string, projectId: string): FirebaseUser {
  const parts = token.split(".");
  if (parts.length < 2) throw new Error("malformed");
  return checkIdClaims(decodePart(parts[1]), projectId);
}

/** appIds: the Firebase App IDs allowed to call (empty = any app of this project). */
export async function verifyAppCheck(token: string, projectNumber: string, appIds: string[] = []): Promise<void> {
  const { header, claims: c } = await verifyRs256(token, APP_CHECK_JWKS);
  if (header.typ !== "JWT") throw new Error("typ");
  const now = Date.now() / 1000;
  if (c.iss !== `https://firebaseappcheck.googleapis.com/${projectNumber}`) throw new Error("iss");
  const aud: string[] = Array.isArray(c.aud) ? c.aud : [c.aud];
  if (!aud.includes(`projects/${projectNumber}`)) throw new Error("aud");
  if (!(c.exp > now - LEEWAY)) throw new Error("expired");
  if (appIds.length > 0 && !appIds.includes(c.sub)) throw new Error("sub");
}
