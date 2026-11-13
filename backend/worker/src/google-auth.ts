// Mint a Google OAuth2 access token from the service-account key with WebCrypto.
// One token covers Firestore and FCM. Cached in memory and in KV (~55 min).
import { emulatorMode, type Env } from "./env";
import { JWT_GRANT, jwtClaims, TOKEN_URL } from "./oauth";

export interface ServiceAccount {
  client_email: string;
  private_key: string;
  project_id: string;
}

export { TOKEN_URL };
const KV_KEY = "gcp_token";
const enc = new TextEncoder();

export function b64url(data: ArrayBuffer | Uint8Array | string): string {
  const bytes = typeof data === "string" ? enc.encode(data)
    : data instanceof Uint8Array ? data : new Uint8Array(data);
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Google's JSON key holds a PKCS#8 PEM ("BEGIN PRIVATE KEY"). */
export async function importPkcs8(pem: string): Promise<CryptoKey> {
  const body = pem.replace(/\\n/g, "\n")
    .replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey(
    "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
}

export async function signJwt(sa: ServiceAccount, key: CryptoKey, nowSec: number): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" };
  const claims = jwtClaims(sa.client_email, nowSec);
  const input = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(input));
  return `${input}.${b64url(sig)}`;
}

// Module scope often survives between requests in the same isolate.
let cachedKey: CryptoKey | undefined;
let cachedToken: { value: string; exp: number } | undefined;

export function resetAuthCacheForTests() {
  cachedKey = undefined;
  cachedToken = undefined;
}

export async function getAccessToken(env: Env): Promise<string> {
  if (emulatorMode(env)) return "owner"; // the emulator's admin token: bypasses rules
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.exp - 300 > now) return cachedToken.value;

  // Second-level cache in KV (free: 1,000 writes/day; this writes ~24/day).
  const kv = await env.CACHE.get(KV_KEY, "json") as { value: string; exp: number } | null;
  if (kv && kv.exp - 300 > now) return (cachedToken = kv).value;

  if (!env.GCP_SA_JSON) throw new Error("GCP_SA_JSON secret is not set");
  const sa = JSON.parse(env.GCP_SA_JSON) as ServiceAccount;
  cachedKey ??= await importPkcs8(sa.private_key);
  const assertion = await signJwt(sa, cachedKey, now);
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: JWT_GRANT, assertion }),
  });
  if (!res.ok) throw new Error(`token endpoint ${res.status}`); // body may echo details; don't log it
  const { access_token, expires_in } = await res.json() as { access_token: string; expires_in: number };
  cachedToken = { value: access_token, exp: now + expires_in };
  await env.CACHE.put(KV_KEY, JSON.stringify(cachedToken), { expirationTtl: Math.max(60, expires_in - 60) });
  return access_token;
}
