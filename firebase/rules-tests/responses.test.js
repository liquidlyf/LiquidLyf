// requests/{rid}/responses/{donorUid}, responders map, unitsFulfilled, verified donations.
// Doc 07 §5 tests 5, 7, 10; Spark test 4.
import { describe, test, beforeAll, afterAll, beforeEach } from 'vitest';
import { assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import {
  doc, getDoc, getDocs, setDoc, updateDoc, deleteDoc, writeBatch, serverTimestamp, increment,
  collection, collectionGroup, query, where,
} from 'firebase/firestore';
import {
  setupEnv, teardownEnv, getEnv, as, seed, seedUsers, cast, userDoc, ts, DAY,
  seedRequest, seedResponse, responseDoc, offer, decide,
} from './helpers.js';

let users;
beforeAll(setupEnv);
afterAll(teardownEnv);
/** Take a listed user out of donor search (profile + card), rules off. */
function unlist(uid) {
  return seed(async (db) => {
    await updateDoc(doc(db, 'users', uid), { showInDonorSearch: false });
    await deleteDoc(doc(db, 'donors', uid));
  });
}

beforeEach(async () => {
  await getEnv().clearFirestore();
  users = cast();
  await seedUsers(users);
  await seedRequest('r1', 'alice'); // B+ whole blood, exact match, 2 units
});

describe('offers (doc 07 test 5)', () => {
  test('a compatible donor can offer on an OPEN request', async () => {
    await assertSucceeds(offer(as('bob'), 'bob', 'r1', 'alice', users.bob));
  });

  test('a donor cannot offer twice (doc ID = uid)', async () => {
    await offer(as('bob'), 'bob', 'r1', 'alice', users.bob);
    await assertFails(offer(as('bob'), 'bob', 'r1', 'alice', users.bob));
    await assertFails(setDoc(doc(as('bob'), 'requests/r1/responses/bob'), responseDoc('bob', 'r1', 'alice', users.bob)));
  });

  test('a donor cannot offer on their own request', async () => {
    await assertFails(offer(as('alice'), 'alice', 'r1', 'alice', users.alice));
  });

  test('a donor whose blood group is not in acceptedGroups cannot offer', async () => {
    await assertFails(offer(as('dave'), 'dave', 'r1', 'alice', users.dave));
  });

  test('the donor group on the offer must be the profile\'s group', async () => {
    await assertFails(offer(as('dave'), 'dave', 'r1', 'alice', { ...users.dave, bloodGroup: 'B_POS' }));
  });

  test('compatible donors can offer when the requester accepts compatible blood', async () => {
    await seedRequest('r2', 'alice', { acceptCompatible: true, acceptedGroups: ['B_POS', 'B_NEG', 'O_POS', 'O_NEG'] });
    await assertSucceeds(offer(as('carol'), 'carol', 'r2', 'alice', users.carol));
  });

  test('offers are rejected on closed or expired requests', async () => {
    await seedRequest('r2', 'alice', { status: 'FULFILLED' });
    await assertFails(offer(as('bob'), 'bob', 'r2', 'alice', users.bob));
    await seedRequest('r3', 'alice', { expiresAt: ts(-60_000), neededBy: ts(-DAY) });
    await assertFails(offer(as('bob'), 'bob', 'r3', 'alice', users.bob));
  });

  test('an offer must update responders and responseCount in the same batch', async () => {
    await assertFails(setDoc(doc(as('bob'), 'requests/r1/responses/bob'), responseDoc('bob', 'r1', 'alice', users.bob)));
  });

  test('the responders entry must copy the response (no fake names or groups)', async () => {
    await assertFails(offer(as('bob'), 'bob', 'r1', 'alice', users.bob, { entry: { name: 'Dr. Famous' } }));
    await assertFails(offer(as('bob'), 'bob', 'r1', 'alice', users.bob, { entry: { group: 'O_NEG' } }));
    await assertFails(offer(as('bob'), 'bob', 'r1', 'alice', users.bob, { entry: { status: 'DONATED' } }));
  });

  test('responseCount moves by exactly 1', async () => {
    await assertFails(offer(as('bob'), 'bob', 'r1', 'alice', users.bob, { request: { responseCount: increment(5) } }));
  });

  test('an offer cannot touch other request fields', async () => {
    await assertFails(offer(as('bob'), 'bob', 'r1', 'alice', users.bob, { request: { unitsFulfilled: 2 } }));
    await assertFails(offer(as('bob'), 'bob', 'r1', 'alice', users.bob, { request: { status: 'FULFILLED' } }));
  });

  test('the offer must carry a valid BD phone and the right requesterUid', async () => {
    await assertFails(offer(as('bob'), 'bob', 'r1', 'alice', { ...users.bob, phone: '017' }));
    await assertFails(offer(as('bob'), 'bob', 'r1', 'alice', users.bob, { response: { requesterUid: 'eve' } }));
  });

  test('an unverified user cannot offer', async () => {
    await assertFails(offer(as('bob', { email_verified: false }), 'bob', 'r1', 'alice', users.bob));
  });

  test('a banned user (custom claim) cannot offer (phase-07 7.9)', async () => {
    await assertFails(offer(as('bob', { banned: true }), 'bob', 'r1', 'alice', users.bob));
    await assertSucceeds(offer(as('bob', { banned: false }), 'bob', 'r1', 'alice', users.bob));
  });
});

describe('responses: read', () => {
  beforeEach(() => seedResponse('r1', 'bob', 'alice', users.bob, 'OFFERED'));

  test('the donor and the requester can read the response; a stranger cannot (doc 07 test 1)', async () => {
    await assertSucceeds(getDoc(doc(as('bob'), 'requests/r1/responses/bob')));
    await assertSucceeds(getDoc(doc(as('alice'), 'requests/r1/responses/bob')));
    await assertFails(getDoc(doc(as('eve'), 'requests/r1/responses/bob')));
  });

  test('the requester lists responses with a requesterUid filter', async () => {
    const q = query(collection(as('alice'), 'requests/r1/responses'), where('requesterUid', '==', 'alice'));
    await assertSucceeds(getDocs(q));
    await assertFails(getDocs(collection(as('eve'), 'requests/r1/responses')));
  });
});

describe('"my offers" collection-group query (doc 07 test 10)', () => {
  beforeEach(() => seedResponse('r1', 'bob', 'alice', users.bob, 'OFFERED'));

  test('works for the donor', async () => {
    const q = query(collectionGroup(as('bob'), 'responses'), where('donorUid', '==', 'bob'));
    await assertSucceeds(getDocs(q));
  });

  test('fails for anyone else', async () => {
    const q = query(collectionGroup(as('eve'), 'responses'), where('donorUid', '==', 'bob'));
    await assertFails(getDocs(q));
    await assertFails(getDocs(collectionGroup(as('eve'), 'responses')));
  });
});

describe('transitions (doc 07 test 7)', () => {
  beforeEach(() => seedResponse('r1', 'bob', 'alice', users.bob, 'OFFERED'));

  test('the requester can confirm, then mark donated (+1 unit)', async () => {
    await assertSucceeds(decide(as('alice'), 'r1', 'bob', 'CONFIRMED'));
    await assertSucceeds(decide(as('alice'), 'r1', 'bob', 'DONATED'));
    const r = await getDoc(doc(as('alice'), 'requests/r1'));
    if (r.data().unitsFulfilled !== 1 || r.data().responders.bob.status !== 'DONATED') throw new Error('not mirrored');
  });

  test('the requester can decline an offer', async () => {
    await assertSucceeds(decide(as('alice'), 'r1', 'bob', 'DECLINED'));
  });

  test('OFFERED cannot jump straight to DONATED', async () => {
    await assertFails(decide(as('alice'), 'r1', 'bob', 'DONATED'));
  });

  test('the donor cannot confirm or mark their own response donated', async () => {
    await assertFails(decide(as('bob'), 'r1', 'bob', 'CONFIRMED'));
    await seed(async (db) => updateDoc(doc(db, 'requests/r1/responses/bob'), { status: 'CONFIRMED' }));
    await assertFails(decide(as('bob'), 'r1', 'bob', 'DONATED'));
  });

  test('a stranger cannot change a response', async () => {
    await assertFails(decide(as('eve'), 'r1', 'bob', 'DECLINED'));
  });

  test('the donor can withdraw (OFFERED or CONFIRMED -> WITHDRAWN)', async () => {
    await assertSucceeds(decide(as('bob'), 'r1', 'bob', 'WITHDRAWN'));
  });

  test('the requester cannot withdraw for the donor', async () => {
    await assertFails(decide(as('alice'), 'r1', 'bob', 'WITHDRAWN'));
  });

  test('a withdrawn or declined response is final', async () => {
    await decide(as('bob'), 'r1', 'bob', 'WITHDRAWN');
    await assertFails(decide(as('alice'), 'r1', 'bob', 'CONFIRMED'));
    await assertFails(decide(as('bob'), 'r1', 'bob', 'OFFERED'));
  });

  test('a decision must mirror into responders in the same batch', async () => {
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1/responses/bob'), { status: 'CONFIRMED', updatedAt: serverTimestamp() }));
  });

  test('the responders entry must carry the new status', async () => {
    const db = as('alice');
    const b = writeBatch(db);
    b.update(doc(db, 'requests/r1/responses/bob'), { status: 'CONFIRMED', updatedAt: serverTimestamp() });
    b.update(doc(db, 'requests/r1'), { 'responders.bob.status': 'DONATED', lastResponderUid: 'bob', updatedAt: serverTimestamp() });
    await assertFails(b.commit());
  });

  test('the requester cannot edit a responders entry without a response change', async () => {
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), {
      'responders.bob.name': 'Someone Else', lastResponderUid: 'bob', updatedAt: serverTimestamp(),
    }));
  });

  test('response fields other than status cannot change', async () => {
    await assertFails(updateDoc(doc(as('bob'), 'requests/r1/responses/bob'), { donorPhone: '+8801900000000', updatedAt: serverTimestamp() }));
  });
});

