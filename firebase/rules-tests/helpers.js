// Shared fixtures for the Firestore rules tests.
// All data is synthetic: fake names, example.test emails, +88017000000xx numbers.
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import {
  Timestamp, doc, setDoc, writeBatch, serverTimestamp, increment,
} from 'firebase/firestore';

export const PROJECT_ID = 'demo-liquidlyf';
export const DAY = 24 * 60 * 60 * 1000;

let env;

export async function setupEnv() {
  const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST ?? '127.0.0.1:8085').split(':');
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      host,
      port: Number(port),
      // RULES_FILE lets a mutation check load a deliberately weakened copy.
      rules: readFileSync(process.env.RULES_FILE ?? new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
  });
  return env;
}

export async function teardownEnv() {
  await env?.cleanup();
}

export function getEnv() {
  return env;
}

/** Timestamp relative to now. */
export function ts(msFromNow) {
  return Timestamp.fromMillis(Date.now() + msFromNow);
}

/** Firestore for a signed-in user. email_verified defaults to true. */
export function as(uid, claims = {}) {
  return env
    .authenticatedContext(uid, { email: `${uid}@example.test`, email_verified: true, ...claims })
    .firestore();
}

export function anon() {
  return env.unauthenticatedContext().firestore();
}

/** Write fixtures with the rules switched off. */
export async function seed(fn) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await fn(ctx.firestore());
  });
}

// ---------------------------------------------------------------- users

let phoneSeq = 10;
export function fakePhone() {
  phoneSeq = (phoneSeq + 1) % 100;
  return `+88017000000${String(phoneSeq).padStart(2, '0')}`;
}

export const AREA = { division: 'BD30', district: 'BD3026', upazila: 'BD30262000' }; // HDX pcodes, as in bd-geo.json

/** A complete, valid users/{uid} doc as stored (fixed timestamps, for seeding). */
export function userDoc(uid, o = {}) {
  return {
    fullName: `Test ${uid}`,
    email: `${uid}@example.test`,
    phone: fakePhone(),
    bloodGroup: 'B_POS',
    gender: 'MALE',
    birthYear: 1995,
    weightOk: true,
    ...AREA,
    available: true,
    showInDonorSearch: false,
    locale: 'en',
    termsVersion: '2026-11',
    termsAcceptedAt: ts(-30 * DAY),
    donationCount: 0,
    verifiedDonations: 0,
    createdAt: ts(-30 * DAY),
    updatedAt: ts(-30 * DAY),
    ...o,
  };
}

/** A users doc as the app writes it on profile setup (server timestamps). */
export function newUserDoc(uid, o = {}) {
  return userDoc(uid, {
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    termsAcceptedAt: serverTimestamp(),
    ...o,
  });
}

export const EPOCH = Timestamp.fromMillis(0);

/** The public donor card that mirrors a users doc. */
export function donorCard(u, o = {}) {
  const card = {
    displayName: `${u.fullName.split(' ')[0]} T.`,
    bloodGroup: u.bloodGroup,
    division: u.division,
    district: u.district,
    upazila: u.upazila,
    available: u.available,
    eligibleFrom: u.lastDonationAt
      ? Timestamp.fromMillis(u.lastDonationAt.toMillis() + 120 * DAY)
      : EPOCH,
    donationCount: u.donationCount,
    verifiedDonations: u.verifiedDonations,
    phoneVerified: u.phoneVerified ?? false,
  };
  if (u.lastActiveAt !== undefined) card.lastActiveAt = u.lastActiveAt;
  return { ...card, ...o };
}

/** Seed users (+ userStats, + donor card when listed). */
export async function seedUsers(users) {
  await seed(async (db) => {
    for (const [uid, u] of Object.entries(users)) {
      await setDoc(doc(db, 'users', uid), u);
      await setDoc(doc(db, 'userStats', uid), { openRequests: 0, totalRequests: 0 });
      if (u.showInDonorSearch) await setDoc(doc(db, 'donors', uid), donorCard(u));
    }
  });
}

/** The standard cast used by most tests. */
export function cast() {
  return {
    alice: userDoc('alice'), // requester, account 30 days old
    bob: userDoc('bob', { showInDonorSearch: true, consentAt: ts(-30 * DAY) }), // B+ donor
    carol: userDoc('carol', { bloodGroup: 'O_NEG', showInDonorSearch: true, consentAt: ts(-30 * DAY) }),
    dave: userDoc('dave', { bloodGroup: 'A_POS' }), // incompatible with B+ exact
    eve: userDoc('eve'), // a stranger
    newbie: userDoc('newbie', { createdAt: ts(-1 * DAY), updatedAt: ts(-1 * DAY) }),
  };
}

// ---------------------------------------------------------------- requests

