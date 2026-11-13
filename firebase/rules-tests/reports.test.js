// reports/{reporterUid}_{targetId} and the reportCount / auto-hide batch.
// Doc 07 §5 test 9; Spark test 6.
import { describe, test, beforeAll, afterAll, beforeEach } from 'vitest';
import { assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import {
  doc, getDoc, getDocs, setDoc, updateDoc, deleteDoc, writeBatch, serverTimestamp, increment,
  collection, query, where,
} from 'firebase/firestore';
import {
  setupEnv, teardownEnv, getEnv, as, seed, seedUsers, cast, seedRequest,
} from './helpers.js';

beforeAll(setupEnv);
afterAll(teardownEnv);
beforeEach(async () => {
  await getEnv().clearFirestore();
  await seedUsers(cast());
  await seedRequest('r1', 'alice');
});

function reportDoc(reporterUid, targetId, o = {}) {
  return {
    reporterUid, targetType: 'REQUEST', targetId, reason: 'ASKING_MONEY',
    note: 'Asked for bKash', status: 'OPEN', createdAt: serverTimestamp(), ...o,
  };
}

/** The report batch: report doc + reportCount +1 (+ optional status). */
function reportRequest(db, uid, rid = 'r1', { status, count = 1, report = {} } = {}) {
  const b = writeBatch(db);
  b.set(doc(db, 'reports', `${uid}_${rid}`), reportDoc(uid, rid, report));
  const upd = { reportCount: increment(count), updatedAt: serverTimestamp() };
  if (status) upd.status = status;
  b.update(doc(db, 'requests', rid), upd);
  return b.commit();
}

describe('reports: create only, admin reads (doc 07 test 9)', () => {
  test('a verified user can file a report', async () => {
    await assertSucceeds(reportRequest(as('eve'), 'eve'));
  });

  test('a user report needs no counter', async () => {
    await assertSucceeds(setDoc(doc(as('eve'), 'reports/eve_alice'), reportDoc('eve', 'alice', { targetType: 'USER' })));
  });

  test('the reporter cannot read, edit or delete their report', async () => {
    await reportRequest(as('eve'), 'eve');
    await assertFails(getDoc(doc(as('eve'), 'reports/eve_r1')));
    await assertFails(updateDoc(doc(as('eve'), 'reports/eve_r1'), { reason: 'SPAM' }));
    await assertFails(deleteDoc(doc(as('eve'), 'reports/eve_r1')));
  });

  test('only an admin can read and handle reports', async () => {
    await reportRequest(as('eve'), 'eve');
    const mod = as('mod', { admin: true });
    await assertSucceeds(getDocs(query(collection(mod, 'reports'), where('status', '==', 'OPEN'))));
    await assertSucceeds(updateDoc(doc(mod, 'reports/eve_r1'), { status: 'ACTIONED', handledBy: 'mod', handledAt: serverTimestamp() }));
    await assertFails(getDocs(collection(as('eve'), 'reports')));
  });

  test('the report ID must be {me}_{targetId}, with a valid reason', async () => {
    await assertFails(setDoc(doc(as('eve'), 'reports/whatever'), reportDoc('eve', 'r1')));
    await assertFails(setDoc(doc(as('eve'), 'reports/bob_r1'), reportDoc('bob', 'r1')));
    await assertFails(setDoc(doc(as('eve'), 'reports/eve_alice'), reportDoc('eve', 'alice', { targetType: 'USER', reason: 'BORED' })));
  });

  test('an unverified user cannot report', async () => {
    await assertFails(reportRequest(as('eve', { email_verified: false }), 'eve'));
  });
});

describe('reportCount and auto-hide (Spark test 6)', () => {
  test('reportCount +1 needs a new report doc in the same batch', async () => {
    await assertFails(updateDoc(doc(as('eve'), 'requests/r1'), { reportCount: increment(1), updatedAt: serverTimestamp() }));
  });

  test('the same reporter cannot count twice', async () => {
    await assertSucceeds(reportRequest(as('eve'), 'eve'));
    await assertFails(reportRequest(as('eve'), 'eve'));
    await assertFails(updateDoc(doc(as('eve'), 'requests/r1'), { reportCount: increment(1), updatedAt: serverTimestamp() }));
  });

  test('reportCount moves by exactly 1', async () => {
    await assertFails(reportRequest(as('eve'), 'eve', 'r1', { count: 3 }));
  });

  test('REMOVED is not allowed below 3 reports', async () => {
    await assertFails(reportRequest(as('eve'), 'eve', 'r1', { status: 'REMOVED' }));
    await seed(async (db) => updateDoc(doc(db, 'requests/r1'), { reportCount: 1 }));
    await assertFails(reportRequest(as('eve'), 'eve', 'r1', { status: 'REMOVED' }));
  });

  test('the third distinct reporter may set REMOVED', async () => {
    await assertSucceeds(reportRequest(as('eve'), 'eve'));
    await assertSucceeds(reportRequest(as('bob'), 'bob'));
    await assertSucceeds(reportRequest(as('carol'), 'carol', 'r1', { status: 'REMOVED' }));
    const r = await getDoc(doc(as('alice'), 'requests/r1'));
    if (r.data().status !== 'REMOVED' || r.data().reportCount !== 3) throw new Error('not removed');
  });

  test('a reporter can only set REMOVED, nothing else', async () => {
    await seed(async (db) => updateDoc(doc(db, 'requests/r1'), { reportCount: 2 }));
    await assertFails(reportRequest(as('eve'), 'eve', 'r1', { status: 'CANCELLED' }));
  });

  test('a USER report cannot bump a request\'s counter', async () => {
    const db = as('eve');
    const b = writeBatch(db);
    b.set(doc(db, 'reports/eve_r1'), reportDoc('eve', 'r1', { targetType: 'USER' }));
    b.update(doc(db, 'requests/r1'), { reportCount: increment(1), updatedAt: serverTimestamp() });
    await assertFails(b.commit());
  });
});

describe('hospitals', () => {
  test('signed-in users read; only admins write', async () => {
    await assertSucceeds(getDoc(doc(as('eve'), 'hospitals/h1')));
    await assertFails(setDoc(doc(as('eve'), 'hospitals/h1'), { name: 'Fake Hospital' }));
    await assertSucceeds(setDoc(doc(as('mod', { admin: true }), 'hospitals/h1'), { name: 'Test Hospital' }));
  });
});