describe('unitsFulfilled (doc 07 test 3)', () => {
  beforeEach(() => seedResponse('r1', 'bob', 'alice', users.bob, 'CONFIRMED'));

  test('DONATED must add exactly 1 unit', async () => {
    await assertFails(decide(as('alice'), 'r1', 'bob', 'DONATED', { units: 0 }));
    await assertFails(decide(as('alice'), 'r1', 'bob', 'DONATED', { units: 2 }));
  });

  test('a decline cannot add a unit', async () => {
    await assertFails(decide(as('alice'), 'r1', 'bob', 'DECLINED', { units: 1 }));
  });

  test('the last unit can close the request as FULFILLED, with the userStats -1', async () => {
    await seed(async (db) => updateDoc(doc(db, 'requests/r1'), { unitsNeeded: 1 }));
    const db = as('alice');
    const b = writeBatch(db);
    b.update(doc(db, 'requests/r1/responses/bob'), { status: 'DONATED', updatedAt: serverTimestamp() });
    b.update(doc(db, 'requests/r1'), {
      'responders.bob.status': 'DONATED', lastResponderUid: 'bob', unitsFulfilled: increment(1),
      status: 'FULFILLED', updatedAt: serverTimestamp(),
    });
    b.update(doc(db, 'userStats/alice'), { openRequests: increment(-1), lastRequestId: 'r1' });
    await assertSucceeds(b.commit());
  });

  test('a donation can still be recorded after the requester closed the request', async () => {
    await seed(async (db) => updateDoc(doc(db, 'requests/r1'), { status: 'FULFILLED' }));
    await assertSucceeds(decide(as('alice'), 'r1', 'bob', 'DONATED'));
  });
});

