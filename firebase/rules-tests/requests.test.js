// requests/{rid}, private/contact, userStats counters.
// Doc 07 §5 tests 1 (contact), 2, 4, 6, 8; Spark tests 2, 3, 7, 9.
import { describe, test, beforeAll, afterAll, beforeEach, expect } from 'vitest';
import { assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import {
  doc, getDoc, getDocs, setDoc, updateDoc, deleteDoc, writeBatch, serverTimestamp, increment,
  collection, query, where, orderBy, limit, startAfter, Timestamp,
} from 'firebase/firestore';
import {
  setupEnv, teardownEnv, getEnv, as, anon, seed, seedUsers, cast, ts, DAY,
  newRequestDoc, createRequest, seedRequest, seedResponse, contactDoc,
} from './helpers.js';

let users;
beforeAll(setupEnv);
afterAll(teardownEnv);
beforeEach(async () => {
  await getEnv().clearFirestore();
  users = cast();
  await seedUsers(users);
});

/** The requester closes a request: status + userStats -1 naming it. */
function closeRequest(db, uid, rid, status = 'CANCELLED', { stats = true } = {}) {
  const b = writeBatch(db);
  b.update(doc(db, 'requests', rid), { status, closedReason: 'Found a donor elsewhere', updatedAt: serverTimestamp() });
  if (stats) b.update(doc(db, 'userStats', uid), { openRequests: increment(-1), lastRequestId: rid });
  return b.commit();
}

describe('requests: read (doc 07 test 2)', () => {
  beforeEach(() => seedRequest('r1', 'alice'));

  test('a verified user can read requests and query the feed', async () => {
    await assertSucceeds(getDoc(doc(as('eve'), 'requests/r1')));
    const feed = query(collection(as('eve'), 'requests'),
      where('status', '==', 'OPEN'), where('district', '==', 'BD3026'),
      where('acceptedGroups', 'array-contains', 'B_POS'));
    await assertSucceeds(getDocs(feed));
  });

  test('the Nearby list (OPEN in my district, any group, by neededBy, paged) is allowed', async () => {
    const nearby = (db, after) => query(collection(db, 'requests'),
      where('status', '==', 'OPEN'), where('district', '==', 'BD3026'),
      orderBy('neededBy'), ...(after ? [startAfter(after)] : []), limit(20));
    await assertSucceeds(getDocs(nearby(as('eve'))));
    await assertSucceeds(getDocs(nearby(as('eve'), ts(DAY))));
    await assertFails(getDocs(nearby(as('eve', { email_verified: false }))));
  });

  test('the delta-sync feed (no status filter) is allowed', async () => {
    const delta = query(collection(as('eve'), 'requests'),
      where('district', '==', 'BD3026'), where('updatedAt', '>', ts(-DAY)));
    await assertSucceeds(getDocs(delta));
  });

  test('a signed-in user with an unverified email cannot read requests', async () => {
    await assertFails(getDoc(doc(as('eve', { email_verified: false }), 'requests/r1')));
    await assertFails(getDocs(collection(as('eve', { email_verified: false }), 'requests')));
  });

  test('signed-out users cannot read requests', async () => {
    await assertFails(getDoc(doc(anon(), 'requests/r1')));
  });

  test('requests are never deleted', async () => {
    await assertFails(deleteDoc(doc(as('alice'), 'requests/r1')));
  });
});

describe('requests: create (doc 07 test 4)', () => {
  test('a valid request batch succeeds without any phone verification', async () => {
    await assertSucceeds(createRequest(as('alice'), 'alice', 'r1'));
  });

  test('neededBy in the past is rejected', async () => {
    const neededBy = ts(-60 * 60 * 1000);
    await assertFails(createRequest(as('alice'), 'alice', 'r1', {
      neededBy, expiresAt: Timestamp.fromMillis(neededBy.toMillis() + DAY),
    }));
  });

  test('neededBy more than 30 days ahead is rejected', async () => {
    const neededBy = ts(31 * DAY);
    await assertFails(createRequest(as('alice'), 'alice', 'r1', {
      neededBy, expiresAt: Timestamp.fromMillis(neededBy.toMillis() + DAY),
    }));
  });

  test('expiresAt must be neededBy + 24 h', async () => {
    const r = newRequestDoc('alice');
    await assertFails(createRequest(as('alice'), 'alice', 'r1', {
      expiresAt: Timestamp.fromMillis(r.neededBy.toMillis() + 7 * DAY),
    }));
  });

  test('more than 10 units is rejected', async () => {
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { unitsNeeded: 11 }));
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { unitsNeeded: 0 }));
  });

  test('a requesterUid that belongs to someone else is rejected', async () => {
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { requesterUid: 'bob' }));
  });

  test('counters and status must start at zero / OPEN', async () => {
    for (const o of [{ unitsFulfilled: 1 }, { responseCount: 3 }, { reportCount: 1 }, { askCount: 2 },
      { status: 'FULFILLED' }, { responders: { bob: { name: 'B', group: 'B_POS', status: 'OFFERED' } } }]) {
      await assertFails(createRequest(as('alice'), 'alice', 'r1', o));
    }
  });

  test('acceptedGroups: exact match unless compatible blood is accepted', async () => {
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { acceptedGroups: ['B_POS', 'O_NEG'] }));
    await assertSucceeds(createRequest(as('alice'), 'alice', 'r1', {
      acceptCompatible: true, acceptedGroups: ['B_POS', 'B_NEG', 'O_POS', 'O_NEG'],
    }));
  });

  test('acceptedGroups can never include an incompatible group', async () => {
    await assertFails(createRequest(as('alice'), 'alice', 'r1', {
      acceptCompatible: true, acceptedGroups: ['B_POS', 'A_POS'],
    }));
  });

  test('unknown fields and bad codes are rejected', async () => {
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { patientPhone: '+8801700000001' }));
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { district: 'Dhaka North' }));
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { urgency: 'NOW' }));
  });

  test('an unverified or signed-out user cannot create a request', async () => {
    await assertFails(createRequest(as('alice', { email_verified: false }), 'alice', 'r1'));
  });

  test('a banned user (custom claim) cannot create a request', async () => {
    await assertFails(createRequest(as('alice', { banned: true }), 'alice', 'r1'));
  });

  test('optional fields may be null', async () => {
    await assertSucceeds(createRequest(as('alice'), 'alice', 'r1', {
      hospitalId: null, hospitalAddress: null, upazila: null, condition: null, location: null, notes: null,
    }));
  });

  test('a location must be a {lat, lng, geohash} map', async () => {
    await assertSucceeds(createRequest(as('alice'), 'alice', 'r1', {
      location: { lat: 23.7393, lng: 90.3966, geohash: 'wh0r3' },
    }));
    await assertFails(createRequest(as('alice'), 'alice', 'r2', {
      location: { lat: 123, lng: 90.3966, geohash: 'wh0r3' },
    }, { stats: { lastRequestId: 'r2' } }));
  });
});

