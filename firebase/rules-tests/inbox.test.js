// users/{uid}/notifications/{nid}: the whitelisted cross-user inbox (Spark test 5).
import { describe, test, beforeAll, afterAll, beforeEach, expect } from 'vitest';
import { assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import {
  doc, getDoc, setDoc, updateDoc, deleteDoc, writeBatch, serverTimestamp, increment,
} from 'firebase/firestore';
import {
  setupEnv, teardownEnv, getEnv, as, seed, seedUsers, cast, ts,
  seedRequest, seedResponse, offer, decide, responseDoc,
} from './helpers.js';

let users;
beforeAll(setupEnv);
afterAll(teardownEnv);
beforeEach(async () => {
  await getEnv().clearFirestore();
  users = cast();
  await seedUsers(users);
  await seedRequest('r1', 'alice');
});

function item(type, fromUid, requestId = 'r1', o = {}) {
  return { type, fromUid, requestId, createdAt: serverTimestamp(), ...o };
}

/**
 * Ask a listed donor (contract 14): inbox item ask_{rid} + the request's askCount +1, with the
 * donor named in alice's own userStats (private), not on the public request.
 */
function ask(db, uid, rid = 'r1', { count = 1, named = uid, namedRid = rid } = {}) {
  const b = writeBatch(db);
  b.set(doc(db, 'users', uid, 'notifications', `ask_${rid}`), item('ASKED_TO_DONATE', 'alice', rid));
  b.update(doc(db, 'requests', rid), { askCount: increment(count), updatedAt: serverTimestamp() });
  b.update(doc(db, 'userStats', 'alice'), { lastAskedUid: named, lastAskRid: namedRid });
  return b.commit();
}

describe('NEW_OFFER (donor -> requester)', () => {
  test('sent in the offer batch, it succeeds', async () => {
    const db = as('bob');
    const r = responseDoc('bob', 'r1', 'alice', users.bob);
    const b = writeBatch(db);
    b.set(doc(db, 'requests/r1/responses/bob'), r);
    b.update(doc(db, 'requests/r1'), {
      'responders.bob': { name: r.donorDisplayName, group: r.donorBloodGroup, status: 'OFFERED' },
      responseCount: increment(1), lastResponderUid: 'bob', updatedAt: serverTimestamp(),
    });
    b.set(doc(db, 'users/alice/notifications/NEW_OFFER_r1_bob'), item('NEW_OFFER', 'bob'));
    await assertSucceeds(b.commit());
  });

  test('fails without an OFFERED response', async () => {
    await assertFails(setDoc(doc(as('bob'), 'users/alice/notifications/NEW_OFFER_r1_bob'), item('NEW_OFFER', 'bob')));
  });

  test('fails to someone who is not the requester', async () => {
    await offer(as('bob'), 'bob', 'r1', 'alice', users.bob);
    await assertFails(setDoc(doc(as('bob'), 'users/eve/notifications/NEW_OFFER_r1_bob'), item('NEW_OFFER', 'bob')));
  });

  test('fails with a free-form ID or a forged sender', async () => {
    await offer(as('bob'), 'bob', 'r1', 'alice', users.bob);
    await assertFails(setDoc(doc(as('bob'), 'users/alice/notifications/random'), item('NEW_OFFER', 'bob')));
    await assertFails(setDoc(doc(as('bob'), 'users/alice/notifications/NEW_OFFER_r1_bob'), item('NEW_OFFER', 'carol')));
  });

  test('carries no free text', async () => {
    await offer(as('bob'), 'bob', 'r1', 'alice', users.bob);
    await assertFails(setDoc(doc(as('bob'), 'users/alice/notifications/NEW_OFFER_r1_bob'),
      item('NEW_OFFER', 'bob', 'r1', { body: 'Send bKash to 017...' })));
  });
});

describe('requester -> donor notes', () => {
  beforeEach(() => seedResponse('r1', 'bob', 'alice', users.bob, 'OFFERED'));

  test('OFFER_CONFIRMED in the confirm batch succeeds', async () => {
    const db = as('alice');
    const b = writeBatch(db);
    b.update(doc(db, 'requests/r1/responses/bob'), { status: 'CONFIRMED', updatedAt: serverTimestamp() });
    b.update(doc(db, 'requests/r1'), { 'responders.bob.status': 'CONFIRMED', lastResponderUid: 'bob', updatedAt: serverTimestamp() });
    b.set(doc(db, 'users/bob/notifications/OFFER_CONFIRMED_r1'), item('OFFER_CONFIRMED', 'alice'));
    await assertSucceeds(b.commit());
  });

  test('fails in the wrong state (OFFER_CONFIRMED while still OFFERED)', async () => {
    await assertFails(setDoc(doc(as('alice'), 'users/bob/notifications/OFFER_CONFIRMED_r1'), item('OFFER_CONFIRMED', 'alice')));
  });

  test('DONATION_RECORDED needs a DONATED response', async () => {
    await decide(as('alice'), 'r1', 'bob', 'CONFIRMED');
    await assertFails(setDoc(doc(as('alice'), 'users/bob/notifications/DONATION_RECORDED_r1'), item('DONATION_RECORDED', 'alice')));
    await decide(as('alice'), 'r1', 'bob', 'DONATED');
    await assertSucceeds(setDoc(doc(as('alice'), 'users/bob/notifications/DONATION_RECORDED_r1'), item('DONATION_RECORDED', 'alice')));
  });

  test('a stranger cannot send them', async () => {
    await decide(as('alice'), 'r1', 'bob', 'DECLINED');
    await assertFails(setDoc(doc(as('eve'), 'users/bob/notifications/OFFER_DECLINED_r1'), item('OFFER_DECLINED', 'eve')));
    await assertSucceeds(setDoc(doc(as('alice'), 'users/bob/notifications/OFFER_DECLINED_r1'), item('OFFER_DECLINED', 'alice')));
  });
});

describe('unlisted types and abuse', () => {
  test('an unlisted type fails', async () => {
    await assertFails(setDoc(doc(as('alice'), 'users/bob/notifications/PROMO_r1'), item('PROMO', 'alice')));
    await assertFails(setDoc(doc(as('alice'), 'users/bob/notifications/x'), item('ANNOUNCEMENT', 'alice')));
  });

  test('nobody can write into their own inbox, or edit an item', async () => {
    await assertFails(setDoc(doc(as('bob'), 'users/bob/notifications/OFFER_CONFIRMED_r1'), item('OFFER_CONFIRMED', 'bob')));
    await seed(async (db) => setDoc(doc(db, 'users/bob/notifications/n1'), { type: 'NEW_OFFER', fromUid: 'x', requestId: 'r1', createdAt: ts(0) }));
    await assertFails(updateDoc(doc(as('bob'), 'users/bob/notifications/n1'), { read: true }));
  });

  test('only the owner can read or delete their inbox', async () => {
    await seed(async (db) => setDoc(doc(db, 'users/bob/notifications/n1'), { type: 'NEW_OFFER', fromUid: 'x', requestId: 'r1', createdAt: ts(0) }));
    await assertSucceeds(getDoc(doc(as('bob'), 'users/bob/notifications/n1')));
    await assertFails(getDoc(doc(as('alice'), 'users/bob/notifications/n1')));
    await assertFails(deleteDoc(doc(as('alice'), 'users/bob/notifications/n1')));
    await assertSucceeds(deleteDoc(doc(as('bob'), 'users/bob/notifications/n1')));
  });
});

describe('ASKED_TO_DONATE', () => {
  test('the requester can ask a listed, compatible donor with askCount +1', async () => {
    await assertSucceeds(ask(as('alice'), 'bob'));
  });

  test('fails without the askCount +1, or with the +1 named for another donor or request', async () => {
    await assertFails(setDoc(doc(as('alice'), 'users/bob/notifications/ask_r1'), item('ASKED_TO_DONATE', 'alice')));
    await assertFails(ask(as('alice'), 'bob', 'r1', { count: 0 }));
    await assertFails(ask(as('alice'), 'bob', 'r1', { named: 'carol' }));
    await assertFails(ask(as('alice'), 'bob', 'r1', { namedRid: 'r2' }));
  });

  test('two asks cannot share one +1 (only one donor can be named)', async () => {
    await seedUsers({ dan: { ...users.bob, fullName: 'Dan D.' } });
    const db = as('alice');
    const b = writeBatch(db);
    b.set(doc(db, 'users/bob/notifications/ask_r1'), item('ASKED_TO_DONATE', 'alice'));
    b.set(doc(db, 'users/dan/notifications/ask_r1'), item('ASKED_TO_DONATE', 'alice'));
    b.update(doc(db, 'requests/r1'), { askCount: increment(1), updatedAt: serverTimestamp() });
    b.update(doc(db, 'userStats/alice'), { lastAskedUid: 'bob', lastAskRid: 'r1' });
    await assertFails(b.commit());
    await assertSucceeds(ask(as('alice'), 'bob'));
    await assertSucceeds(ask(as('alice'), 'dan')); // the next ask, its own batch: askCount 2
  });

  test('nobody else can see whom the requester asked; the request carries no donor uid', async () => {
    await assertSucceeds(ask(as('alice'), 'bob'));
    const rq = (await getDoc(doc(as('eve'), 'requests/r1'))).data();
    expect(rq.askCount).toBe(1);
    expect(rq.lastAskedUid).toBeUndefined();
    await assertFails(getDoc(doc(as('eve'), 'userStats/alice')));
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { lastAskedUid: 'bob', updatedAt: serverTimestamp() }));
    // userStats names an ask only with that new inbox item in the same batch
    await assertFails(updateDoc(doc(as('alice'), 'userStats/alice'), { lastAskedUid: 'carol', lastAskRid: 'r1' }));
  });

  test('fails for an incompatible or unlisted user', async () => {
    await assertFails(ask(as('alice'), 'carol')); // O- is not in [B_POS]
    await assertFails(ask(as('alice'), 'eve'));   // not listed: no donor card
  });

  test('fails if the donor blocked the requester', async () => {
    await seed(async (db) => updateDoc(doc(db, 'users/bob'), { blockedUids: ['alice'] }));
    await assertFails(ask(as('alice'), 'bob'));
  });

  test('fails on a closed request, or after 10 asks', async () => {
    await seed(async (db) => updateDoc(doc(db, 'requests/r1'), { askCount: 10 }));
    await assertFails(ask(as('alice'), 'bob'));
    await seedRequest('r2', 'alice', { status: 'CANCELLED' });
    await assertFails(ask(as('alice'), 'bob', 'r2'));
  });

  test('only the requester can ask', async () => {
    const db = as('eve');
    const b = writeBatch(db);
    b.set(doc(db, 'users/bob/notifications/ask_r1'), item('ASKED_TO_DONATE', 'eve'));
    b.update(doc(db, 'requests/r1'), { askCount: increment(1), updatedAt: serverTimestamp() });
    b.update(doc(db, 'userStats/eve'), { lastAskedUid: 'bob', lastAskRid: 'r1' });
    await assertFails(b.commit());
  });
});
