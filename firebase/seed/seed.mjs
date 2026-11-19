// Synthetic demo data for the Firebase emulators. Nothing here is real:
// "Demo" names, example.test emails, +88017000000xx numbers.
// The real hospital list is seed/hospitals.csv (hospitals.mjs), not Firestore.
//
//   npm run seed          writes fresh data into running emulators (requests dated from now)
//   npm run seed:export   regenerates the committed snapshot in seed/emulator-data/
//
// Area codes are the HDX pcodes used by app/src/main/assets/bd-geo.json (BBS data via
// UN OCHA HDX). Each city corporation is one area. If bd-geo.json changes a code, update
// AREAS below and re-run seed:export.
import { pathToFileURL } from 'node:url';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { Timestamp, doc, setDoc } from 'firebase/firestore';

const PROJECT_ID = 'demo-liquidlyf';
const FIRESTORE = process.env.FIRESTORE_EMULATOR_HOST ?? '127.0.0.1:8085';
const AUTH = process.env.FIREBASE_AUTH_EMULATOR_HOST ?? '127.0.0.1:9099';
const PASSWORD = 'password123'; // emulator only
const DAY = 24 * 60 * 60 * 1000;
const now = Date.now();
const at = (ms) => Timestamp.fromMillis(Math.floor((now + ms) / 1000) * 1000);
const EPOCH = Timestamp.fromMillis(0);

const AREAS = {
  dhanmondi: { division: 'BD30', district: 'BD3026', upazila: 'BD30262000' }, // Dhaka South City Corporation
  mirpur: { division: 'BD30', district: 'BD3026', upazila: 'BD30262500' }, // Dhaka North City Corporation
  kotwali: { division: 'BD20', district: 'BD2015', upazila: 'BD20151600' }, // Chattogram City Corporation
};

// ------------------------------------------------------------------ people
const people = [
  { uid: 'demo-requester', name: 'Demo Rahim Requester', group: 'B_POS', area: 'dhanmondi', listed: false },
  { uid: 'demo-donor-bpos', name: 'Demo Karim Donor', group: 'B_POS', area: 'dhanmondi', listed: true },
  { uid: 'demo-donor-oneg', name: 'Demo Nasrin Donor', group: 'O_NEG', area: 'dhanmondi', listed: true, gender: 'FEMALE' },
  { uid: 'demo-donor-apos', name: 'Demo Jamal Donor', group: 'A_POS', area: 'mirpur', listed: true, lastDonationDaysAgo: 40 },
  { uid: 'demo-donor-abpos', name: 'Demo Shirin Donor', group: 'AB_POS', area: 'mirpur', listed: true, gender: 'FEMALE', available: false },
  { uid: 'demo-donor-ctg', name: 'Demo Tanvir Donor', group: 'B_POS', area: 'kotwali', listed: true },
  { uid: 'demo-new', name: 'Demo Newcomer', group: 'O_POS', area: 'mirpur', listed: false, ageDays: 2 },
  { uid: 'demo-admin', name: 'Demo Moderator', group: 'A_NEG', area: 'dhanmondi', listed: false, admin: true },
  // D1: the short setup's profile (name, phone, area). No blood group or donor details until "Become a donor".
  { uid: 'demo-requester-only', name: 'Demo Salma Requester', area: 'mirpur', listed: false, available: false, requesterOnly: true },
];

