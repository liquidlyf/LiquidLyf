// The rules' compatibility table must match backend/shared/blood-compatibility.json,
// the vectors that the Worker and core/domain/BloodCompatibility.kt also test against.
import { describe, test, beforeAll, afterAll, beforeEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc } from 'firebase/firestore';
import {
  setupEnv, teardownEnv, getEnv, as, seed, seedUsers, userDoc, createRequest,
} from './helpers.js';

const vectors = JSON.parse(
  readFileSync(new URL('../../backend/shared/blood-compatibility.json', import.meta.url), 'utf8'),
);

beforeAll(setupEnv);
afterAll(teardownEnv);
beforeEach(async () => {
  await getEnv().clearFirestore();
  await seedUsers({ alice: userDoc('alice') });
});

async function resetStats() {
  await seed((db) => setDoc(doc(db, 'userStats/alice'), { openRequests: 0, totalRequests: 0 }));
}

describe('compatibility vectors', () => {
  for (const component of vectors.components) {
    test(`${component}: the full compatible list is accepted, nothing outside it is`, async () => {
      const db = as('alice');
      let n = 0;
      for (const group of vectors.groups) {
        const donors = vectors.compatibleDonors[component][group];
        await resetStats();
        await assertSucceeds(createRequest(db, 'alice', `ok_${n}`, {
          bloodGroup: group, component, acceptCompatible: true, acceptedGroups: donors,
        }, { stats: { lastRequestId: `ok_${n}` } }));
        const outside = vectors.groups.filter((g) => !donors.includes(g));
        if (outside.length) {
          await resetStats();
          await assertFails(createRequest(db, 'alice', `bad_${n}`, {
            bloodGroup: group, component, acceptCompatible: true, acceptedGroups: [...donors, outside[0]],
          }, { stats: { lastRequestId: `bad_${n}` } }));
        }
        n++;
      }
    });
  }
});
