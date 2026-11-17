// users/{uid}, donors/{uid}, userStats/{uid} create, donations.
// Doc 07 §5 tests 1 (users part) and 3; Spark tests 1 and 8; verified-donation counters.
import { describe, test, beforeAll, afterAll, beforeEach, expect } from 'vitest';
import { assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import {
  doc, getDoc, setDoc, updateDoc, deleteDoc, writeBatch, serverTimestamp, increment, deleteField,
  Timestamp,
} from 'firebase/firestore';
import {
  setupEnv, teardownEnv, getEnv, as, anon, seed, seedUsers, cast, userDoc, newUserDoc, donorCard,
  ts, DAY, EPOCH, seedRequest, createRequest, offer,
} from './helpers.js';

let users;
beforeAll(setupEnv);
afterAll(teardownEnv);
beforeEach(async () => {
  await getEnv().clearFirestore();
  users = cast();
  await seedUsers(users);
});

/** The profile-setup batch: users + userStats (+ donor card when listed). */
function profileSetup(db, uid, u, card) {
  const b = writeBatch(db);
  b.set(doc(db, 'users', uid), u);
  b.set(doc(db, 'userStats', uid), { openRequests: 0, totalRequests: 0 });
  if (card) b.set(doc(db, 'donors', uid), card);
  return b.commit();
}

/** A users update (+ optional donor-card update) in one batch. */
function updateProfile(db, uid, userFields, cardFields) {
  const b = writeBatch(db);
  b.update(doc(db, 'users', uid), { ...userFields, updatedAt: serverTimestamp() });
  if (cardFields) b.update(doc(db, 'donors', uid), cardFields);
  return b.commit();
}

describe('users: read (doc 07 test 1)', () => {
  test('the owner can read their profile', async () => {
    await assertSucceeds(getDoc(doc(as('alice'), 'users/alice')));
  });
  test('a stranger cannot read someone else\'s profile', async () => {
    await assertFails(getDoc(doc(as('eve'), 'users/alice')));
  });
  test('signed-out users cannot read profiles', async () => {
    await assertFails(getDoc(doc(anon(), 'users/alice')));
  });
  test('an admin can read profiles', async () => {
    await assertSucceeds(getDoc(doc(as('mod', { admin: true }), 'users/alice')));
  });
});

describe('users: profile setup', () => {
  test('an unlisted user can create their profile and userStats', async () => {
    await assertSucceeds(profileSetup(as('zed'), 'zed', newUserDoc('zed')));
  });

  test('a listed user must create the donor card in the same batch', async () => {
    const u = newUserDoc('zed', { showInDonorSearch: true, consentAt: serverTimestamp() });
    await assertFails(profileSetup(as('zed'), 'zed', u));
    const card = donorCard(userDoc('zed'), {});
    await assertSucceeds(profileSetup(as('zed'), 'zed', u, card));
  });

  test('listing needs a recorded consent (consentAt)', async () => {
    const u = newUserDoc('zed', { showInDonorSearch: true });
    await assertFails(profileSetup(as('zed'), 'zed', u, donorCard(userDoc('zed'))));
  });

  test('an unverified email cannot create a profile', async () => {
    await assertFails(profileSetup(as('zed', { email_verified: false }), 'zed', newUserDoc('zed')));
  });

  test('the email must be the signed-in account\'s email', async () => {
    await assertFails(profileSetup(as('zed'), 'zed', newUserDoc('zed', { email: 'other@example.test' })));
  });

  test('createdAt must be the server time', async () => {
    await assertFails(profileSetup(as('zed'), 'zed', newUserDoc('zed', { createdAt: ts(-30 * DAY) })));
  });

  test('a user cannot create a profile for someone else', async () => {
    await assertFails(setDoc(doc(as('eve'), 'users/zed'), newUserDoc('zed', { email: 'eve@example.test' })));
  });

  test('bad phone, blood group, age or extra fields are rejected', async () => {
    const db = as('zed');
    await assertFails(profileSetup(db, 'zed', newUserDoc('zed', { phone: '01700000011' })));
    await assertFails(profileSetup(db, 'zed', newUserDoc('zed', { bloodGroup: 'B+' })));
    await assertFails(profileSetup(db, 'zed', newUserDoc('zed', { birthYear: new Date().getFullYear() - 10 })));
    await assertFails(profileSetup(db, 'zed', newUserDoc('zed', { district: 'Dhaka/North' })));
    await assertFails(profileSetup(db, 'zed', newUserDoc('zed', { isAdmin: true })));
  });

  test('userStats can only start at zero', async () => {
    const db = as('zed');
    await assertFails(setDoc(doc(db, 'userStats/zed'), { openRequests: -3, totalRequests: 0 }));
    await assertSucceeds(setDoc(doc(db, 'userStats/zed'), { openRequests: 0, totalRequests: 0 }));
  });

  test('optional fields may be written as null (Kotlin DTOs)', async () => {
    const u = newUserDoc('zed', { lastDonationAt: null, inboxSeenAt: null, hiddenRequestIds: null, lastActiveAt: null });
    await assertSucceeds(profileSetup(as('zed'), 'zed', u));
  });
});

describe('users: protected fields (doc 07 test 3)', () => {
  test('a user cannot set their own phoneVerified', async () => {
    await assertFails(profileSetup(as('zed'), 'zed', newUserDoc('zed', { phoneVerified: true })));
    await assertFails(updateProfile(as('alice'), 'alice', { phoneVerified: true }));
  });

  test('phoneVerified can only drop to false together with a phone change', async () => {
    await seed(async (db) => updateDoc(doc(db, 'users/alice'), { phoneVerified: true }));
    await assertFails(updateProfile(as('alice'), 'alice', { phoneVerified: false }));
    await assertFails(updateProfile(as('alice'), 'alice', { phone: '+8801800000001' }));
    await assertSucceeds(updateProfile(as('alice'), 'alice', { phone: '+8801800000001', phoneVerified: false }));
  });

  test('a user cannot set their own role', async () => {
    await assertFails(profileSetup(as('zed'), 'zed', newUserDoc('zed', { role: 'admin' })));
    await assertFails(updateProfile(as('alice'), 'alice', { role: 'admin' }));
  });

  test('a user cannot set their own donationCount or verifiedDonations', async () => {
    await assertFails(profileSetup(as('zed'), 'zed', newUserDoc('zed', { donationCount: 5 })));
    await assertFails(updateProfile(as('alice'), 'alice', { donationCount: 5 }));
    await assertFails(updateProfile(as('alice'), 'alice', { donationCount: 1, verifiedDonations: 1, lastDonationId: 'x' }));
  });

  test('createdAt and email cannot change', async () => {
    await assertFails(updateProfile(as('alice'), 'alice', { createdAt: ts(-400 * DAY) }));
    await assertFails(updateProfile(as('alice'), 'alice', { email: 'new@example.test' }));
  });

  test('a normal profile edit works', async () => {
    await assertSucceeds(updateProfile(as('alice'), 'alice', { fullName: 'Alice Renamed', locale: 'bn' }));
  });

  test('rapidResponder is the Worker\'s: nobody sets, changes or removes it, and other edits keep working', async () => {
    await assertFails(profileSetup(as('zed'), 'zed', newUserDoc('zed', { rapidResponder: true })));
    await assertFails(updateProfile(as('alice'), 'alice', { rapidResponder: true }));
    await seed(async (db) => updateDoc(doc(db, 'users/alice'), { rapidResponder: true })); // as the Worker
    await assertSucceeds(updateProfile(as('alice'), 'alice', { fullName: 'Alice Renamed' }));
    await assertFails(updateProfile(as('alice'), 'alice', { rapidResponder: false }));
    await assertFails(updateProfile(as('alice'), 'alice', { rapidResponder: deleteField() }));
    expect((await getDoc(doc(as('alice'), 'users/alice'))).data().rapidResponder).toBe(true);
  });

  test('updatedAt must be the server time', async () => {
    await assertFails(updateDoc(doc(as('alice'), 'users/alice'), { fullName: 'X Y', updatedAt: ts(0) }));
  });
});

describe('users: private lists and timestamps (doc 07 §9.1)', () => {
  const ids = (n, p) => Array.from({ length: n }, (_, i) => `${p}${i}`);

  test('the owner hides requests, blocks people, marks the inbox seen and records the last post', async () => {
    await assertSucceeds(updateProfile(as('alice'), 'alice', {
      hiddenRequestIds: ['req1', 'req2'], blockedUids: ['mallory'], inboxSeenAt: serverTimestamp(), lastRequestAt: ts(-DAY),
    }));
    // A listed donor too: none of these fields is on the public card, so no card update is needed.
    await assertSucceeds(updateProfile(as('bob'), 'bob', { blockedUids: ['mallory'], inboxSeenAt: serverTimestamp() }));
  });

  test('each list holds at most 200 entries', async () => {
    await assertSucceeds(updateProfile(as('alice'), 'alice', { hiddenRequestIds: ids(200, 'r'), blockedUids: ids(200, 'u') }));
    await assertFails(updateProfile(as('alice'), 'alice', { hiddenRequestIds: ids(201, 'r') }));
    await assertFails(updateProfile(as('alice'), 'alice', { blockedUids: ids(201, 'u') }));
    await assertFails(profileSetup(as('zed'), 'zed', newUserDoc('zed', { blockedUids: ids(201, 'u') })));
  });

  test('lists must be lists and the times must be timestamps', async () => {
    await assertFails(updateProfile(as('alice'), 'alice', { blockedUids: 'mallory' }));
    await assertFails(updateProfile(as('alice'), 'alice', { hiddenRequestIds: { req1: true } }));
    await assertFails(updateProfile(as('alice'), 'alice', { inboxSeenAt: '2026-11-17' }));
    await assertFails(updateProfile(as('alice'), 'alice', { lastRequestAt: 1763337600000 }));
  });

  test('nobody else can change my lists', async () => {
    await assertFails(updateDoc(doc(as('mallory'), 'users/alice'), { blockedUids: [], updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(as('bob'), 'users/alice'), { hiddenRequestIds: [], updatedAt: serverTimestamp() }));
  });
});

describe('users: language and terms (phase-08)', () => {
  test('the language switches between English and Bangla only', async () => {
    await assertSucceeds(updateProfile(as('alice'), 'alice', { locale: 'bn' }));
    await assertSucceeds(updateProfile(as('alice'), 'alice', { locale: 'en' }));
    await assertFails(updateProfile(as('alice'), 'alice', { locale: 'fr' }));
    await assertFails(updateProfile(as('alice'), 'alice', { locale: 'BN' }));
  });

  test('accepting new terms records the version and the time', async () => {
    await assertSucceeds(updateProfile(as('alice'), 'alice', { termsVersion: '2027-01', termsAcceptedAt: serverTimestamp() }));
    await assertFails(updateProfile(as('alice'), 'alice', { termsVersion: '' }));
    await assertFails(updateProfile(as('alice'), 'alice', { termsVersion: 'x'.repeat(21) }));
    await assertFails(updateProfile(as('alice'), 'alice', { termsAcceptedAt: 'today' }));
    await assertFails(updateProfile(as('alice'), 'alice', { termsVersion: deleteField() }));
  });
});

describe('donors: owner-written mirror (Spark test 1)', () => {
  test('signed-in verified users can read donor cards; unverified cannot', async () => {
    await assertSucceeds(getDoc(doc(as('eve'), 'donors/bob')));
    await assertFails(getDoc(doc(as('eve', { email_verified: false }), 'donors/bob')));
  });

  test('a donor-card write alone fails unless it equals the users doc', async () => {
    await assertFails(updateDoc(doc(as('bob'), 'donors/bob'), { bloodGroup: 'AB_NEG' }));
    await assertFails(updateDoc(doc(as('bob'), 'donors/bob'), { donationCount: 50 }));
    await assertFails(updateDoc(doc(as('bob'), 'donors/bob'), { verifiedDonations: 9 }));
    await assertSucceeds(updateDoc(doc(as('bob'), 'donors/bob'), { displayName: 'Bobby T.' }));
  });

  test('users + donors in one batch with matching fields succeeds', async () => {
    await assertSucceeds(updateProfile(as('bob'), 'bob',
      { district: 'BD3033', upazila: 'BD30333300' }, { district: 'BD3033', upazila: 'BD30333300' }));
  });

  test('a mismatching card in the batch fails', async () => {
    await assertFails(updateProfile(as('bob'), 'bob', { district: 'BD3033', upazila: 'BD30333300' }, { district: 'BD3033' }));
    await assertFails(updateProfile(as('bob'), 'bob', { available: false }, { available: true }));
  });

  test('a listed user cannot change a mirrored field without updating the card', async () => {
    await assertFails(updateProfile(as('bob'), 'bob', { available: false }));
    await assertSucceeds(updateProfile(as('bob'), 'bob', { available: false }, { available: false }));
  });

  test('a stranger cannot write someone else\'s card', async () => {
    await assertFails(setDoc(doc(as('eve'), 'donors/bob'), donorCard(users.bob, { displayName: 'Hacked' })));
  });

  test('an unlisted user cannot create a donor card', async () => {
    await assertFails(setDoc(doc(as('alice'), 'donors/alice'), donorCard(users.alice)));
  });

  test('unlisting must delete the card in the same batch', async () => {
    await assertFails(updateProfile(as('bob'), 'bob', { showInDonorSearch: false }));
    const db = as('bob');
    const b = writeBatch(db);
    b.update(doc(db, 'users/bob'), { showInDonorSearch: false, updatedAt: serverTimestamp() });
    b.delete(doc(db, 'donors/bob'));
    await assertSucceeds(b.commit());
  });

  test('a listed user cannot delete only the card', async () => {
    await assertFails(deleteDoc(doc(as('bob'), 'donors/bob')));
  });

  test('eligibleFrom must be at least 120 days after lastDonationAt, and never null', async () => {
    const last = ts(-10 * DAY);
    const early = Timestamp.fromMillis(last.toMillis() + 90 * DAY);
    const right = Timestamp.fromMillis(last.toMillis() + 120 * DAY);
    await assertFails(updateProfile(as('bob'), 'bob', { lastDonationAt: last }, { eligibleFrom: early }));
    await assertFails(updateProfile(as('bob'), 'bob', { lastDonationAt: last }, { eligibleFrom: null }));
    await assertSucceeds(updateProfile(as('bob'), 'bob', { lastDonationAt: last }, { eligibleFrom: right }));
  });

  test('the 1970 sentinel means "eligible now" when there is no donation', async () => {
    await assertSucceeds(updateDoc(doc(as('bob'), 'donors/bob'), { eligibleFrom: EPOCH }));
  });

  test('the card holds no phone, email, birth year or gender', async () => {
    await assertFails(updateDoc(doc(as('bob'), 'donors/bob'), { phone: users.bob.phone }));
    await assertFails(updateDoc(doc(as('bob'), 'donors/bob'), { gender: 'MALE' }));
  });
});

describe('users: lastActiveAt throttle (Spark test 8)', () => {
  test('a lastActiveAt write within 3 days of the last one fails', async () => {
    await seed(async (db) => updateDoc(doc(db, 'users/alice'), { lastActiveAt: ts(-1 * DAY) }));
    await assertFails(updateProfile(as('alice'), 'alice', { lastActiveAt: serverTimestamp() }));
  });

  test('after 3 days it succeeds', async () => {
    await seed(async (db) => updateDoc(doc(db, 'users/alice'), { lastActiveAt: ts(-4 * DAY) }));
    await assertSucceeds(updateProfile(as('alice'), 'alice', { lastActiveAt: serverTimestamp() }));
  });

  test('the first lastActiveAt write succeeds; it must be the server time', async () => {
    await assertFails(updateProfile(as('alice'), 'alice', { lastActiveAt: ts(-5 * DAY) }));
    await assertSucceeds(updateProfile(as('alice'), 'alice', { lastActiveAt: serverTimestamp() }));
  });

  test('a listed donor must mirror lastActiveAt to the card', async () => {
    await assertFails(updateProfile(as('bob'), 'bob', { lastActiveAt: serverTimestamp() }));
    await assertSucceeds(updateProfile(as('bob'), 'bob',
      { lastActiveAt: serverTimestamp() }, { lastActiveAt: serverTimestamp() }));
  });
});

describe('donations: SELF entries and counters', () => {
  function selfDonation(o = {}) {
    return {
      donatedAt: ts(-20 * DAY), component: 'WHOLE_BLOOD', hospitalName: 'Test Hospital',
      verified: false, source: 'SELF', createdAt: serverTimestamp(), ...o,
    };
  }

  test('the owner can add a SELF donation and count it (+1, same batch)', async () => {
    const db = as('alice');
    const b = writeBatch(db);
    b.set(doc(db, 'users/alice/donations/d1'), selfDonation());
    b.update(doc(db, 'users/alice'), {
      donationCount: increment(1), lastDonationId: 'd1', lastDonationAt: ts(-20 * DAY), updatedAt: serverTimestamp(),
    });
    await assertSucceeds(b.commit());
  });

  test('a SELF donation cannot raise verifiedDonations', async () => {
    const db = as('alice');
    const b = writeBatch(db);
    b.set(doc(db, 'users/alice/donations/d1'), selfDonation());
    b.update(doc(db, 'users/alice'), {
      donationCount: increment(1), verifiedDonations: increment(1), lastDonationId: 'd1', updatedAt: serverTimestamp(),
    });
    await assertFails(b.commit());
  });

  test('a SELF donation cannot claim to be verified, or be in the future', async () => {
    await assertFails(setDoc(doc(as('alice'), 'users/alice/donations/d1'), selfDonation({ verified: true })));
    await assertFails(setDoc(doc(as('alice'), 'users/alice/donations/d1'), selfDonation({ donatedAt: ts(2 * DAY) })));
  });

  test('deleting a SELF donation must take it out of the count', async () => {
    await seed(async (db) => {
      await setDoc(doc(db, 'users/alice/donations/d1'), selfDonation({ createdAt: ts(-DAY) }));
      await updateDoc(doc(db, 'users/alice'), { donationCount: 1, lastDonationId: 'd1' });
    });
    await assertFails(deleteDoc(doc(as('alice'), 'users/alice/donations/d1')));
    const db = as('alice');
    const b = writeBatch(db);
    b.delete(doc(db, 'users/alice/donations/d1'));
    b.update(doc(db, 'users/alice'), { donationCount: increment(-1), lastDonationId: 'd1', updatedAt: serverTimestamp() });
    await assertSucceeds(b.commit());
  });

  test('others cannot read or write my donations', async () => {
    await assertFails(getDoc(doc(as('eve'), 'users/alice/donations/d1')));
    await assertFails(setDoc(doc(as('eve'), 'users/alice/donations/d1'), selfDonation()));
  });
});

describe('account deletion', () => {
  test('a listed user deletes the card and profile together', async () => {
    const db = as('bob');
    await assertFails(deleteDoc(doc(db, 'users/bob')));
    const b = writeBatch(db);
    b.delete(doc(db, 'donors/bob'));
    b.delete(doc(db, 'users/bob'));
    b.delete(doc(db, 'userStats/bob'));
    await assertSucceeds(b.commit());
  });

  test('userStats cannot be deleted while requests are open (no count reset)', async () => {
    await seed(async (db) => setDoc(doc(db, 'userStats/alice'), { openRequests: 2, totalRequests: 2, lastRequestId: 'r1' }));
    await assertFails(deleteDoc(doc(as('alice'), 'userStats/alice')));
  });

  test('a stranger cannot delete a profile', async () => {
    await assertFails(deleteDoc(doc(as('eve'), 'users/alice')));
  });
});

describe('unused paths are closed', () => {
  test('the old subcollections and config docs are denied', async () => {
    await assertFails(setDoc(doc(as('alice'), 'users/alice/devices/x'), { token: 't' }));
    await assertFails(setDoc(doc(as('alice'), 'users/alice/hiddenRequests/x'), { createdAt: serverTimestamp() }));
    await assertFails(getDoc(doc(as('alice'), 'config/public')));
    await assertFails(setDoc(doc(as('alice'), 'anything/x'), { a: 1 }));
  });

  test('deleteField on a required field is rejected', async () => {
    await assertFails(updateProfile(as('alice'), 'alice', { phone: deleteField() }));
  });
});

describe('unknown blood group (P1): absent or null, never a made-up value', () => {
  /** A stored profile without a blood group. */
  const noGroup = (uid, o = {}) => { const u = userDoc(uid, o); delete u.bloodGroup; return u; };

  test('profile setup without a group is allowed (field absent, or null)', async () => {
    const u = newUserDoc('zed');
    delete u.bloodGroup;
    await assertSucceeds(profileSetup(as('zed'), 'zed', u));
    await assertSucceeds(profileSetup(as('yan'), 'yan', newUserDoc('yan', { bloodGroup: null })));
  });

  test('a made-up group such as "UNKNOWN" is refused', async () => {
    await assertFails(profileSetup(as('zed'), 'zed', newUserDoc('zed', { bloodGroup: 'UNKNOWN' })));
  });

  test('listing is refused without a group: at setup, and later', async () => {
    const u = newUserDoc('zed', { showInDonorSearch: true, consentAt: serverTimestamp() });
    delete u.bloodGroup;
    await assertFails(profileSetup(as('zed'), 'zed', u, donorCard(userDoc('zed'))));

    await seedUsers({ yan: noGroup('yan') });
    const db = as('yan');
    const b = writeBatch(db);
    b.update(doc(db, 'users', 'yan'), { showInDonorSearch: true, consentAt: serverTimestamp(), updatedAt: serverTimestamp() });
    b.set(doc(db, 'donors', 'yan'), donorCard(userDoc('yan')));
    await assertFails(b.commit());
  });

  test('adding the group later is a normal edit, and then listing works', async () => {
    const stored = noGroup('yan');
    await seedUsers({ yan: stored });
    await assertSucceeds(updateProfile(as('yan'), 'yan', { bloodGroup: 'A_POS' }));
    const db = as('yan');
    const b = writeBatch(db);
    b.update(doc(db, 'users', 'yan'), { showInDonorSearch: true, consentAt: serverTimestamp(), updatedAt: serverTimestamp() });
    b.set(doc(db, 'donors', 'yan'), donorCard({ ...stored, bloodGroup: 'A_POS' }));
    await assertSucceeds(b.commit());
  });

  test('a listed donor cannot drop their group without unlisting', async () => {
    await assertFails(updateProfile(as('bob'), 'bob', { bloodGroup: deleteField() }));
    await assertFails(updateProfile(as('bob'), 'bob', { bloodGroup: null }, { bloodGroup: null }));
  });

  test('offering is refused without a group, even if the offer claims one', async () => {
    const zed = noGroup('zed');
    await seedUsers({ zed });
    await seedRequest('r1', 'alice');
    await assertFails(offer(as('zed'), 'zed', 'r1', 'alice', { ...zed, bloodGroup: 'B_POS' }));
  });

  test('posting a request is allowed without a group (it carries the patient\'s group)', async () => {
    await seedUsers({ zed: noGroup('zed') });
    await assertSucceeds(createRequest(as('zed'), 'zed', 'r1'));
  });
});

describe('requester profile (D1): no donor details until "Become a donor"', () => {
  /** The short setup's profile: name, phone and area. No group, gender, birth year, weight or last donation. */
  const requester = (make, uid, o = {}) => {
    const u = make(uid, { available: false });
    for (const k of ['bloodGroup', 'gender', 'birthYear', 'weightOk']) delete u[k];
    return { ...u, ...o };
  };
  /** "Become a donor": the three donor details, plus availability (and listing, if chosen). */
  const details = { gender: 'FEMALE', birthYear: 1998, weightOk: true };

  test('the short setup creates a profile, with the donor fields absent or null (Kotlin DTOs)', async () => {
    await assertSucceeds(profileSetup(as('zed'), 'zed', requester(newUserDoc, 'zed')));
    await assertSucceeds(profileSetup(as('yan'), 'yan', requester(newUserDoc, 'yan',
      { bloodGroup: null, gender: null, birthYear: null, weightOk: null, lastDonationAt: null, consentAt: null })));
    await assertSucceeds(profileSetup(as('xia'), 'xia', requester(newUserDoc, 'xia', { bloodGroup: 'B_POS' })));
  });

  test('the donor details come all three together, or not at all', async () => {
    const db = as('zed');
    await assertFails(profileSetup(db, 'zed', requester(newUserDoc, 'zed', { gender: 'MALE' })));
    await assertFails(profileSetup(db, 'zed', requester(newUserDoc, 'zed', { birthYear: 1995 })));
    await assertFails(profileSetup(db, 'zed', requester(newUserDoc, 'zed', { gender: 'MALE', birthYear: 1995 })));
    await assertFails(profileSetup(db, 'zed', requester(newUserDoc, 'zed', { weightOk: true })));
  });

  test('without them: never listed, never available, no last donation', async () => {
    const db = as('zed');
    const listed = requester(newUserDoc, 'zed', { bloodGroup: 'B_POS', showInDonorSearch: true, consentAt: serverTimestamp() });
    await assertFails(profileSetup(db, 'zed', listed, donorCard({ ...userDoc('zed'), available: false })));
    await assertFails(profileSetup(db, 'zed', requester(newUserDoc, 'zed', { available: true })));
    await assertFails(profileSetup(db, 'zed', requester(newUserDoc, 'zed', { lastDonationAt: ts(-40 * DAY) })));
  });

  test('a stored requester can\'t switch on availability or write a donor card without the details', async () => {
    const stored = requester(userDoc, 'yan', { bloodGroup: 'B_POS' });
    await seedUsers({ yan: stored });
    const db = as('yan');
    await assertFails(updateProfile(db, 'yan', { available: true }));
    await assertFails(setDoc(doc(db, 'donors/yan'), donorCard(stored)));
    const b = writeBatch(db);
    b.update(doc(db, 'users', 'yan'), { showInDonorSearch: true, consentAt: serverTimestamp(), updatedAt: serverTimestamp() });
    b.set(doc(db, 'donors', 'yan'), donorCard(stored));
    await assertFails(b.commit());
  });

  test('"Become a donor" adds the details, availability, listing and the card in one batch', async () => {
    const stored = requester(userDoc, 'yan', { bloodGroup: 'B_POS' });
    await seedUsers({ yan: stored, xia: requester(userDoc, 'xia') });
    const db = as('yan');
    const b = writeBatch(db);
    b.update(doc(db, 'users', 'yan'), {
      ...details, available: true, showInDonorSearch: true, consentAt: serverTimestamp(), updatedAt: serverTimestamp(),
    });
    b.set(doc(db, 'donors', 'yan'), donorCard({ ...stored, available: true }));
    await assertSucceeds(b.commit());
    // Unlisted is fine too, and the group can come in the same edit. The age rule still holds.
    await assertFails(updateProfile(as('xia'), 'xia', { ...details, birthYear: new Date().getFullYear() - 10, bloodGroup: 'O_POS' }));
    await assertSucceeds(updateProfile(as('xia'), 'xia', { ...details, available: true, bloodGroup: 'O_POS' }));
  });

  test('a listed donor can\'t drop the details', async () => {
    await assertFails(updateProfile(as('bob'), 'bob', { gender: deleteField(), birthYear: deleteField(), weightOk: deleteField() }));
  });

  test('offering needs the donor details, even with a known group; posting a request doesn\'t', async () => {
    const zed = requester(userDoc, 'zed', { bloodGroup: 'B_POS' });
    await seedUsers({ zed });
    await seedRequest('r1', 'alice');
    await assertFails(offer(as('zed'), 'zed', 'r1', 'alice', zed));
    await assertSucceeds(createRequest(as('zed'), 'zed', 'r2'));
    await seedUsers({ zed: { ...zed, ...details, available: true } });
    await assertSucceeds(offer(as('zed'), 'zed', 'r1', 'alice', zed));
  });
});