describe('requests: fan-out fields (Spark test 7)', () => {
  test('a client cannot create with fanoutTier != 0 or nextTierAt != now', async () => {
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { fanoutTier: 2 }));
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { nextTierAt: ts(DAY) }));
  });

  test('a client cannot write fanoutTier or nextTierAt after create', async () => {
    await seedRequest('r1', 'alice');
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { fanoutTier: 3, updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { nextTierAt: ts(DAY), updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(as('eve'), 'requests/r1'), { fanoutTier: 0, updatedAt: serverTimestamp() }));
  });
});

describe('requests: money words (Spark test 9)', () => {
  test('notes containing "bkash" must have flagged == true', async () => {
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { notes: 'Please send 500 via bKash', flagged: false }));
    await assertSucceeds(createRequest(as('alice'), 'alice', 'r1', { notes: 'Please send 500 via bKash', flagged: true }));
  });

  test('the check crosses line breaks and covers Bangla words', async () => {
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { notes: 'Ward 5\nNagad number below', flagged: false }));
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { notes: 'রোগীর জন্য টাকা লাগবে', flagged: false }));
    await assertSucceeds(createRequest(as('alice'), 'alice', 'r1', { notes: 'রোগীর জন্য বিকাশ', flagged: true }));
  });

  test('clean notes must not be flagged', async () => {
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { notes: 'Ward 5, bed 12', flagged: true }));
  });

  test('editing notes recomputes the flag', async () => {
    await seedRequest('r1', 'alice');
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { notes: 'call for payment', updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(as('alice'), 'requests/r1'), {
      notes: 'call for payment', flagged: true, updatedAt: serverTimestamp(),
    }));
  });
});

