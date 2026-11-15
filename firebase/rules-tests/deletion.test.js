// In-app account deletion (doc 06 §4.7, write contract 16): the fixed anonymizing edits
// on requests and responses, and the whole deletion sequence the app runs.
import { describe, test, beforeAll, afterAll, beforeEach, expect } from 'vitest';
import { assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import {
  doc, getDoc, setDoc, updateDoc, deleteDoc, writeBatch, serverTimestamp, increment, deleteField,
} from 'firebase/firestore';
import {
  setupEnv, teardownEnv, getEnv, as, seed, seedUsers, cast, ts, DAY, userDoc,
  seedRequest, seedResponse,
} from './helpers.js';

const DELETED = 'Deleted user';
let users;
beforeAll(setupEnv);
afterAll(teardownEnv);
beforeEach(async () => {
  await getEnv().clearFirestore();
  users = cast();
  await seedUsers(users);
});

/** The requester's anonymize write (+ userStats -1 when it closes an OPEN request). */
function anonymizeRequest(db, uid, rid, { close = false, extra = {} } = {}) {
  const b = writeBatch(db);
  const req = {
    requesterDisplayName: DELETED, notes: deleteField(), closedReason: deleteField(), flagged: false,
    updatedAt: serverTimestamp(), ...extra,
  };
  if (close) req.status = 'CANCELLED';
  b.update(doc(db, 'requests', rid), req);
  if (close) b.update(doc(db, 'userStats', uid), { openRequests: increment(-1), lastRequestId: rid });
  return b.commit();
}

/** The donor's anonymize batch: their responders entry + their response doc. */
function anonymizeOffer(db, donorUid, rid, status, { entry = {}, response = {} } = {}) {
  const b = writeBatch(db);
  b.update(doc(db, 'requests', rid), {
    [`responders.${donorUid}`]: { name: DELETED, group: 'B_POS', status, ...entry },
    updatedAt: serverTimestamp(),
  });
  b.update(doc(db, 'requests', rid, 'responses', donorUid), {
    donorDisplayName: DELETED, donorPhone: deleteField(), message: deleteField(),
    updatedAt: serverTimestamp(), ...response,
  });
  return b.commit();
}

describe('account deletion: the requester anonymizes their requests', () => {
  test('a closed request: name -> "Deleted user", notes and closedReason removed', async () => {
    await seedRequest('r1', 'alice', { status: 'FULFILLED', closedReason: 'Thanks to Bob, call 01700000099' }, 0);
    await assertSucceeds(anonymizeRequest(as('alice'), 'alice', 'r1'));
    const after = (await getDoc(doc(as('bob'), 'requests/r1'))).data();
    expect(after.requesterDisplayName).toBe(DELETED);
    expect(after.notes).toBeUndefined();
    expect(after.closedReason).toBeUndefined();
    expect(after.status).toBe('FULFILLED');
  });

  test('an OPEN request is cancelled in the same write, with userStats -1', async () => {
    await seedRequest('r1', 'alice', { notes: 'Send via bKash', flagged: true });
    await assertSucceeds(anonymizeRequest(as('alice'), 'alice', 'r1', { close: true }));
    const stats = (await getDoc(doc(as('alice'), 'userStats/alice'))).data();
    expect(stats.openRequests).toBe(0);
  });

  test('an OPEN request cannot stay open, and userStats cannot skip the -1', async () => {
    await seedRequest('r1', 'alice');
    await assertFails(anonymizeRequest(as('alice'), 'alice', 'r1'));
    await assertFails(anonymizeRequest(as('alice'), 'alice', 'r1', { extra: { status: 'FULFILLED' } }));
  });

  test('only the exact anonymizing edit is allowed', async () => {
    await seedRequest('r1', 'alice', { status: 'CANCELLED' }, 0);
    await assertFails(anonymizeRequest(as('alice'), 'alice', 'r1', { extra: { requesterDisplayName: 'Gone' } }));
    await assertFails(anonymizeRequest(as('alice'), 'alice', 'r1', { extra: { notes: 'still here' } }));
    await assertFails(anonymizeRequest(as('alice'), 'alice', 'r1', { extra: { flagged: true } }));
    await assertFails(anonymizeRequest(as('alice'), 'alice', 'r1', { extra: { hospitalName: 'Elsewhere' } }));
    await assertFails(anonymizeRequest(as('alice'), 'alice', 'r1', { extra: { status: 'OPEN' } }));
  });

  test('nobody else can anonymize a request', async () => {
    await seedRequest('r1', 'alice', { status: 'CANCELLED' }, 0);
    await assertFails(anonymizeRequest(as('eve'), 'eve', 'r1'));
    await assertFails(anonymizeRequest(as('mod', { admin: true }), 'mod', 'r1'));
  });

  test('a suspended or unverified owner can still anonymize', async () => {
    await seedRequest('r1', 'alice', { status: 'CANCELLED' }, 0);
    await assertSucceeds(anonymizeRequest(as('alice', { banned: true, email_verified: false }), 'alice', 'r1'));
  });

  test('a hidden (REMOVED) request is cancelled like an OPEN one, with userStats -1 (B15)', async () => {
    await seedRequest('r1', 'alice', { status: 'REMOVED', reportCount: 3 });
    const banned = as('alice', { banned: true });
    await assertFails(anonymizeRequest(banned, 'alice', 'r1')); // can't stay hidden
    await assertFails(anonymizeRequest(banned, 'alice', 'r1', { extra: { status: 'REMOVED' } }));
    await assertSucceeds(anonymizeRequest(banned, 'alice', 'r1', { close: true }));
    expect((await getDoc(doc(as('bob'), 'requests/r1'))).data().status).toBe('CANCELLED');
    expect((await getDoc(doc(banned, 'userStats/alice'))).data().openRequests).toBe(0);
    await assertSucceeds(deleteDoc(doc(banned, 'userStats/alice'))); // so the final batch can go
  });
});

describe('account deletion: the donor anonymizes their offers', () => {
  test('entry + response in one batch: name, phone and message go, status stays', async () => {
    await seedRequest('r1', 'alice');
    await seedResponse('r1', 'bob', 'alice', users.bob, 'CONFIRMED');
    await seed((db) => updateDoc(doc(db, 'requests/r1/responses/bob'), { message: 'Call me any time' }));
    await assertSucceeds(anonymizeOffer(as('bob'), 'bob', 'r1', 'CONFIRMED'));
    const resp = (await getDoc(doc(as('alice'), 'requests/r1/responses/bob'))).data();
    expect(resp).toMatchObject({ donorDisplayName: DELETED, status: 'CONFIRMED' });
    expect(resp.donorPhone).toBeUndefined();
    expect(resp.message).toBeUndefined();
    const rq = (await getDoc(doc(as('alice'), 'requests/r1'))).data();
    expect(rq.responders.bob).toEqual({ name: DELETED, group: 'B_POS', status: 'CONFIRMED' });
  });

  test('works on a closed request and a DONATED offer', async () => {
    await seedRequest('r1', 'alice');
    await seedResponse('r1', 'bob', 'alice', users.bob, 'DONATED');
    await seed((db) => updateDoc(doc(db, 'requests/r1'), { status: 'FULFILLED' }));
    await assertSucceeds(anonymizeOffer(as('bob'), 'bob', 'r1', 'DONATED'));
  });

  test('the status, group and phone cannot be changed or kept', async () => {
    await seedRequest('r1', 'alice');
    await seedResponse('r1', 'bob', 'alice', users.bob, 'OFFERED');
    await assertFails(anonymizeOffer(as('bob'), 'bob', 'r1', 'DONATED'));
    await assertFails(anonymizeOffer(as('bob'), 'bob', 'r1', 'OFFERED', { entry: { group: 'O_NEG' } }));
    await assertFails(anonymizeOffer(as('bob'), 'bob', 'r1', 'OFFERED', { response: { donorPhone: '+8801700000000' } }));
    await assertFails(anonymizeOffer(as('bob'), 'bob', 'r1', 'OFFERED', { response: { status: 'WITHDRAWN' } }));
    await assertFails(anonymizeOffer(as('bob'), 'bob', 'r1', 'OFFERED', { response: { donorDisplayName: 'Bobby' } }));
  });

  test("only the donor: not the requester, not another responder's entry", async () => {
    await seedRequest('r1', 'alice');
    await seedResponse('r1', 'bob', 'alice', users.bob, 'OFFERED');
    await seedResponse('r1', 'carol', 'alice', users.carol, 'OFFERED');
    await assertFails(anonymizeOffer(as('alice'), 'bob', 'r1', 'OFFERED'));
    await assertFails(anonymizeOffer(as('carol'), 'bob', 'r1', 'OFFERED'));
    await assertFails(updateDoc(doc(as('carol'), 'requests/r1'), {
      'responders.carol': { name: DELETED, group: 'O_NEG', status: 'OFFERED' },
      'responders.bob': { name: DELETED, group: 'B_POS', status: 'OFFERED' },
      updatedAt: serverTimestamp(),
    }));
  });
});

describe('account deletion: the whole sequence', () => {
  test('a listed requester with open and closed requests, inbox items and donations', async () => {
    const alice = userDoc('alice', {
      showInDonorSearch: true, consentAt: ts(-30 * DAY),
      donationCount: 2, verifiedDonations: 1, lastDonationAt: ts(-200 * DAY), lastDonationId: 'old-rq',
    });
    await seedUsers({ alice });
    await seedRequest('r2', 'alice', { status: 'CANCELLED', closedReason: 'Found a donor' }, 0);
    await seedRequest('r1', 'alice', {}, 1);
    await seed(async (db) => {
      await setDoc(doc(db, 'users/alice/notifications/NEW_OFFER_r1_bob'),
        { type: 'NEW_OFFER', fromUid: 'bob', requestId: 'r1', createdAt: ts(-60_000) });
      await setDoc(doc(db, 'users/alice/donations/self1'),
        { donatedAt: ts(-300 * DAY), component: 'WHOLE_BLOOD', verified: false, source: 'SELF', createdAt: ts(-300 * DAY) });
      await setDoc(doc(db, 'users/alice/donations/old-rq'),
        { donatedAt: ts(-200 * DAY), component: 'WHOLE_BLOOD', verified: true, source: 'REQUEST', requestId: 'old-rq', createdAt: ts(-200 * DAY) });
    });
    const db = as('alice');

    // 1. Close + anonymize the open request (one per batch: userStats -1 names it).
    await assertSucceeds(anonymizeRequest(db, 'alice', 'r1', { close: true }));
    // 2. Anonymize the closed ones (many per batch are fine).
    await assertSucceeds(anonymizeRequest(db, 'alice', 'r2'));
    // 3. Contacts and inbox.
    const b3 = writeBatch(db);
    b3.delete(doc(db, 'requests/r1/private/contact'));
    b3.delete(doc(db, 'requests/r2/private/contact'));
    b3.delete(doc(db, 'users/alice/notifications/NEW_OFFER_r1_bob'));
    await assertSucceeds(b3.commit());
    // 4. Final batch: donor card + userStats + donations + users.
    const b4 = writeBatch(db);
    b4.delete(doc(db, 'donors/alice'));
    b4.delete(doc(db, 'userStats/alice'));
    b4.delete(doc(db, 'users/alice/donations/self1'));
    b4.delete(doc(db, 'users/alice/donations/old-rq'));
    b4.delete(doc(db, 'users/alice'));
    await assertSucceeds(b4.commit());

    const left = await getDoc(doc(as('bob'), 'requests/r1'));
    expect(left.data()).toMatchObject({ requesterDisplayName: DELETED, status: 'CANCELLED' });
    expect((await getDoc(doc(as('bob'), 'donors/alice'))).exists()).toBe(false);
  });

  test('a resumed deletion can re-run the contact + inbox batch; others still cannot delete a contact', async () => {
    await seedRequest('r1', 'alice', { status: 'CANCELLED' }, 0);
    await assertFails(deleteDoc(doc(as('eve'), 'requests/r1/private/contact')));
    const run = () => {
      const db = as('alice');
      const b = writeBatch(db);
      b.delete(doc(db, 'requests/r1/private/contact'));
      b.delete(doc(db, 'users/alice/notifications/NEW_OFFER_r1_bob'));
      return b.commit();
    };
    await assertSucceeds(run());
    await assertSucceeds(run()); // already gone: still allowed
  });

  test('userStats cannot be deleted while a request is still open', async () => {
    await seedRequest('r1', 'alice', {}, 1);
    await assertFails(deleteDoc(doc(as('alice'), 'userStats/alice')));
  });

  test('a donor: withdraw the active offer, anonymize offers, then the final batch', async () => {
    await seedRequest('r1', 'alice');
    await seedResponse('r1', 'bob', 'alice', users.bob, 'OFFERED');
    await seedRequest('r9', 'carol', {}, 1);
    await seedResponse('r9', 'bob', 'carol', users.bob, 'DONATED');
    const db = as('bob');

    // Withdraw the open offer (write contract 10).
    const w = writeBatch(db);
    w.update(doc(db, 'requests/r1/responses/bob'), { status: 'WITHDRAWN', updatedAt: serverTimestamp() });
    w.update(doc(db, 'requests/r1'), {
      'responders.bob.status': 'WITHDRAWN', lastResponderUid: 'bob', updatedAt: serverTimestamp(),
    });
    await assertSucceeds(w.commit());
    // Anonymize both offers.
    await assertSucceeds(anonymizeOffer(db, 'bob', 'r1', 'WITHDRAWN'));
    await assertSucceeds(anonymizeOffer(db, 'bob', 'r9', 'DONATED'));
    // Final batch (bob is listed, has no requests).
    const f = writeBatch(db);
    f.delete(doc(db, 'donors/bob'));
    f.delete(doc(db, 'userStats/bob'));
    f.delete(doc(db, 'users/bob'));
    await assertSucceeds(f.commit());
  });

  test('users cannot go before the donor card, and a verified donation not before users', async () => {
    const bob = { ...users.bob, donationCount: 1, verifiedDonations: 1, lastDonationAt: ts(-200 * DAY) };
    await seedUsers({ bob }); // listed: writes donors/bob too
    await seed((db) => setDoc(doc(db, 'users/bob/donations/r7'),
      { donatedAt: ts(-200 * DAY), component: 'WHOLE_BLOOD', verified: true, source: 'REQUEST', requestId: 'r7', createdAt: ts(-200 * DAY) }));
    await assertFails(deleteDoc(doc(as('bob'), 'users/bob')));
    await assertFails(deleteDoc(doc(as('bob'), 'users/bob/donations/r7')));
  });
});
