// Live end-to-end test (Phase 2 acceptance; spikes 1 and 2): a test requester account posts a
// real blood request through the security rules, exactly as the app will (write contract 1 and
// 6), then calls the Worker's dispatch. A phone subscribed to the matching topic should alert.
//
//   node scripts/live-request.mjs --email <test email> --division BD30 --district BD3026 \
//        --upazila BD30262000 --group B_POS            post + dispatch (prints timings)
//   node scripts/live-request.mjs --email <test email> --confirm <requestId> [--donor <uid>]
//        confirm the offer on it, as the app does (contract 11 + the donor's inbox item), then
//        notify -> OFFER_CONFIRMED to the donor. Without --donor: the one OFFERED offer
//   node scripts/live-request.mjs --email <test email> --donated <requestId> [--donor <uid>]
//        mark the CONFIRMED donor as having donated (contract 11: DONATED, unitsFulfilled +1,
//        the donor's inbox item), then notify -> DONATION_RECORDED. Without --donor: the one CONFIRMED.
//        As in the app, the donation that reaches unitsNeeded also closes the request (FULFILLED,
//        userStats -1), then notify-responders -> REQUEST_FULFILLED to donors still waiting
//   node scripts/live-request.mjs --email <test email> --ask <requestId> --donor <uid>
//        ask a listed donor to donate (contract 14: the inbox item, askCount +1, and the donor
//        named in this account's own userStats), then the Worker pushes ASKED_TO_DONATE
//   node scripts/live-request.mjs --email <test email> --cancel <requestId>   close it again,
//        then notify-responders -> REQUEST_CANCELLED to donors still OFFERED or CONFIRMED
//   node scripts/live-request.mjs --email <test email> --delete-account       remove the test
//        account the way the app's account deletion does (write contract 16); asks first
//   add --emulator to run against the local Auth + Firestore emulators. The Worker is then
//        called only if you also pass --worker http://127.0.0.1:8787 (npm run dev)
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
const deleting = args["delete-account"] === true;
const confirming = args.confirm !== undefined;
const donating = args.donated !== undefined;
const asking = args.ask !== undefined;
const posting = !args.cancel && !deleting && !confirming && !donating && !asking;
if (!args.email) usage("--email is required");
if (posting && !(args.division && args.district && args.group)) usage("--division, --district and --group are required");
if (asking && args.donor === undefined) usage("--ask needs --donor <uid>");
function usage(msg) { console.error(`${msg}\nSee the comment at the top of scripts/live-request.mjs.`); process.exit(2); }

const ID = /^[A-Za-z0-9_-]{1,128}$/;
for (const k of ["confirm", "donated", "ask", "cancel", "donor"]) if (args[k] !== undefined && !ID.test(String(args[k]))) usage(`--${k} needs an ID`);
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

async function call(url, body, headers = {}, method) {
  const res = await fetch(url, {
    method: method ?? (body === undefined ? "GET" : "POST"),
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
function ask(prompt) {
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    let answer = "";
    rl.on("close", () => resolve(answer)); // no input at all counts as "no"
    rl.question(prompt, (a) => { answer = a.trim(); rl.close(); });
  });
}

const password = await askHidden(`Password for ${args.email}: `);
if (password.length < 8) usage("use a password of at least 8 characters");
let session = await authCall("signInWithPassword", { email: args.email, password, returnSecureToken: true });
if (!session.ok && posting && /EMAIL_NOT_FOUND|INVALID_LOGIN_CREDENTIALS/.test(err(session))) {
  const created = await authCall("signUp", { email: args.email, password, returnSecureToken: true });
  if (!created.ok) fail(`sign-up failed: ${err(created)}`);
  session = created;
  console.log(`Created the test account ${args.email}.`);
} else if (!session.ok) fail(`sign-in failed: ${err(session)}`); // --cancel / --delete-account never create one
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

/** POST to the Worker as this account. On the emulators only with an explicit --worker. */
async function workerPost(path) {
  if (emulator && !args.worker) {
    console.log(`      (Worker ${path} skipped: add --worker http://127.0.0.1:8787 to call the local Worker)`);
    return null;
  }
  const t = Date.now();
  const r = await call(`${WORKER}${path}`, {}, bearer);
  console.log(`${r.status === 202 ? "PASS" : "FAIL"}  ${path.split("/").pop()} -> ${r.status} ${JSON.stringify(r.json)} in ${Date.now() - t} ms`);
  console.log(`      sent at ${new Date().toISOString()} (compare with the phone's receive time)`);
  return r;
}

// ---------------------------------------------------------------- Firestore helpers
const enc = (v) => v === null ? { nullValue: null }
  : typeof v === "string" ? { stringValue: v }
  : typeof v === "boolean" ? { booleanValue: v }
  : typeof v === "number" ? { integerValue: String(v) }
  : v instanceof Date ? { timestampValue: v.toISOString() }
  : Array.isArray(v) ? { arrayValue: { values: v.map(enc) } }
  : { mapValue: { fields: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, enc(x)])) } };