function email(uid) { return `${uid}@example.test`; }
function phone(i) { return `+88017000000${String(i + 10).padStart(2, '0')}`; }
// ------------------------------------------------------------------ short names
// The public short name, ported from the app's Validators.displayName (doc 07 §2), so the
// test data reads like what the app writes. Keep the two in step.
// D22 (the maintainer, 19 Nov 2026): the full Mohammad spellings, Mosammat and "Most" (no
// dot) are skipped like "Md." and "Mst.": "Mohammad Rafiqul Islam" → "Rafiqul I.".
const NAME_MIN = 2;
const DISPLAY_NAME_MAX = 40;
const NAME_PREFIXES = new Set([
  'md', 'mst', 'mohd', 'dr',
  'mohammad', 'mohammed', 'muhammad', 'muhammed', 'mohamed', 'mohamad', 'মোহাম্মদ', 'মোহাম্মাদ', 'মুহাম্মদ',
  'mosammat', 'মোসাম্মৎ', 'most',
].map((w) => w.normalize('NFC')));
const BRACKETED = /\([^)]*\)?|\[[^\]]*\]?/g; // "(brother)", "[ICU]", or an unclosed "(brother"
const EDGE_PUNCTUATION = /^(?:(?!\.)[\p{P}\p{Sm}\p{So}])+|(?:(?!\.)[\p{P}\p{Sm}\p{So}])+$/gu; // keeps "Md."'s dot

const normalizeName = (s) => s.trim().split(/\s+/).join(' ');
/** "Md.", "MD", "Dr.", "মোঃ", "Mohammad", "Most" and any other word ending in '.' or 'ঃ'. */
// Bangla in one Unicode form (NFC), like the app: "ো" typed as "ে" + "া" still matches.
const isNamePrefix = (w) => w.endsWith('.') || w.endsWith('ঃ') || NAME_PREFIXES.has(w.normalize('NFC').toLowerCase());
const trimPunctuation = (w) => w.replace(EDGE_PUNCTUATION, '');

function shortName(words) {
  if (words.length === 0) return '';
  const first = words[0];
  if (words.length === 1) return first.slice(0, DISPLAY_NAME_MAX);
  const last = words[words.length - 1];
  const suffix = ` ${String.fromCodePoint(last.codePointAt(0)).toUpperCase()}.`;
  return first.slice(0, DISPLAY_NAME_MAX - suffix.length) + suffix;
}

/** The app's rule: first name and last initial, leading "Md."/"Mohammad"/"Most"… skipped. */
export function appDisplayName(fullName) {
  const cleaned = normalizeName(fullName.replace(BRACKETED, ' ')) || normalizeName(fullName);
  const words = cleaned.split(' ').filter(Boolean);
  const start = words.findIndex((w) => !isNamePrefix(w));
  const withoutPrefixes = start === -1 ? words.slice(-1) : words.slice(start); // "Mohammad" alone stays whole
  const clean = (ws) => ws.map(trimPunctuation).filter(Boolean);
  const short = shortName(clean(withoutPrefixes));
  return short.length >= NAME_MIN ? short : shortName(clean(words)); // "Md. X" alone is too short
}

/** Seed names start with the "Demo" marker, which the short name leaves out: "Demo Karim Donor" → "Karim D.". */
export function displayName(full) {
  return appDisplayName(full.replace(/^Demo\s+/, ''));
}

function userDoc(p, i) {
  const created = at(-(p.ageDays ?? 60) * DAY);
  const u = {
    fullName: p.name,
    email: email(p.uid),
    phone: phone(i),
    bloodGroup: p.group,
    gender: p.gender ?? 'MALE',
    birthYear: 1990 + (i % 10),
    weightOk: true,
    ...AREAS[p.area],
    available: p.available ?? true,
    showInDonorSearch: p.listed,
    locale: i % 2 ? 'bn' : 'en',
    termsVersion: '2026-11',
    termsAcceptedAt: created,
    donationCount: 0,
    verifiedDonations: 0,
    hiddenRequestIds: [],
    blockedUids: [],
    createdAt: created,
    updatedAt: created,
  };
  if (p.requesterOnly) for (const k of ['bloodGroup', 'gender', 'birthYear', 'weightOk']) delete u[k];
  if (p.listed) u.consentAt = created;
  if (p.lastDonationDaysAgo) u.lastDonationAt = at(-p.lastDonationDaysAgo * DAY);
  return u;
}

