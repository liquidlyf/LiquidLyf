// Live end-to-end test (Phase 2 acceptance; spikes 1 and 2): a test requester account posts a
// real blood request through the security rules, exactly as the app will (write contract 1 and
// 6), then calls the Worker's dispatch. A phone subscribed to the matching topic should alert.
//
//   node scripts/live-request.mjs --email <test email> --division BD30 --district BD3026 \
//        --upazila BD30262000 --group B_POS            post + dispatch (prints timings)
//   node scripts/live-request.mjs --email <test email> --cancel <requestId>   close it again
//   add --emulator to run against the local Auth + Firestore emulators (no dispatch)
//
// The account's password is asked for here and never printed or stored. A new account gets a
// verification email first: click the link, then run the script again. The Android API key
// is read from app/src/prod/google-services.json and never printed.
import { readFileSync } from "node:fs";
import { createInterface } from "node:readline";
import { randomBytes } from "node:crypto";

// ---------------------------------------------------------------- arguments
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith("--")) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith("--") ? all[i + 1] : true]);
  return acc;
}, []));
const emulator = args.emulator === true;
if (!args.email) usage("--email is required");
if (!args.cancel && !(args.division && args.district && args.group)) usage("--division, --district and --group are required");
function usage(msg) { console.error(`${msg}\nSee the comment at the top of scripts/live-request.mjs.`); process.exit(2); }

const CODE = /^[A-Za-z0-9_-]{1,40}$/;
const GROUPS = ["A_POS", "A_NEG", "B_POS", "B_NEG", "AB_POS", "AB_NEG", "O_POS", "O_NEG"];
for (const k of ["division", "district", "upazila"]) if (args[k] && !CODE.test(args[k])) usage(`bad --${k}`);
if (args.group && !GROUPS.includes(args.group)) usage(`--group must be one of ${GROUPS.join(", ")}`);

// ---------------------------------------------------------------- endpoints
let project, apiKey, androidHeaders = {};
if (emulator) {
  project = "demo-liquidlyf"; apiKey = "demo";
} else {
  const gs = JSON.parse(readFileSync(new URL("../../../app/src/prod/google-services.json", import.meta.url), "utf8"));
  const client = gs.client.find((c) => c.client_info.android_client_info.package_name === "io.github.liquidlyf");
  project = gs.project_info.project_id;
  apiKey = client.api_key[0].current_key;
  // The key may be restricted to the Android app: identify as it (package + signing SHA-1).
  const cert = (client.oauth_client ?? []).find((o) => o.android_info)?.android_info.certificate_hash;
  androidHeaders = { "X-Android-Package": "io.github.liquidlyf", ...(cert ? { "X-Android-Cert": cert.toUpperCase() } : {}) };
}
const AUTH = emulator ? "http://127.0.0.1:9099/identitytoolkit.googleapis.com" : "https://identitytoolkit.googleapis.com";
const DOCS_HOST = emulator ? "http://127.0.0.1:8085" : "https://firestore.googleapis.com";
const ROOT = `projects/${project}/databases/(default)/documents`;
const DOCS = `${DOCS_HOST}/v1/${ROOT}`;
const WORKER = args.worker ?? "https://liquidlyf-worker.liquidlyf.workers.dev";

async function call(url, body, headers = {}) {
  const res = await fetch(url, {
    method: body === undefined ? "GET" : "POST",
    headers: { "content-type": "application/json", ...headers },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await res.text();
  let json; try { json = JSON.parse(text); } catch { json = { raw: text }; }
  return { status: res.status, ok: res.ok, json };
}
const authCall = (method, body) => call(`${AUTH}/v1/accounts:${method}?key=${apiKey}`, body, androidHeaders);
const err = (r) => `${r.status} ${r.json?.error?.message || r.json?.error?.status || JSON.stringify(r.json).slice(0, 200)}`;

// ---------------------------------------------------------------- sign in
function askHidden(prompt) {
  if (process.env.LIQUIDLYF_TEST_PASSWORD) return Promise.resolve(process.env.LIQUIDLYF_TEST_PASSWORD);
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    rl._writeToOutput = (s) => { if (s.includes(prompt)) process.stdout.write(s); };
    rl.question(prompt, (a) => { rl.close(); process.stdout.write("\n"); resolve(a); });
  });
}

const password = await askHidden(`Password for ${args.email}: `);
if (password.length < 8) usage("use a password of at least 8 characters");
let session = await authCall("signInWithPassword", { email: args.email, password, returnSecureToken: true });
if (!session.ok && /EMAIL_NOT_FOUND|INVALID_LOGIN_CREDENTIALS/.test(err(session))) {
  const created = await authCall("signUp", { email: args.email, password, returnSecureToken: true });
  if (!created.ok) fail(`sign-up failed: ${err(created)}`);
  session = created;
  console.log(`Created the test account ${args.email}.`);
} else if (!session.ok) fail(`sign-in failed: ${err(session)}`);
let { idToken, localId: uid } = session.json;

const lookup = await authCall("lookup", { idToken });
if (!lookup.json.users?.[0]?.emailVerified) {
  if (emulator) {
    // The Auth emulator can mark it verified directly.
    await call(`${AUTH}/v1/projects/${project}/accounts:update`, { localId: uid, emailVerified: true }, { authorization: "Bearer owner" });
    idToken = (await authCall("signInWithPassword", { email: args.email, password, returnSecureToken: true })).json.idToken;
  } else {
    const sent = await authCall("sendOobCode", { requestType: "VERIFY_EMAIL", idToken });
    if (!sent.ok) fail(`couldn't send the verification email: ${err(sent)}`);
    console.log(`A verification email was sent to ${args.email}. Click the link in it, then run this command again.`);
    process.exit(0);
  }
}
const bearer = { authorization: `Bearer ${idToken}` };
function fail(msg) { console.error(`FAILED  ${msg}`); process.exit(1); }