const dec = (v) => "stringValue" in v ? v.stringValue
  : "booleanValue" in v ? v.booleanValue
  : "integerValue" in v ? Number(v.integerValue)
  : "doubleValue" in v ? v.doubleValue
  : "timestampValue" in v ? new Date(v.timestampValue)
  : "nullValue" in v ? null
  : "arrayValue" in v ? (v.arrayValue.values ?? []).map(dec)
  : "mapValue" in v ? Object.fromEntries(Object.entries(v.mapValue.fields ?? {}).map(([k, x]) => [k, dec(x)]))
  : undefined;
const fields = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, enc(v)]));
const fieldsOf = (d) => Object.fromEntries(Object.entries(d.fields ?? {}).map(([k, v]) => [k, dec(v)]));
const serverTime = (...f) => f.map((fieldPath) => ({ fieldPath, setToServerValue: "REQUEST_TIME" }));
const commit = (writes) => call(`${DOCS}:commit`, { writes }, bearer);
const name = (path) => `${ROOT}/${path}`;
const pathOf = (docName) => docName.slice(ROOT.length + 1);

/** This account's OPEN request, or exit with the reason. */
async function myOpenRequest(rid) {
  const reqDoc = await call(`${DOCS}/requests/${rid}`, undefined, bearer);
  if (!reqDoc.ok) fail(`couldn't read request ${rid}: ${err(reqDoc)}`);
  const r = fieldsOf(reqDoc.json);
  if (r.requesterUid !== uid) fail(`request ${rid} isn't this account's`);
  if (r.status !== "OPEN") fail(`request ${rid} is ${r.status}, not OPEN`);
  return r;
}