function donorCard(u) {
  return {
    displayName: displayName(u.fullName),
    bloodGroup: u.bloodGroup,
    division: u.division,
    district: u.district,
    upazila: u.upazila,
    available: u.available,
    eligibleFrom: u.lastDonationAt ? Timestamp.fromMillis(u.lastDonationAt.toMillis() + 120 * DAY) : EPOCH,
    donationCount: u.donationCount,
    verifiedDonations: u.verifiedDonations,
    phoneVerified: false,
  };
}

// ------------------------------------------------------------------ requests
function request(id, o) {
  const created = at(-(o.hoursAgo ?? 1) * 60 * 60 * 1000);
  const neededBy = at((o.neededInHours ?? 48) * 60 * 60 * 1000);
  const { hoursAgo, neededInHours, ...fields } = o;
  return {
    id,
    data: {
      requesterUid: 'demo-requester',
      requesterDisplayName: 'Rahim R.',
      component: 'WHOLE_BLOOD',
      acceptCompatible: false,
      unitsNeeded: 1,
      unitsFulfilled: 0,
      hospitalId: 'demo-dmch',
      hospitalName: 'Demo Medical College Hospital',
      ...AREAS.dhanmondi,
      urgency: 'URGENT',
      neededBy,
      expiresAt: Timestamp.fromMillis(neededBy.toMillis() + DAY),
      notes: '',
      flagged: false,
      status: 'OPEN',
      responseCount: 0,
      reportCount: 0,
      askCount: 0,
      responders: {},
      fanoutTier: 1,
      nextTierAt: at(30 * 60 * 1000),
      createdAt: created,
      updatedAt: created,
      ...fields,
    },
  };
}

const requests = [
  request('demo-req-open-bpos', {
    bloodGroup: 'B_POS', acceptedGroups: ['B_POS'], unitsNeeded: 2, condition: 'SURGERY',
    notes: 'Surgery on Thursday morning. Ward 4.',
  }),
  request('demo-req-critical-oneg', {
    bloodGroup: 'O_NEG', acceptedGroups: ['O_NEG'], urgency: 'CRITICAL', condition: 'ACCIDENT',
    neededInHours: 6, hoursAgo: 0.5, notes: 'Road accident, emergency ward.',
  }),
  request('demo-req-compatible-abpos', {
    bloodGroup: 'AB_POS', acceptCompatible: true,
    acceptedGroups: ['A_POS', 'A_NEG', 'B_POS', 'B_NEG', 'AB_POS', 'AB_NEG', 'O_POS', 'O_NEG'],
    component: 'RED_CELLS', urgency: 'PLANNED', neededInHours: 24 * 5, condition: 'THALASSEMIA',
    ...AREAS.mirpur, hospitalId: 'demo-mirpur', hospitalName: 'Demo Mirpur General Hospital',
    requesterUid: 'demo-donor-apos', requesterDisplayName: 'Jamal D.',
  }),
  // A new account's single open request, with money words: flagged for review.
  request('demo-req-flagged', {
    bloodGroup: 'B_POS', acceptedGroups: ['B_POS'], notes: 'Send 2000 taka by bKash first', flagged: true,
    requesterUid: 'demo-new', requesterDisplayName: 'Newcomer D.',
  }),
  request('demo-req-fulfilled', {
    bloodGroup: 'B_POS', acceptedGroups: ['B_POS'], status: 'FULFILLED', unitsFulfilled: 1,
    hoursAgo: 72, neededInHours: -24, closedReason: 'Donor found through LiquidLyf',
  }),
];

// One offered response and one confirmed response on the open B+ request.
const responses = [
  { rid: 'demo-req-open-bpos', donor: 'demo-donor-bpos', status: 'CONFIRMED' },
  { rid: 'demo-req-open-bpos', donor: 'demo-donor-ctg', status: 'OFFERED' },
];

function owner(rid) { return requests.find((r) => r.id === rid).data.requesterUid; }