// ---------------------------------------------------------------- Firestore helpers
const enc = (v) => v === null ? { nullValue: null }
  : typeof v === "string" ? { stringValue: v }
  : typeof v === "boolean" ? { booleanValue: v }
  : typeof v === "number" ? { integerValue: String(v) }
  : v instanceof Date ? { timestampValue: v.toISOString() }
  : Array.isArray(v) ? { arrayValue: { values: v.map(enc) } }
  : { mapValue: { fields: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, enc(x)])) } };
const fields = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, enc(v)]));
const serverTime = (...f) => f.map((fieldPath) => ({ fieldPath, setToServerValue: "REQUEST_TIME" }));
const commit = (writes) => call(`${DOCS}:commit`, { writes }, bearer);
const name = (path) => `${ROOT}/${path}`;

// ---------------------------------------------------------------- cancel
if (args.cancel) {
  const rid = String(args.cancel);
  const res = await commit([
    { update: { name: name(`requests/${rid}`), fields: fields({ status: "CANCELLED", closedReason: "Test finished" }) },
      updateMask: { fieldPaths: ["status", "closedReason"] }, updateTransforms: serverTime("updatedAt"),
      currentDocument: { exists: true } },
    { update: { name: name(`userStats/${uid}`), fields: fields({ lastRequestId: rid }) },
      updateMask: { fieldPaths: ["lastRequestId"] },
      updateTransforms: [{ fieldPath: "openRequests", increment: { integerValue: "-1" } }] },
  ]);
  if (!res.ok) fail(`cancel refused: ${err(res)}`);
  console.log(`PASS  request ${rid} cancelled (userStats -1).`);
  process.exit(0);
}

// ---------------------------------------------------------------- profile (contract 1)
const area = { division: args.division, district: args.district, ...(args.upazila ? { upazila: args.upazila } : {}) };
const phone = "+8801700000001"; // fake, never shown to anyone
const profile = await call(`${DOCS}/users/${uid}`, undefined, bearer);
if (profile.status === 404) {
  const res = await commit([
    { update: { name: name(`users/${uid}`), fields: fields({
        fullName: "LiquidLyf Test Requester", email: args.email, phone, gender: "OTHER", birthYear: 1990,
        weightOk: true, upazila: args.upazila ?? args.district, division: args.division, district: args.district,
        available: false, showInDonorSearch: false, locale: "en", termsVersion: "2026-11",
        donationCount: 0, verifiedDonations: 0 }) }, // no bloodGroup: "I don't know" (P1)
      updateTransforms: serverTime("createdAt", "updatedAt", "termsAcceptedAt"), currentDocument: { exists: false } },
    { update: { name: name(`userStats/${uid}`), fields: fields({ openRequests: 0, totalRequests: 0 }) },
      currentDocument: { exists: false } },
  ]);
  if (!res.ok) fail(`profile setup refused by the rules: ${err(res)}`);
  console.log("PASS  profile set up (through the rules)");
} else if (!profile.ok) fail(`couldn't read the profile: ${err(profile)}`);

// ---------------------------------------------------------------- request (contract 6)
const rid = `test-${randomBytes(6).toString("hex")}`;
const neededBy = new Date(Math.floor((Date.now() + 86_400_000) / 1000) * 1000);
const request = {
  requesterUid: uid, requesterDisplayName: "Test R.", bloodGroup: args.group, component: "WHOLE_BLOOD",
  acceptCompatible: false, acceptedGroups: [args.group], unitsNeeded: 1, unitsFulfilled: 0,
  hospitalName: "LiquidLyf test (please ignore)", ...area, urgency: "URGENT",
  neededBy, expiresAt: new Date(neededBy.getTime() + 86_400_000),
  notes: "Test request, please ignore.", flagged: false, status: "OPEN",
  responseCount: 0, reportCount: 0, askCount: 0, responders: {}, fanoutTier: 0,
};
const t0 = Date.now();
const created = await commit([
  { update: { name: name(`requests/${rid}`), fields: fields(request) },
    updateTransforms: serverTime("createdAt", "updatedAt", "nextTierAt"), currentDocument: { exists: false } },
  { update: { name: name(`userStats/${uid}`), fields: fields({ lastRequestId: rid }) },
    updateMask: { fieldPaths: ["lastRequestId"] },
    updateTransforms: [
      { fieldPath: "openRequests", increment: { integerValue: "1" } },
      { fieldPath: "totalRequests", increment: { integerValue: "1" } }] },
  { update: { name: name(`requests/${rid}/private/contact`),
      fields: fields({ requesterUid: uid, contactName: "Test Requester", contactPhone: phone }) } },
]);
if (!created.ok) fail(`request refused by the rules: ${err(created)}`);
console.log(`PASS  request ${rid} created through the rules (${Date.now() - t0} ms)`);

if (emulator || args["no-dispatch"]) process.exit(0);
const t1 = Date.now();
const res = await call(`${WORKER}/v1/requests/${rid}/dispatch`, {}, bearer);
const t2 = Date.now();
console.log(`${res.status === 202 ? "PASS" : "FAIL"}  dispatch -> ${res.status} ${JSON.stringify(res.json)} in ${t2 - t1} ms`);
console.log(`      sent at ${new Date(t2).toISOString()} (compare with the phone's receive time)`);
console.log(`      to close it later: node scripts/live-request.mjs --email ${args.email} --cancel ${rid}`);
process.exit(res.status === 202 ? 0 : 1);