// The app's Validators use Kotlin String.length (UTF-16 code units). These pin that the
// rules' size() counts the same way, not UTF-8 bytes (a Bangla letter is 3 bytes).
describe('requests: text limits count UTF-16 code units, like Kotlin String.length', () => {
  test('500 Bangla letters fit the 500 limit; 501 do not', async () => {
    await assertSucceeds(createRequest(as('alice'), 'alice', 'r1', { notes: 'ক'.repeat(500), flagged: false }));
    await getEnv().clearFirestore();
    await seedUsers(users);
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { notes: 'ক'.repeat(501), flagged: false }));
  });

  test('an emoji counts as 2: 250 fit, 251 do not', async () => {
    await assertSucceeds(createRequest(as('alice'), 'alice', 'r1', { notes: '🩸'.repeat(250), flagged: false }));
    await getEnv().clearFirestore();
    await seedUsers(users);
    await assertFails(createRequest(as('alice'), 'alice', 'r1', { notes: '🩸'.repeat(251), flagged: false }));
  });
});

describe('userStats: open-request counter (Spark test 2)', () => {
  test('a request without the userStats +1 is rejected', async () => {
    await assertFails(createRequest(as('alice'), 'alice', 'r1', {}, { stats: false }));
  });

  test('openRequests cannot move without a matching request create', async () => {
    await assertFails(updateDoc(doc(as('alice'), 'userStats/alice'), {
      openRequests: increment(1), totalRequests: increment(1), lastRequestId: 'nope',
    }));
  });

  test('openRequests cannot drop without closing one of my OPEN requests', async () => {
    await seedRequest('r1', 'alice');
    await assertFails(updateDoc(doc(as('alice'), 'userStats/alice'), { openRequests: increment(-1), lastRequestId: 'r1' }));
  });

  test('closing a request with the -1 succeeds; a second -1 for it fails', async () => {
    await seedRequest('r1', 'alice');
    await assertSucceeds(closeRequest(as('alice'), 'alice', 'r1'));
    await seed(async (db) => setDoc(doc(db, 'userStats/alice'), { openRequests: 1, totalRequests: 2, lastRequestId: 'r1' }));
    await assertFails(updateDoc(doc(as('alice'), 'userStats/alice'), { openRequests: increment(-1), lastRequestId: 'r1' }));
  });

  test('a -1 cannot use someone else\'s request', async () => {
    await seedRequest('r1', 'bob');
    await seed(async (db) => setDoc(doc(db, 'userStats/alice'), { openRequests: 1, totalRequests: 1, lastRequestId: 'x' }));
    const db = as('alice');
    await assertFails(updateDoc(doc(db, 'userStats/alice'), { openRequests: increment(-1), lastRequestId: 'r1' }));
  });

  test('one +1 cannot cover two new requests', async () => {
    const db = as('alice');
    const b = writeBatch(db);
    b.set(doc(db, 'requests/r1'), newRequestDoc('alice'));
    b.set(doc(db, 'requests/r2'), newRequestDoc('alice'));
    b.update(doc(db, 'userStats/alice'), { openRequests: increment(1), totalRequests: increment(1), lastRequestId: 'r1' });
    await assertFails(b.commit());
  });

  test('a +2 jump is rejected', async () => {
    await assertFails(createRequest(as('alice'), 'alice', 'r1', {}, { stats: { openRequests: increment(2) } }));
  });

  test('the in-emulator flow: create updates userStats', async () => {
    await assertSucceeds(createRequest(as('alice'), 'alice', 'r1'));
    const s = await getDoc(doc(as('alice'), 'userStats/alice'));
    if (s.data().openRequests !== 1 || s.data().lastRequestId !== 'r1') throw new Error('userStats not updated');
  });
});

describe('userStats: open-request limit by account age (Spark test 3)', () => {
  test('a new account (< 7 days) cannot hold 2 open requests', async () => {
    await assertSucceeds(createRequest(as('newbie'), 'newbie', 'n1'));
    await assertFails(createRequest(as('newbie'), 'newbie', 'n2'));
  });

  test('an account older than 7 days can hold 3 but not 4', async () => {
    const db = as('alice');
    await assertSucceeds(createRequest(db, 'alice', 'r1'));
    await assertSucceeds(createRequest(db, 'alice', 'r2'));
    await assertSucceeds(createRequest(db, 'alice', 'r3'));
    await assertFails(createRequest(db, 'alice', 'r4'));
  });

  test('closing one frees a slot', async () => {
    const db = as('alice');
    for (const r of ['r1', 'r2', 'r3']) await createRequest(db, 'alice', r);
    await assertSucceeds(closeRequest(db, 'alice', 'r2', 'FULFILLED'));
    await assertSucceeds(createRequest(db, 'alice', 'r4'));
  });

  test('userStats must exist first (created at profile setup)', async () => {
    await seed(async (db) => deleteDoc(doc(db, 'userStats/alice')));
    await assertFails(createRequest(as('alice'), 'alice', 'r1'));
  });
});