// ------------------------------------------------------------------ auth
async function authCall(path, body) {
  const res = await fetch(`http://${AUTH}/identitytoolkit.googleapis.com/v1/projects/${PROJECT_ID}/${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer owner' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`Auth emulator ${path}: ${res.status} ${await res.text()}`);
  return res.json();
}

async function seedAuth() {
  await fetch(`http://${AUTH}/emulator/v1/projects/${PROJECT_ID}/accounts`, { method: 'DELETE' });
  for (const p of people) {
    await authCall('accounts', {
      localId: p.uid, email: email(p.uid), password: PASSWORD, emailVerified: true, displayName: p.name,
    });
    if (p.admin) {
      await authCall('accounts:update', { localId: p.uid, customAttributes: JSON.stringify({ admin: true }) });
    }
  }
}

// ------------------------------------------------------------------ firestore
export const seedPeople = people;
export const seedRequests = requests;

/** Write every seed doc with an admin (rules-disabled) Firestore. Used by rules-tests/seed.test.js. */
export async function writeSeed(db) {
    const users = {};
    for (const [i, p] of people.entries()) {
      const u = userDoc(p, i);
      users[p.uid] = u;
      await setDoc(doc(db, 'users', p.uid), u);
      if (p.listed) await setDoc(doc(db, 'donors', p.uid), donorCard(u));
    }
    // userStats must agree with the requests (the rules enforce 1 open for new accounts, 3 after).
    for (const p of people) {
      const own = requests.filter((r) => r.data.requesterUid === p.uid);
      const stats = { openRequests: own.filter((r) => r.data.status === 'OPEN').length, totalRequests: own.length };
      if (own.length) stats.lastRequestId = own[own.length - 1].id;
      await setDoc(doc(db, 'userStats', p.uid), stats);
    }
    for (const r of requests) {
      const data = { ...r.data };
      const mine = responses.filter((x) => x.rid === r.id);
      for (const x of mine) {
        const d = users[x.donor];
        data.responders = { ...data.responders, [x.donor]: { name: displayName(d.fullName), group: d.bloodGroup, status: x.status } };
      }
      data.responseCount = mine.length;
      if (mine.length) data.lastResponderUid = mine[mine.length - 1].donor;
      await setDoc(doc(db, 'requests', r.id), data);
      await setDoc(doc(db, 'requests', r.id, 'private', 'contact'), {
        requesterUid: data.requesterUid, contactName: 'Demo Rahim (brother)', contactPhone: '+8801700000099',
        patientName: 'Demo Patient', wardOrBed: 'Ward 4, Bed 12',
      });
    }
    for (const x of responses) {
      const d = users[x.donor];
      const created = at(-20 * 60 * 1000);
      await setDoc(doc(db, 'requests', x.rid, 'responses', x.donor), {
        donorUid: x.donor, requestId: x.rid, requesterUid: owner(x.rid), status: x.status,
        donorDisplayName: displayName(d.fullName), donorBloodGroup: d.bloodGroup, donorPhone: d.phone,
        message: 'I can come within an hour.', createdAt: created, updatedAt: created,
      });
      await setDoc(doc(db, 'users', owner(x.rid), 'notifications', `NEW_OFFER_${x.rid}_${x.donor}`), {
        type: 'NEW_OFFER', fromUid: x.donor, requestId: x.rid, createdAt: created,
      });
    }
}

async function seedFirestore() {
  const [host, port] = FIRESTORE.split(':');
  const env = await initializeTestEnvironment({ projectId: PROJECT_ID, firestore: { host, port: Number(port) } });
  await env.clearFirestore();
  await env.withSecurityRulesDisabled((ctx) => writeSeed(ctx.firestore()));
  await env.cleanup();
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  await seedAuth();
  await seedFirestore();
  console.log(`Seeded ${people.length} users (password "${PASSWORD}"), ${requests.length} requests.`);
}
