// Test-day helpers for the 5-person user test (ui-audit/user-test-plan.md). EMULATORS ONLY:
// every command refuses to run unless it reaches a Firebase emulator on this PC for the
// demo-liquidlyf project. No credentials, no service account, no real project.
//
//   npm run usertest:offer                      Demo Karim (B+) offers on the newest open request
//                                               a tester (pN@example.test) posted
//   npm run usertest:verify p1@example.test  marks that test account's email as verified
//
// The offer goes through the security rules, signed in as Karim, with the same three writes
// the app makes (response + the request's responders entry + the requester's inbox item), so
// the tester's request page shows it within seconds, just like a real offer.
import { pathToFileURL } from 'node:url';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection, doc, getDoc, getDocs, increment, query, serverTimestamp, where, writeBatch,
} from 'firebase/firestore';

const PROJECT_ID = 'demo-liquidlyf';
const KARIM = 'demo-donor-bpos'; // seed.mjs: Demo Karim Donor, B+, listed, eligible, Dhaka
const TESTERS = [1, 2, 3, 4, 5].map((n) => `p${n}@example.test`);
const MESSAGE = 'আমি এক ঘণ্টার মধ্যে আসতে পারব।'; // "I can come within an hour."
const LOCAL = new Set(['127.0.0.1', 'localhost', '[::1]', '::1']);

export class Refused extends Error {}

/**
 * The safety check. Returns { host, port } only for an emulator on this PC; otherwise throws.
 * `name` is the env var the Firebase tools use; `fallback` = firebase.json's port.
 */
export function localHost(env, name, fallback) {
  const project = env.GCLOUD_PROJECT ?? env.GOOGLE_CLOUD_PROJECT;
  if (project && project !== PROJECT_ID) {
    throw new Refused(`refused: the project is "${project}". These helpers only work on ${PROJECT_ID} (the emulators).`);
  }
  const value = env[name] ?? fallback;
  const m = /^(\[[^\]]+\]|[^:]+):(\d+)$/.exec(value);
  if (!m || !LOCAL.has(m[1])) {
    throw new Refused(`refused: ${name}=${value} is not an emulator on this PC (127.0.0.1 or localhost only).`);
  }
  return { host: m[1].replace(/^\[|\]$/g, ''), port: Number(m[2]), hostPort: value };
}

/** Is something answering there, and is it the emulator? */
async function probe(url, what) {
  let res;
  try {
    res = await fetch(url, { signal: AbortSignal.timeout(3000) });
  } catch {
    throw new Refused(`no ${what} emulator answers at ${url}. Start the emulators first (npm run emulators).`);
  }
  if (!res.ok) throw new Refused(`${url} answered ${res.status}: that's not the ${what} emulator.`);
}

const shortTime = (t) => t?.toDate?.().toLocaleString('en-GB', { timeZone: 'Asia/Dhaka' }) ?? '?';