describe('requests: owner edits (doc 07 test 8)', () => {
  beforeEach(() => seedRequest('r1', 'alice'));

  test('the requester cannot change bloodGroup or requesterUid', async () => {
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { bloodGroup: 'O_NEG', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { requesterUid: 'bob', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { acceptedGroups: ['B_POS', 'O_NEG'], updatedAt: serverTimestamp() }));
  });

  test('the requester can edit notes, units and neededBy', async () => {
    const neededBy = Timestamp.fromMillis(Math.floor((Date.now() + 3 * DAY) / 1000) * 1000);
    await assertSucceeds(updateDoc(doc(as('alice'), 'requests/r1'), {
      notes: 'Now in ICU', unitsNeeded: 3, neededBy,
      expiresAt: Timestamp.fromMillis(neededBy.toMillis() + DAY), updatedAt: serverTimestamp(),
    }));
  });

  test('neededBy and expiresAt move together', async () => {
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { neededBy: ts(3 * DAY), updatedAt: serverTimestamp() }));
  });

  test('updatedAt must be the server time on every write', async () => {
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { notes: 'x' }));
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { notes: 'x', updatedAt: ts(-DAY) }));
  });

  test('the requester cannot set unitsFulfilled directly (doc 07 test 3)', async () => {
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { unitsFulfilled: 2, updatedAt: serverTimestamp() }));
  });

  test('status: OPEN -> CANCELLED|FULFILLED only; closed is final; EXPIRED is the Worker\'s', async () => {
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { status: 'EXPIRED', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { status: 'REMOVED', updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(as('alice'), 'requests/r1'), { status: 'CANCELLED', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { status: 'OPEN', updatedAt: serverTimestamp() }));
  });

  test('strangers cannot edit a request', async () => {
    await assertFails(updateDoc(doc(as('eve'), 'requests/r1'), { notes: 'x', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(as('eve'), 'requests/r1'), { status: 'CANCELLED', updatedAt: serverTimestamp() }));
  });

  test('an admin can hide and restore a request, nothing else', async () => {
    const mod = as('mod', { admin: true });
    await assertSucceeds(updateDoc(doc(mod, 'requests/r1'), { status: 'REMOVED', updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(mod, 'requests/r1'), { status: 'OPEN', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(mod, 'requests/r1'), { notes: 'x', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(mod, 'requests/r1'), { status: 'OPEN', updatedAt: serverTimestamp() })); // no-op
    await assertFails(updateDoc(doc(mod, 'requests/r1'), { status: 'FULFILLED', updatedAt: serverTimestamp() }));
  });

  test('askCount: +1 only, at most 10; no donor uid on the request', async () => {
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { askCount: 5, updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { askCount: 1, lastAskedUid: 'bob', updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(as('alice'), 'requests/r1'), { askCount: 1, updatedAt: serverTimestamp() }));
    await seed(async (db) => updateDoc(doc(db, 'requests/r1'), { askCount: 10 }));
    await assertFails(updateDoc(doc(as('alice'), 'requests/r1'), { askCount: 11, updatedAt: serverTimestamp() }));
  });
});

describe('admin cancel gives the slot back; a hidden request keeps it until then (B15)', () => {
  const mod = () => as('mod', { admin: true });
  /** The admin's cancel batch: request -> CANCELLED (+ the owner's userStats change). */
  function adminCancel(db, rid, owner, { stats = { openRequests: increment(-1), lastRequestId: rid } } = {}) {
    const b = writeBatch(db);
    b.update(doc(db, 'requests', rid), { status: 'CANCELLED', updatedAt: serverTimestamp() });
    if (stats) b.update(doc(db, 'userStats', owner), stats);
    return b.commit();
  }
  const openCount = async (uid) => (await getDoc(doc(as(uid), 'userStats', uid))).data().openRequests;

  test('an admin cancels an OPEN request and the owner gets the slot back in the same batch', async () => {
    await seedRequest('r1', 'alice');
    await assertSucceeds(adminCancel(mod(), 'r1', 'alice'));
    expect(await openCount('alice')).toBe(0);
  });

  test('a hidden (REMOVED) request: the owner cannot free its slot; an admin cancel does', async () => {
    await seedRequest('r1', 'alice', { status: 'REMOVED', reportCount: 3 });
    await assertFails(closeRequest(as('alice'), 'alice', 'r1'));
    await assertFails(updateDoc(doc(as('alice'), 'userStats/alice'), { openRequests: increment(-1), lastRequestId: 'r1' }));
    await assertSucceeds(adminCancel(mod(), 'r1', 'alice'));
    expect(await openCount('alice')).toBe(0);
  });

  test('an admin cancel must give back exactly one slot, for this request', async () => {
    await seedRequest('r2', 'alice', {}, 2);
    await seedRequest('r1', 'alice', {}, 2);
    await assertFails(adminCancel(mod(), 'r1', 'alice', { stats: false }));
    await assertFails(adminCancel(mod(), 'r1', 'alice', { stats: { openRequests: increment(-2), lastRequestId: 'r1' } }));
    await assertFails(adminCancel(mod(), 'r1', 'alice', { stats: { openRequests: increment(-1), lastRequestId: 'r2' } }));
    await assertFails(adminCancel(mod(), 'r1', 'alice', { stats: { openRequests: increment(-1), totalRequests: 0, lastRequestId: 'r1' } }));
    await assertSucceeds(adminCancel(mod(), 'r1', 'alice'));
    expect(await openCount('alice')).toBe(1);
  });

  test("an admin can't move userStats without a cancel, or for someone else's request", async () => {
    await seedRequest('r1', 'alice', {}, 1);
    await assertFails(updateDoc(doc(mod(), 'userStats/alice'), { openRequests: increment(-1), lastRequestId: 'r1' }));
    await seedRequest('r9', 'bob', {}, 1);
    const db = mod();
    const b = writeBatch(db);
    b.update(doc(db, 'requests/r9'), { status: 'CANCELLED', updatedAt: serverTimestamp() });
    b.update(doc(db, 'userStats/alice'), { openRequests: increment(-1), lastRequestId: 'r9' });
    await assertFails(b.commit());
    await assertFails(updateDoc(doc(as('eve'), 'userStats/alice'), { openRequests: increment(-1), lastRequestId: 'r1' }));
  });

  test('closed requests stay closed, for admins too', async () => {
    for (const status of ['CANCELLED', 'FULFILLED', 'EXPIRED']) {
      await seedRequest('r1', 'alice', { status }, 0);
      await assertFails(updateDoc(doc(mod(), 'requests/r1'), { status: 'OPEN', updatedAt: serverTimestamp() }));
      await assertFails(updateDoc(doc(mod(), 'requests/r1'), { status: 'REMOVED', updatedAt: serverTimestamp() }));
      await assertFails(adminCancel(mod(), 'r1', 'alice', { stats: false }));
    }
  });

  test('an owner whose counters are already gone (deleted account): the cancel alone is enough', async () => {
    await seedRequest('r1', 'alice', { status: 'REMOVED', reportCount: 3 });
    await seed(async (db) => deleteDoc(doc(db, 'userStats/alice')));
    await assertSucceeds(adminCancel(mod(), 'r1', 'alice', { stats: false }));
  });
});

describe('private/contact (doc 07 tests 1 and 6)', () => {
  beforeEach(() => seedRequest('r1', 'alice'));

  test('the requester can read the contact', async () => {
    await assertSucceeds(getDoc(doc(as('alice'), 'requests/r1/private/contact')));
  });

  test('a stranger cannot read the contact', async () => {
    await assertFails(getDoc(doc(as('eve'), 'requests/r1/private/contact')));
  });

  test('a donor can read the contact only after offering', async () => {
    await assertFails(getDoc(doc(as('bob'), 'requests/r1/private/contact')));
    await seedResponse('r1', 'bob', 'alice', users.bob, 'OFFERED');
    await assertSucceeds(getDoc(doc(as('bob'), 'requests/r1/private/contact')));
  });

  test('only the requester can write the contact, with a valid BD phone', async () => {
    await assertFails(setDoc(doc(as('eve'), 'requests/r1/private/contact'), contactDoc('eve')));
    await assertFails(setDoc(doc(as('alice'), 'requests/r1/private/contact'), contactDoc('alice', { contactPhone: '12345' })));
    await assertSucceeds(setDoc(doc(as('alice'), 'requests/r1/private/contact'), contactDoc('alice', { wardOrBed: 'Ward 5' })));
  });

  test('a stranger cannot attach a contact to someone else\'s request', async () => {
    await assertFails(setDoc(doc(as('eve'), 'requests/r1/private/contact'), contactDoc('alice')));
  });

  test('an admin can read the contact', async () => {
    await assertSucceeds(getDoc(doc(as('mod', { admin: true }), 'requests/r1/private/contact')));
  });
});
