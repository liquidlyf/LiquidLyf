// The demo seed data must stay valid under the rules, so contributors' demo builds
// behave like production. If a schema change breaks this, update seed/seed.mjs.
import { describe, test, expect, beforeAll, afterAll, beforeEach } from 'vitest';
import { assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, updateDoc, serverTimestamp } from 'firebase/firestore';
import { setupEnv, teardownEnv, getEnv, as, seed } from './helpers.js';
import { writeSeed, seedPeople, seedRequests, displayName, appDisplayName } from '../seed/seed.mjs';

beforeAll(setupEnv);
afterAll(teardownEnv);

describe('short names follow the app\'s rule (Validators.displayName, D22)', () => {
  test.each([
    // D22 (the maintainer, 19 Nov 2026): the full Mohammad spellings, Mosammat and "Most" are skipped.
    ['Mohammad Rafiqul Islam', 'Rafiqul I.'],
    ['Mohammad Rafiqul', 'Rafiqul'],
    ['Mohammad', 'Mohammad'], // alone, it stays whole
    ['Mohammad A', 'Mohammad A.'], // too short after skipping: falls back to all the words
    ['Mohammed Ali Khan', 'Ali K.'], ['Muhammad Ali Khan', 'Ali K.'], ['Muhammed Ali Khan', 'Ali K.'],
    ['Mohamed Ali Khan', 'Ali K.'], ['MOHAMAD Ali Khan', 'Ali K.'],
    ['মোহাম্মদ রফিকুল ইসলাম', 'রফিকুল ই.'], ['মোহাম্মাদ রফিকুল ইসলাম', 'রফিকুল ই.'], ['মুহাম্মদ রফিকুল ইসলাম', 'রফিকুল ই.'],
    ['Mosammat Rokeya Begum', 'Rokeya B.'], ['মোসাম্মৎ রোকেয়া বেগম', 'রোকেয়া ব.'],
    ['Most Rokeya Begum', 'Rokeya B.'], ['Most. Rokeya Begum', 'Rokeya B.'],
    ['মোহাম্মদ রফিকুল ইসলাম', 'রফিকুল ই.'], // "ো" typed as "ে" + "া" (NFC, like the app)
    // Unchanged before D22.
    ['Md. Mohammad Ali', 'Ali'], ['মোঃ করিম উদ্দিন', 'করিম উ.'], ['Dr. Nasrin Akter', 'Nasrin A.'],
    ['Rahim Khan (brother)', 'Rahim K.'], ['Rahim, Karim', 'Rahim K.'], ['Mostafa Kamal', 'Mostafa K.'],
  ])('%s → %s', (full, short) => expect(appDisplayName(full)).toBe(short));

  test('seed names leave out the "Demo" marker, and every short name fits the rules (2–40)', () => {
    expect(displayName('Demo Karim Donor')).toBe('Karim D.');
    for (const p of seedPeople) expect(displayName(p.name).length).toBeGreaterThanOrEqual(2);
    expect(appDisplayName('A'.repeat(50) + ' Khan')).toBe('A'.repeat(37) + ' K.');
  });
});

describe('seed data is valid under the rules', () => {
  beforeEach(async () => {
    await getEnv().clearFirestore();
    await seed((db) => writeSeed(db));
  });

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