// ------------------------------------------- confirm an offer / record a donation (contract 11)
// One batch, as the app: the response, its mirror on the request (DONATED also adds
// unitsFulfilled +1), and the donor's inbox item. Then the Worker tells the donor.
const DECISIONS = {
  confirm: { from: "OFFERED", to: "CONFIRMED", inbox: "OFFER_CONFIRMED", done: "confirmed" },
  donated: { from: "CONFIRMED", to: "DONATED", inbox: "DONATION_RECORDED", done: "marked DONATED" },
};
if (confirming || donating) {
  const d = DECISIONS[confirming ? "confirm" : "donated"];
  const rid = String(confirming ? args.confirm : args.donated);
  const r = await myOpenRequest(rid);
  const waiting = Object.entries(r.responders ?? {}).filter(([, v]) => v?.status === d.from).map(([k]) => k);
  const donor = args.donor ? String(args.donor) : waiting.length === 1 ? waiting[0] : null;
  if (!donor) fail(waiting.length ? `${waiting.length} offers are ${d.from} (${waiting.join(", ")}): pick one with --donor <uid>` : `no offer is ${d.from} yet`);
  const entry = r.responders?.[donor];
  if (entry?.status !== d.from) fail(`donor ${donor}'s offer is ${entry?.status ?? "missing"}, not ${d.from}`);
  const resp = await call(`${DOCS}/requests/${rid}/responses/${donor}`, undefined, bearer);
  if (!resp.ok) fail(`couldn't read the offer: ${err(resp)}`);
  // As the app (OfferRepository.decide): the donation that reaches unitsNeeded also closes the
  // request as FULFILLED in the same batch, with userStats -1 naming it (contract 7).
  const fills = d.to === "DONATED" && (r.unitsFulfilled ?? 0) + 1 >= (r.unitsNeeded ?? 1);

  const res = await commit([
    { update: { name: name(`requests/${rid}/responses/${donor}`), fields: fields({ status: d.to }) },
      updateMask: { fieldPaths: ["status"] }, updateTransforms: serverTime("updatedAt"),
      currentDocument: { updateTime: resp.json.updateTime } },
    { update: { name: name(`requests/${rid}`), fields: fields({
        responders: { [donor]: { name: entry.name, group: entry.group, status: d.to } }, lastResponderUid: donor,
        ...(fills ? { status: "FULFILLED" } : {}) }) },
      updateMask: { fieldPaths: [`responders.\`${donor}\``, "lastResponderUid", ...(fills ? ["status"] : [])] },
      updateTransforms: [...serverTime("updatedAt"),
        ...(d.to === "DONATED" ? [{ fieldPath: "unitsFulfilled", increment: { integerValue: "1" } }] : [])] },
    ...(fills ? [{ update: { name: name(`userStats/${uid}`), fields: fields({ lastRequestId: rid }) },
      updateMask: { fieldPaths: ["lastRequestId"] },
      updateTransforms: [{ fieldPath: "openRequests", increment: { integerValue: "-1" } }] }] : []),
    { update: { name: name(`users/${donor}/notifications/${d.inbox}_${rid}`),
        fields: fields({ type: d.inbox, fromUid: uid, requestId: rid }) },
      updateTransforms: serverTime("createdAt"), currentDocument: { exists: false } },
  ]);
  if (!res.ok) fail(`${d.done} refused by the rules: ${err(res)}`);
  console.log(`PASS  offer from ${donor} on ${rid} ${d.done} through the rules (+ the donor's inbox item)${fills ? "; the request is FULFILLED (userStats -1)" : ""}`);
  const n = await workerPost(`/v1/requests/${rid}/responses/${donor}/notify`);
  if (fills) {
    // Donors still OFFERED or CONFIRMED learn it's fulfilled (REQUEST_FULFILLED), as after a close.
    const nr = await workerPost(`/v1/requests/${rid}/notify-responders`);
    process.exit((n && n.status !== 202) || (nr && nr.status !== 202) ? 1 : 0);
  }
  console.log(`      to close it later: node scripts/live-request.mjs --email ${args.email} --cancel ${rid}`);
  process.exit(n && n.status !== 202 ? 1 : 0);
}

// ---------------------------------------------------------------- ask a donor (contract 14)
// One batch, as the app: the donor's inbox item, the request's askCount +1, and the donor
// named in this account's own userStats (private). The rules check the donor is listed, has
// an accepted group and hasn't blocked this account. Then the Worker pushes ASKED_TO_DONATE.
if (asking) {
  const rid = String(args.ask);
  const donor = String(args.donor);
  const r = await myOpenRequest(rid);
  if ((r.askCount ?? 0) >= 10) fail(`request ${rid} already asked 10 donors (the limit)`);
  const res = await commit([
    { update: { name: name(`users/${donor}/notifications/ask_${rid}`),
        fields: fields({ type: "ASKED_TO_DONATE", fromUid: uid, requestId: rid }) },
      updateTransforms: serverTime("createdAt"), currentDocument: { exists: false } },
    { update: { name: name(`requests/${rid}`), fields: {} }, updateMask: { fieldPaths: [] },
      updateTransforms: [{ fieldPath: "askCount", increment: { integerValue: "1" } }, ...serverTime("updatedAt")],
      currentDocument: { exists: true } },
    { update: { name: name(`userStats/${uid}`), fields: fields({ lastAskedUid: donor, lastAskRid: rid }) },
      updateMask: { fieldPaths: ["lastAskedUid", "lastAskRid"] }, currentDocument: { exists: true } },
  ]);
  if (!res.ok) fail(`ask refused by the rules (listed? accepted group? blocked? asked before?): ${err(res)}`);
  console.log(`PASS  donor ${donor} asked for ${rid} through the rules (askCount ${(r.askCount ?? 0) + 1})`);
  const n = await workerPost(`/v1/requests/${rid}/asks/${donor}/notify`);
  console.log(`      to close it later: node scripts/live-request.mjs --email ${args.email} --cancel ${rid}`);
  process.exit(n && n.status !== 202 ? 1 : 0);
}

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
  // Donors still OFFERED or CONFIRMED get REQUEST_CANCELLED, as from the app.
  const n = await workerPost(`/v1/requests/${rid}/notify-responders`);
  process.exit(n && n.status !== 202 ? 1 : 0);
}