export async function karimOffers(env = process.env, message = MESSAGE) {
  const fs = localHost(env, 'FIRESTORE_EMULATOR_HOST', '127.0.0.1:8085');
  await probe(`http://${fs.hostPort}/`, 'Firestore');
  const testEnv = await initializeTestEnvironment({ projectId: PROJECT_ID, firestore: { host: fs.host, port: fs.port } });
  try {
    // Find the testers and their newest open request (read without rules: emulator only).
    let target;
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      const users = await getDocs(query(collection(db, 'users'), where('email', 'in', TESTERS)));
      const email = Object.fromEntries(users.docs.map((d) => [d.id, d.data().email]));
      if (!users.size) throw new Refused(`no tester account yet (${TESTERS[0]} … ${TESTERS[4]}). Has the tester finished signing up?`);
      const open = await getDocs(query(collection(db, 'requests'),
        where('requesterUid', 'in', Object.keys(email)), where('status', '==', 'OPEN')));
      const newest = open.docs.sort((a, b) => b.data().createdAt.toMillis() - a.data().createdAt.toMillis())[0];
      if (!newest) throw new Refused(`no open request from ${Object.values(email).join(', ')}. Has the tester posted one?`);
      const karim = (await getDoc(doc(db, 'users', KARIM))).data();
      if (!karim) throw new Refused('Demo Karim is missing. Run npm run seed for fresh test data.');
      target = { id: newest.id, r: newest.data(), tester: email[newest.data().requesterUid], karim };
    });
    const { id, r, tester, karim } = target;
    const label = `${tester}'s request ${id} (${r.bloodGroup}, ${r.hospitalName}, posted ${shortTime(r.createdAt)})`;
    if (r.responders?.[KARIM]) throw new Refused(`Karim already offered on ${label}.`);
    if (!r.acceptedGroups?.includes(karim.bloodGroup)) {
      throw new Refused(`${label} doesn't accept ${karim.bloodGroup}, so Karim can't offer. It accepts ${r.acceptedGroups?.join(', ')}.`);
    }
    if (r.expiresAt?.toMillis() <= Date.now()) throw new Refused(`${label} has expired.`);

    // The app's offer batch (OfferRepository.offer), signed in as Karim, through the rules.
    const db = testEnv.authenticatedContext(KARIM, { email: karim.email, email_verified: true }).firestore();
    const name = `${karim.fullName.split(' ')[1]} ${karim.fullName.split(' ')[2][0]}.`; // "Karim D."
    const b = writeBatch(db);
    b.set(doc(db, 'requests', id, 'responses', KARIM), {
      donorUid: KARIM, requestId: id, requesterUid: r.requesterUid, status: 'OFFERED',
      donorDisplayName: name, donorBloodGroup: karim.bloodGroup, donorPhone: karim.phone, message,
      createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
    });
    b.update(doc(db, 'requests', id), {
      [`responders.${KARIM}`]: { name, group: karim.bloodGroup, status: 'OFFERED' },
      responseCount: increment(1), lastResponderUid: KARIM, updatedAt: serverTimestamp(),
    });
    b.set(doc(db, 'users', r.requesterUid, 'notifications', `NEW_OFFER_${id}_${KARIM}`), {
      type: 'NEW_OFFER', fromUid: KARIM, requestId: id, createdAt: serverTimestamp(),
    });
    try {
      await b.commit();
    } catch (e) {
      throw new Refused(`the rules refused Karim's offer on ${label}: ${e.message}`);
    }
    return `Karim D. (${karim.bloodGroup}) offered on ${label}: "${message}"`;
  } finally {
    await testEnv.cleanup();
  }
}

export async function verifyEmail(email, env = process.env) {
  if (!/^[a-z0-9._-]+@example\.test$/i.test(email ?? '')) {
    throw new Refused('give a test account\'s email, for example: npm run usertest:verify p1@example.test');
  }
  const auth = localHost(env, 'FIREBASE_AUTH_EMULATOR_HOST', '127.0.0.1:9099');
  const base = `http://${auth.hostPort}`;
  await probe(`${base}/emulator/v1/projects/${PROJECT_ID}/config`, 'Auth');
  const call = async (path, body) => {
    const res = await fetch(`${base}/identitytoolkit.googleapis.com/v1/projects/${PROJECT_ID}/${path}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer owner' },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Refused(`Auth emulator ${path}: ${res.status} ${await res.text()}`);
    return res.json();
  };
  const user = (await call('accounts:lookup', { email: [email] })).users?.[0];
  if (!user) throw new Refused(`no account ${email} in the Auth emulator. Has the tester signed up?`);
  if (user.emailVerified) return `${email} was already verified. Ask them to tap "I've verified" again.`;
  await call('accounts:update', { localId: user.localId, emailVerified: true });
  return `${email} is verified. Ask them to tap "I've verified".`;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [cmd, arg] = process.argv.slice(2);
  try {
    if (cmd === 'offer') console.log(await karimOffers(process.env, arg || MESSAGE));
    else if (cmd === 'verify') console.log(await verifyEmail(arg));
    else throw new Refused('usage: node seed/usertest.mjs offer ["message"] | verify <email>');
  } catch (e) {
    console.error(e instanceof Refused ? e.message : e);
    process.exitCode = 1;
  }
}
