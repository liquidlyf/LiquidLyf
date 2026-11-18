// The user-test helpers (seed/usertest.mjs): they refuse anything but a local emulator, and
// Karim's offer passes the real rules. The verify test runs only when the Auth emulator is up
// (npm run test:rules starts Firestore only).
import { describe, test, expect, beforeAll, afterAll, beforeEach } from 'vitest';
import { doc, getDoc, setDoc } from 'firebase/firestore';
import { setupEnv, teardownEnv, getEnv, seed, userDoc, requestDoc, ts } from './helpers.js';
import { writeSeed } from '../seed/seed.mjs';
import { localHost, karimOffers, verifyEmail, Refused } from '../seed/usertest.mjs';

const KARIM = 'demo-donor-bpos';

describe('the safety check: local emulators of demo-liquidlyf only', () => {
  const fs = (env) => localHost(env, 'FIRESTORE_EMULATOR_HOST', '127.0.0.1:8085');

  test('the default and local hosts pass', () => {
    expect(fs({}).port).toBe(8085);
    expect(fs({ FIRESTORE_EMULATOR_HOST: 'localhost:8095', GCLOUD_PROJECT: 'demo-liquidlyf' }).host).toBe('localhost');
    expect(fs({ FIRESTORE_EMULATOR_HOST: '[::1]:8085' }).host).toBe('::1');
  });

  test.each([
    ['the real project', { GCLOUD_PROJECT: 'liquidlyf-app' }, 'liquidlyf-app'],
    ['the real project (other variable)', { GOOGLE_CLOUD_PROJECT: 'liquidlyf-app' }, 'liquidlyf-app'],
    ['Google\'s servers', { FIRESTORE_EMULATOR_HOST: 'firestore.googleapis.com:443' }, 'not an emulator on this PC'],
    ['another PC', { FIRESTORE_EMULATOR_HOST: '192.168.0.5:8085' }, 'not an emulator on this PC'],
    ['a look-alike name', { FIRESTORE_EMULATOR_HOST: 'localhost.example.com:8085' }, 'not an emulator on this PC'],
    ['no port', { FIRESTORE_EMULATOR_HOST: '127.0.0.1' }, 'not an emulator on this PC'],
  ])('refuses %s', (_, env, message) => {
    expect(() => fs(env)).toThrow(Refused);
    expect(() => fs(env)).toThrow(message);
  });

  test('refuses when nothing answers, and never contacts anything for a non-test email', async () => {
    await expect(karimOffers({ FIRESTORE_EMULATOR_HOST: '127.0.0.1:1' })).rejects.toThrow('no Firestore emulator answers');
    await expect(verifyEmail('someone@gmail.com', { FIREBASE_AUTH_EMULATOR_HOST: '192.168.0.5:9099' })).rejects.toThrow('test account');
    await expect(verifyEmail('p1@example.test', { FIREBASE_AUTH_EMULATOR_HOST: '192.168.0.5:9099' })).rejects.toThrow('not an emulator');
  });
});

describe('Karim offers on the newest tester request, through the rules', () => {
  beforeAll(setupEnv);
  afterAll(teardownEnv);
  beforeEach(async () => {
    await getEnv().clearFirestore();
    await seed(async (db) => {
      await writeSeed(db);
      // A tester after the short setup (D1: no donor details) with two open requests.
      const u = userDoc('tester1', { email: 'p1@example.test', available: false });
      for (const k of ['bloodGroup', 'gender', 'birthYear', 'weightOk']) delete u[k];
      await setDoc(doc(db, 'users', 'tester1'), u);
      await setDoc(doc(db, 'requests', 'old'), requestDoc('tester1', { createdAt: ts(-3 * 60 * 60 * 1000) }));
      await setDoc(doc(db, 'requests', 'new'), requestDoc('tester1', { createdAt: ts(-60 * 1000) }));
    });
  });

  test('offers on the newest one with the app\'s three writes, and only once', async () => {
    expect(await karimOffers()).toContain('p1@example.test\'s request new');
    await seed(async (db) => {
      const r = (await getDoc(doc(db, 'requests/new'))).data();
      expect(r.responders[KARIM]).toEqual({ name: 'Karim D.', group: 'B_POS', status: 'OFFERED' });
      expect(r.responseCount).toBe(1);
      const o = (await getDoc(doc(db, `requests/new/responses/${KARIM}`))).data();
      expect(o).toMatchObject({ status: 'OFFERED', requesterUid: 'tester1', donorBloodGroup: 'B_POS' });
      expect(o.message.length).toBeGreaterThan(0);
      expect((await getDoc(doc(db, `users/tester1/notifications/NEW_OFFER_new_${KARIM}`))).exists()).toBe(true);
      expect((await getDoc(doc(db, `requests/old/responses/${KARIM}`))).exists()).toBe(false);
    });
    await expect(karimOffers()).rejects.toThrow('already offered');
  });

  test('clear errors: a request Karim\'s group can\'t give to, or no tester request', async () => {
    await seed(async (db) => setDoc(doc(db, 'requests', 'new'),
      requestDoc('tester1', { bloodGroup: 'A_POS', acceptedGroups: ['A_POS'], createdAt: ts(-60 * 1000) })));
    await expect(karimOffers()).rejects.toThrow('doesn\'t accept B_POS');
    await getEnv().clearFirestore();
    await seed((db) => writeSeed(db));
    await expect(karimOffers()).rejects.toThrow('no tester account');
  });
});

describe.runIf(process.env.FIREBASE_AUTH_EMULATOR_HOST)('verify a tester\'s email (Auth emulator)', () => {
  const base = () => `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`;
  const api = (path, body) => fetch(`${base()}/identitytoolkit.googleapis.com/v1/projects/demo-liquidlyf/${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer owner' }, body: JSON.stringify(body),
  }).then((r) => r.json());

  test('marks the account verified, once, and names a missing account', async () => {
    await fetch(`${base()}/emulator/v1/projects/demo-liquidlyf/accounts`, { method: 'DELETE' });
    await api('accounts', { localId: 'tester1', email: 'p1@example.test', password: 'password123', emailVerified: false });
    expect(await verifyEmail('p1@example.test')).toContain('is verified');
    expect((await api('accounts:lookup', { localId: ['tester1'] })).users[0].emailVerified).toBe(true);
    expect(await verifyEmail('p1@example.test')).toContain('already verified');
    await expect(verifyEmail('p2@example.test')).rejects.toThrow('no account p2@example.test');
  });
});