describe('verified donation (Spark test 4)', () => {
  function requestDonation(o = {}) {
    return {
      donatedAt: ts(-60_000), component: 'WHOLE_BLOOD', hospitalName: 'Test Medical College Hospital',
      requestId: 'r1', verified: true, source: 'REQUEST', createdAt: serverTimestamp(), ...o,
    };
  }
  /** The donor's batch: verified donation + counters +1 + lastDonationAt. */
  function recordVerified(db, uid, did = 'r1', o = {}) {
    const b = writeBatch(db);
    b.set(doc(db, 'users', uid, 'donations', did), requestDonation({ requestId: did, ...o }));
    b.update(doc(db, 'users', uid), {
      donationCount: increment(1), verifiedDonations: increment(1), lastDonationId: did,
      lastDonationAt: ts(-60_000), updatedAt: serverTimestamp(),
    });
    return b.commit();
  }

  test('a REQUEST-sourced verified donation fails until the response is DONATED', async () => {
    await seedResponse('r1', 'bob', 'alice', users.bob, 'CONFIRMED');
    await assertFails(setDoc(doc(as('bob'), 'users/bob/donations/r1'), requestDonation()));
  });

  test('after DONATED, the donor records it and the counters move by 1', async () => {
    await seedResponse('r1', 'carol', 'alice', userDoc('carol', { bloodGroup: 'B_POS' }), 'DONATED');
    await unlist('carol');
    await assertSucceeds(recordVerified(as('carol'), 'carol'));
  });

  test('a listed donor also updates the card (counts + eligibleFrom) in that batch', async () => {
    await seedResponse('r1', 'bob', 'alice', users.bob, 'DONATED');
    const db = as('bob');
    const last = ts(-60_000);
    const b = writeBatch(db);
    b.set(doc(db, 'users/bob/donations/r1'), requestDonation());
    b.update(doc(db, 'users/bob'), {
      donationCount: increment(1), verifiedDonations: increment(1), lastDonationId: 'r1',
      lastDonationAt: last, available: false, updatedAt: serverTimestamp(),
    });
    b.update(doc(db, 'donors/bob'), {
      donationCount: increment(1), verifiedDonations: increment(1), available: false,
      eligibleFrom: new Date(last.toMillis() + 120 * DAY),
    });
    await assertSucceeds(b.commit());
  });

  test('the doc ID must be the request ID, and only once', async () => {
    await seedResponse('r1', 'bob', 'alice', users.bob, 'DONATED');
    await unlist('bob');
    await assertFails(setDoc(doc(as('bob'), 'users/bob/donations/other'), requestDonation()));
    await assertSucceeds(recordVerified(as('bob'), 'bob'));
    await assertFails(setDoc(doc(as('bob'), 'users/bob/donations/r1'), requestDonation({ hospitalName: 'Changed' })));
  });

  test('someone else\'s DONATED response does not count', async () => {
    await seedResponse('r1', 'bob', 'alice', users.bob, 'DONATED');
    await assertFails(recordVerified(as('eve'), 'eve'));
  });

  test('verified donations cannot be edited or deleted while the account exists', async () => {
    await seedResponse('r1', 'bob', 'alice', users.bob, 'DONATED');
    await seed(async (db) => setDoc(doc(db, 'users/bob/donations/r1'), requestDonation({ createdAt: ts(-DAY) })));
    await assertFails(updateDoc(doc(as('bob'), 'users/bob/donations/r1'), { hospitalName: 'Other' }));
    const db = as('bob');
    const b = writeBatch(db);
    b.delete(doc(db, 'users/bob/donations/r1'));
    await assertFails(b.commit());
  });
});