// ---------------------------------------------------------------- delete the account (contract 16)
// The same rules-checked steps as the app's account deletion (firebase/README.md, contract 16).
// Each step re-reads the server state, so an interrupted run can simply be started again.
if (deleting) {
  const DELETED = "Deleted user";
  const getDoc = async (path) => {
    const r = await call(`${DOCS}/${path}`, undefined, bearer);
    if (r.status === 404) return null;
    if (!r.ok) fail(`couldn't read ${path}: ${err(r)}`);
    return r.json;
  };
  const listDocs = async (path) => {
    const r = await call(`${DOCS}/${path}?pageSize=300`, undefined, bearer);
    if (!r.ok) fail(`couldn't list ${path}: ${err(r)}`);
    return r.json.documents ?? [];
  };
  const query = async (collectionId, allDescendants, field) => {
    const r = await call(`${DOCS}:runQuery`, { structuredQuery: {
      from: [{ collectionId, allDescendants }],
      where: { fieldFilter: { field: { fieldPath: field }, op: "EQUAL", value: { stringValue: uid } } },
      orderBy: [{ field: { fieldPath: "createdAt" }, direction: "DESCENDING" }], // the indexed order
      limit: 500,
    } }, bearer);
    if (!r.ok) fail(`couldn't query ${collectionId}: ${err(r)}`);
    return r.json.filter((x) => x.document).map((x) => x.document);
  };
  const must = async (writes, what) => {
    const r = await commit(writes);
    if (!r.ok) fail(`${what} refused: ${err(r)}. Nothing else changed; run the same command again.`);
  };
  const entryPath = `responders.\`${uid}\``; // backticks: a uid may start with a digit

  const requests = await query("requests", false, "requesterUid");
  const offers = await query("responses", true, "donorUid");
  const inbox = await listDocs(`users/${uid}/notifications`);
  const donations = await listDocs(`users/${uid}/donations`);
  console.log(`This deletes ${args.email} (uid ${uid}) the way the app does:`);
  console.log(`  anonymizes ${requests.length} request(s) and ${offers.length} offer(s) (they stay, as "${DELETED}"),`);
  console.log(`  deletes their contact details, ${inbox.length} inbox item(s), ${donations.length} donation(s), the profile and the login.`);
  if (args.yes !== true && (await ask("Type DELETE to go on: ")) !== "DELETE") {
    console.log("Stopped. Nothing changed.");
    process.exit(0);
  }

  // 1. Withdraw my active offers (contract 10).
  let withdrawn = 0;
  for (const o of offers) {
    const resp = await getDoc(pathOf(o.name));
    if (!resp || !["OFFERED", "CONFIRMED"].includes(fieldsOf(resp).status)) continue;
    const rid = pathOf(o.name).split("/")[1];
    const entry = fieldsOf(await getDoc(`requests/${rid}`)).responders?.[uid];
    if (!entry) fail(`request ${rid} has no responders entry for this account`);
    await must([
      { update: { name: o.name, fields: fields({ status: "WITHDRAWN" }) }, updateMask: { fieldPaths: ["status"] },
        updateTransforms: serverTime("updatedAt"), currentDocument: { updateTime: resp.updateTime } },
      { update: { name: name(`requests/${rid}`), fields: fields({
          responders: { [uid]: { name: entry.name, group: entry.group, status: "WITHDRAWN" } }, lastResponderUid: uid }) },
        updateMask: { fieldPaths: [entryPath, "lastResponderUid"] }, updateTransforms: serverTime("updatedAt") },
    ], `withdrawing the offer on ${rid}`);
    withdrawn++;
  }
  console.log(`PASS  1. ${withdrawn} active offer(s) withdrawn`);

  // 2. Anonymize my requests; an OPEN one is cancelled with userStats -1 (one per batch).
  let anonymized = 0, cancelled = 0;
  for (const d of requests) {
    const rid = pathOf(d.name).split("/")[1];
    const doc = await getDoc(`requests/${rid}`);
    const r = fieldsOf(doc);
    const open = r.status === "OPEN";
    if (!open && r.requesterDisplayName === DELETED && r.notes === undefined && r.closedReason === undefined && r.flagged === false) continue;
    const writes = [{
      update: { name: d.name, fields: fields({ requesterDisplayName: DELETED, flagged: false, ...(open ? { status: "CANCELLED" } : {}) }) },
      updateMask: { fieldPaths: ["requesterDisplayName", "notes", "closedReason", "flagged", ...(open ? ["status"] : [])] },
      updateTransforms: serverTime("updatedAt"), currentDocument: { updateTime: doc.updateTime },
    }];
    if (open) {
      writes.push({ update: { name: name(`userStats/${uid}`), fields: fields({ lastRequestId: rid }) },
        updateMask: { fieldPaths: ["lastRequestId"] },
        updateTransforms: [{ fieldPath: "openRequests", increment: { integerValue: "-1" } }] });
    }
    await must(writes, `anonymizing request ${rid}`);
    anonymized++;
    if (open) cancelled++;
  }
  console.log(`PASS  2. ${anonymized} request(s) anonymized, ${cancelled} of them cancelled`);

  // 3. Anonymize my offers, in any status: the responders entry + the response, one batch each.
  let offersDone = 0;
  for (const o of offers) {
    const resp = await getDoc(pathOf(o.name));
    if (!resp) continue;
    const rf = fieldsOf(resp);
    const rid = pathOf(o.name).split("/")[1];
    const entry = fieldsOf(await getDoc(`requests/${rid}`)).responders?.[uid];
    if (rf.donorDisplayName === DELETED && rf.donorPhone === undefined && rf.message === undefined && entry?.name === DELETED) continue;
    await must([
      ...(entry ? [{ update: { name: name(`requests/${rid}`), fields: fields({
          responders: { [uid]: { name: DELETED, group: entry.group, status: entry.status } } }) },
        updateMask: { fieldPaths: [entryPath] }, updateTransforms: serverTime("updatedAt") }] : []),
      { update: { name: o.name, fields: fields({ donorDisplayName: DELETED }) },
        updateMask: { fieldPaths: ["donorDisplayName", "donorPhone", "message"] },
        updateTransforms: serverTime("updatedAt"), currentDocument: { updateTime: resp.updateTime } },
    ], `anonymizing the offer on ${rid}`);
    offersDone++;
  }
  console.log(`PASS  3. ${offersDone} offer(s) anonymized`);

  // 4. Delete my requests' contact docs and my inbox (deleting one that's gone is allowed).
  const gone = [
    ...requests.map((d) => ({ delete: `${d.name}/private/contact` })),
    ...(await listDocs(`users/${uid}/notifications`)).map((d) => ({ delete: d.name })),
  ];
  for (let i = 0; i < gone.length; i += 400) await must(gone.slice(i, i + 400), "deleting contacts and inbox");
  console.log(`PASS  4. ${requests.length} contact doc(s) and the inbox deleted`);

  // 5. Final batch: donor card + userStats (needs openRequests == 0) + donations + the profile.
  const stats = await getDoc(`userStats/${uid}`);
  if (stats && fieldsOf(stats).openRequests !== 0) {
    fail(`userStats still counts ${fieldsOf(stats).openRequests} open request(s); run the same command again`);
  }
  const profile = await getDoc(`users/${uid}`);
  const last = [
    { delete: name(`donors/${uid}`) },
    ...(stats ? [{ delete: name(`userStats/${uid}`) }] : []),
    ...(await listDocs(`users/${uid}/donations`)).map((d) => ({ delete: d.name })),
    ...(profile ? [{ delete: name(`users/${uid}`) }] : []),
  ];
  await must(last, "the final batch (profile, stats, donor card, donations)");
  console.log("PASS  5. profile, userStats, donor card and donations deleted");

  // 6. The login itself, last. (A phone would unsubscribe from its topics first; a script has none.)
  const del = await authCall("delete", { idToken });
  if (!del.ok) fail(`deleting the login failed: ${err(del)}; the data is already gone, run again to retry`);
  console.log(`PASS  6. login ${args.email} deleted. Reports it filed stay with the admins (contract 16).`);
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

if (args["no-dispatch"] || (emulator && !args.worker)) process.exit(0);
const t1 = Date.now();
const res = await call(`${WORKER}/v1/requests/${rid}/dispatch`, {}, bearer);
const t2 = Date.now();
console.log(`${res.status === 202 ? "PASS" : "FAIL"}  dispatch -> ${res.status} ${JSON.stringify(res.json)} in ${t2 - t1} ms`);
console.log(`      sent at ${new Date(t2).toISOString()} (compare with the phone's receive time)`);
console.log(`      to close it later: node scripts/live-request.mjs --email ${args.email} --cancel ${rid}`);
process.exit(res.status === 202 ? 0 : 1);
