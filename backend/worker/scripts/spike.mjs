// Day-1 spike (Phase 2.0, doc 10 §10): can the liquidlyf-worker service account reach
// FCM HTTP v1 and Firestore from outside Google, on the Spark plan?
//
//   node scripts/spike.mjs <path-to-service-account-key.json> [--send]
//
// Prints results only: never the key, never the access token. Changes nothing in
// Firestore. FCM uses validate_only unless you pass --send, which sends one real
// test message to the topic "spike_test".
import { readFileSync } from "node:fs";

const [keyPath, flag] = process.argv.slice(2);
if (!keyPath) {
  console.error("Usage: node scripts/spike.mjs <path-to-key.json> [--send]");
  process.exit(2);
}
const send = flag === "--send";
const sa = JSON.parse(readFileSync(keyPath, "utf8"));
const project = sa.project_id;
const results = [];
const ok = (step, detail) => { results.push(["PASS", step, detail]); console.log(`PASS  ${step}: ${detail}`); };
const bad = (step, detail) => { results.push(["FAIL", step, detail]); console.log(`FAIL  ${step}: ${detail}`); };
const ms = (t) => `${Math.round(performance.now() - t)} ms`;

// ---- 0. the key file
if (sa.type !== "service_account" || !sa.private_key || !sa.client_email) {
  bad("key file", "not a service-account JSON key");
  process.exit(1);
}
ok("key file", `${sa.client_email} (project ${project})`);
if (!sa.client_email.startsWith("liquidlyf-worker@")) bad("account name", "expected liquidlyf-worker@...; check you downloaded the right key");
if (project !== "liquidlyf-app") bad("project", `expected liquidlyf-app, got ${project}`);

// ---- 1. OAuth token (RS256 JWT signed with WebCrypto, as in the Worker)
const b64url = (b) => Buffer.from(b).toString("base64url");
let token;
{
  const t = performance.now();
  const pem = sa.private_key.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey("pkcs8", Buffer.from(pem, "base64"),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const now = Math.floor(Date.now() / 1000);
  const input = `${b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${b64url(JSON.stringify({
    iss: sa.client_email, aud: "https://oauth2.googleapis.com/token", iat: now - 30, exp: now + 3300,
    scope: "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging",
  }))}`;
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(input));
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${input}.${b64url(sig)}` }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) { bad("OAuth token", `${res.status} ${body.error ?? ""} ${body.error_description ?? ""}`); process.exit(1); }
  token = body.access_token;
  ok("OAuth token", `minted in ${ms(t)}, valid ${body.expires_in} s`);
}
const auth = { authorization: `Bearer ${token}`, "content-type": "application/json" };
const errText = async (res) => { const b = await res.json().catch(() => ({})); return `${res.status} ${b.error?.status ?? ""} ${b.error?.message ?? ""}`.trim(); };

// ---- 2. FCM HTTP v1 to a topic condition
{
  const t = performance.now();
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${project}/messages:send`, {
    method: "POST", headers: auth,
    body: JSON.stringify({
      validate_only: !send,
      message: {
        condition: "'spike_test' in topics",
        data: { type: "SPIKE", sentAt: new Date().toISOString() },
        notification: { title: "LiquidLyf spike", body: "FCM from the Worker's service account works." },
        android: { priority: "HIGH", ttl: "600s" },
      },
    }),
  });
  if (res.ok) ok(send ? "FCM send" : "FCM (validate only)", `accepted in ${ms(t)}${send ? " (sent to topic spike_test)" : ""}`);
  else {
    const text = await errText(res);
    const hint = /SERVICE_DISABLED|has not been used|is disabled/i.test(text)
      ? "enable the 'Firebase Cloud Messaging API' in the Cloud console (APIs & Services)"
      : "the account needs the role 'Firebase Cloud Messaging API Admin' (roles/firebasecloudmessaging.admin); " +
        "the similar 'Firebase Cloud Messaging Admin' (roles/firebasenotifications.admin) can't send. New roles can take a few minutes";
    bad("FCM", `${text}  -> ${hint}`);
  }
}

// ---- 3. Firestore: database metadata (location) and a read
{
  const t = performance.now();
  const res = await fetch(`https://firestore.googleapis.com/v1/projects/${project}/databases/(default)`, { headers: auth });
  if (res.ok) {
    const db = await res.json();
    const where = `${db.locationId}, ${db.type}`;
    if (db.locationId === "asia-south1") ok("Firestore database", `${where} (${ms(t)})`);
    else bad("Firestore database", `${where}: the plan needs asia-south1 (the region is permanent)`);
  } else if (res.status === 404) {
    bad("Firestore database", "none yet: create it in the Firebase console (asia-south1, production mode)");
  } else {
    bad("Firestore database", `${await errText(res)}  -> check the 'Cloud Datastore User' role`);
  }
}
{
  // A normal ID that is never written (IDs matching __.*__ are reserved and rejected with 400).
  const t = performance.now();
  const res = await fetch(`https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/documents/requests/spike-missing-doc`, { headers: auth });
  if (res.ok) ok("Firestore read", `allowed in ${ms(t)}`);
  else {
    const b = await res.json().catch(() => ({}));
    const msg = b.error?.message ?? "";
    // A missing doc is 404 'Document "projects/.../documents/requests/spike-missing-doc" not found.'
    // (a missing database is also 404, but says the database does not exist).
    if (res.status === 404 && /^Document\b.*not found/i.test(msg)) ok("Firestore read", `allowed (doc not found, as expected) in ${ms(t)}`);
    else bad("Firestore read", `${res.status} ${b.error?.status ?? ""} ${msg}`.trim() + (res.status === 403 ? "  -> check the 'Cloud Datastore User' role" : ""));
  }
}

const failed = results.filter((r) => r[0] === "FAIL").length;
console.log(failed ? `\n${failed} check(s) failed.` : "\nAll checks passed. Safe to paste this output into the chat.");
process.exit(failed ? 1 : 0);
