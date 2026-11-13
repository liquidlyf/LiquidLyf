// The demo seed data must stay valid under the rules, so contributors' demo builds
// behave like production. If a schema change breaks this, update seed/seed.mjs.
import { describe, test, beforeAll, afterAll, beforeEach } from 'vitest';
import { assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, updateDoc, serverTimestamp } from 'firebase/firestore';
import { setupEnv, teardownEnv, getEnv, as, seed } from './helpers.js';
import { writeSeed, seedPeople, seedRequests } from '../seed/seed.mjs';

beforeAll(setupEnv);
afterAll(teardownEnv);
beforeEach(async () => {
  await getEnv().clearFirestore();
  await seed((db) => writeSeed(db));
});

describe('seed data is valid under the rules', () => {
  test('every demo user can save their profile unchanged', async () => {
    for (const p of seedPeople) {
      const db = as(p.uid, { email: `${p.uid}@example.test` });
      const u = (await getDoc(doc(db, 'users', p.uid))).data();
      await assertSucceeds(updateDoc(doc(db, 'users', p.uid), { fullName: u.fullName, updatedAt: serverTimestamp() }));
      if (p.listed) await assertSucceeds(updateDoc(doc(db, 'donors', p.uid), { displayName: 'Demo D.' }));
    }
  });

  test('every open demo request can be edited by its requester', async () => {
    for (const r of seedRequests.filter((x) => x.data.status === 'OPEN')) {
      const db = as(r.data.requesterUid);
      await assertSucceeds(updateDoc(doc(db, 'requests', r.id), { notes: r.data.notes, updatedAt: serverTimestamp() }));
      await assertSucceeds(getDoc(doc(db, 'requests', r.id, 'private', 'contact')));
    }
  });
});