/** A new request as the app writes it (server timestamps). */
export function newRequestDoc(uid, o = {}) {
  const neededBy = Timestamp.fromMillis(Math.floor((Date.now() + 2 * DAY) / 1000) * 1000);
  return {
    requesterUid: uid,
    requesterDisplayName: 'Alice T.',
    bloodGroup: 'B_POS',
    component: 'WHOLE_BLOOD',
    acceptCompatible: false,
    acceptedGroups: ['B_POS'],
    unitsNeeded: 2,
    unitsFulfilled: 0,
    hospitalName: 'Test Medical College Hospital',
    ...AREA,
    urgency: 'URGENT',
    neededBy,
    expiresAt: Timestamp.fromMillis(neededBy.toMillis() + DAY),
    notes: 'Patient in ward 5',
    flagged: false,
    status: 'OPEN',
    responseCount: 0,
    reportCount: 0,
    askCount: 0,
    responders: {},
    fanoutTier: 0,
    nextTierAt: serverTimestamp(),
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...o,
  };
}

/** A stored request (fixed timestamps, for seeding). */
export function requestDoc(uid, o = {}) {
  const created = ts(-1 * 60 * 60 * 1000);
  return newRequestDoc(uid, { nextTierAt: created, createdAt: created, updatedAt: created, ...o });
}

export function contactDoc(uid, o = {}) {
  return { requesterUid: uid, contactName: 'Alice Test', contactPhone: '+8801700000099', ...o };
}

/**
 * The request-create batch: request + userStats +1 (naming this request) + contact.
 * Pass `stats: false` to leave userStats out, or an object to override its update.
 */
export function createRequest(db, uid, rid, o = {}, { stats = {}, contact = true } = {}) {
  const b = writeBatch(db);
  b.set(doc(db, 'requests', rid), newRequestDoc(uid, o));
  if (stats !== false) {
    b.update(doc(db, 'userStats', uid), {
      openRequests: increment(1),
      totalRequests: increment(1),
      lastRequestId: rid,
      ...stats,
    });
  }
  if (contact) b.set(doc(db, 'requests', rid, 'private', 'contact'), contactDoc(uid));
  return b.commit();
}

/** Seed an OPEN request owned by uid (and count it in their userStats). */
export async function seedRequest(rid, uid, o = {}, openRequests = 1) {
  await seed(async (db) => {
    await setDoc(doc(db, 'requests', rid), requestDoc(uid, o));
    await setDoc(doc(db, 'requests', rid, 'private', 'contact'), contactDoc(uid));
    await setDoc(doc(db, 'userStats', uid), {
      openRequests, totalRequests: openRequests, lastRequestId: rid,
    });
  });
}

// ---------------------------------------------------------------- responses

export function responseDoc(donorUid, rid, requesterUid, donorUser, o = {}) {
  return {
    donorUid,
    requestId: rid,
    requesterUid,
    status: 'OFFERED',
    donorDisplayName: `${donorUser.fullName.split(' ')[0]} T.`,
    donorBloodGroup: donorUser.bloodGroup,
    donorPhone: donorUser.phone,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...o,
  };
}

/** The donor's offer batch: response + request.responders[me] + responseCount +1. */
export function offer(db, donorUid, rid, requesterUid, donorUser, { response = {}, entry = {}, request = {} } = {}) {
  const r = responseDoc(donorUid, rid, requesterUid, donorUser, response);
  const b = writeBatch(db);
  b.set(doc(db, 'requests', rid, 'responses', donorUid), r);
  b.update(doc(db, 'requests', rid), {
    [`responders.${donorUid}`]: { name: r.donorDisplayName, group: r.donorBloodGroup, status: 'OFFERED', ...entry },
    responseCount: increment(1),
    lastResponderUid: donorUid,
    updatedAt: serverTimestamp(),
    ...request,
  });
  return b.commit();
}

/** Seed a response in a given status, with the matching responders entry. */
export async function seedResponse(rid, donorUid, requesterUid, donorUser, status) {
  const r = responseDoc(donorUid, rid, requesterUid, donorUser, {
    status, createdAt: ts(-60_000), updatedAt: ts(-60_000),
  });
  await seed(async (db) => {
    await setDoc(doc(db, 'requests', rid, 'responses', donorUid), r);
    const b = writeBatch(db);
    b.update(doc(db, 'requests', rid), {
      [`responders.${donorUid}`]: { name: r.donorDisplayName, group: r.donorBloodGroup, status },
      responseCount: increment(1),
      lastResponderUid: donorUid,
    });
    await b.commit();
  });
}

/**
 * The requester's decision batch: response status + responders[k].status
 * (+ unitsFulfilled +1 for DONATED). `extra` adds more request fields.
 */
export function decide(db, rid, donorUid, status, { extra = {}, units = status === 'DONATED' ? 1 : 0 } = {}) {
  const b = writeBatch(db);
  b.update(doc(db, 'requests', rid, 'responses', donorUid), { status, updatedAt: serverTimestamp() });
  const req = {
    [`responders.${donorUid}.status`]: status,
    lastResponderUid: donorUid,
    updatedAt: serverTimestamp(),
    ...extra,
  };
  if (units) req.unitsFulfilled = increment(units);
  b.update(doc(db, 'requests', rid), req);
  return b.commit();
}
