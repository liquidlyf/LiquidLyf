// Runs each script as the maintainer would, against the Auth + Firestore emulators
// (phase-07 7.9: "Admin scripts: dry-run mode against the emulator"). `npm test` starts them.
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { randomBytes } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";

if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
  throw new Error("Run these with `npm test`, which starts the emulators.");
}
const DIR = fileURLToPath(new URL("../", import.meta.url));
const DELETED = "Deleted user";
const app = initializeApp({ projectId: "demo-liquidlyf" });
const auth = getAuth(app);
const db = getFirestore(app);
const execP = promisify(execFile);

/** Runs a script; returns its exit code and combined output. */
async function script(file, args, env = process.env) {
  try {
    const { stdout, stderr } = await execP(process.execPath, [join(DIR, file), ...args], { env, cwd: DIR });
    return { code: 0, out: stdout + stderr };
  } catch (e) {
    return { code: e.code, out: `${e.stdout}${e.stderr}` };
  }
}
const id = (p) => `${p}${randomBytes(4).toString("hex")}`;
async function newUser(prefix, claims) {
  const uid = id(prefix);
  await auth.createUser({ uid, email: `${uid.toLowerCase()}@example.test`, emailVerified: true, password: "test-password-1" });
  if (claims) await auth.setCustomUserClaims(uid, claims);
  return { uid, email: `${uid.toLowerCase()}@example.test` };
}
const claimsOf = async (uid) => (await auth.getUser(uid)).customClaims ?? {};
const doc = async (path) => (await db.doc(path).get());
const t0 = Timestamp.fromDate(new Date("2026-11-01T10:00:00Z"));

after(() => app.delete());

describe("setAdmin / setCoordinator", () => {
  test("dry run by default, --apply sets the claim and keeps the others, --remove takes it away", async () => {
    const u = await newUser("adm", { coordinator: true });

    const dry = await script("setAdmin.mjs", ["--emulator", "--email", u.email]);
    assert.equal(dry.code, 0, dry.out);
    assert.match(dry.out, /Mode: dry run/);
    assert.match(dry.out, /Claims after: \{"coordinator":true,"admin":true\}/);
    assert.deepEqual(await claimsOf(u.uid), { coordinator: true });

    const set = await script("setAdmin.mjs", ["--emulator", "--email", u.email, "--apply"]);
    assert.equal(set.code, 0, set.out);
    assert.match(set.out, /OK: "admin" set/);
    assert.deepEqual(await claimsOf(u.uid), { coordinator: true, admin: true });

    const again = await script("setAdmin.mjs", ["--emulator", "--uid", u.uid, "--apply"]);
    assert.match(again.out, /Nothing to change/);

    const removed = await script("setAdmin.mjs", ["--emulator", "--uid", u.uid, "--remove", "--apply"]);
    assert.equal(removed.code, 0, removed.out);
    assert.deepEqual(await claimsOf(u.uid), { coordinator: true });
  });

  test("setCoordinator sets coordinator: true", async () => {
    const u = await newUser("crd");
    const r = await script("setCoordinator.mjs", ["--emulator", "--uid", u.uid, "--apply"]);
    assert.equal(r.code, 0, r.out);
    assert.deepEqual(await claimsOf(u.uid), { coordinator: true });
  });

  test("an unknown account is a clear error", async () => {
    const r = await script("setAdmin.mjs", ["--emulator", "--email", "nobody@example.test", "--apply"]);
    assert.equal(r.code, 2);
    assert.match(r.out, /no account with that email/);
  });
});

describe("revokeAndBan", () => {
  test("bans and signs out with --apply only; --unban removes the claim", async () => {
    const u = await newUser("ban", { admin: false });
    await db.doc(`requests/${id("rq")}`).set({ requesterUid: u.uid, status: "OPEN", createdAt: t0 });
    const before = await auth.getUser(u.uid);

    const dry = await script("revokeAndBan.mjs", ["--emulator", "--uid", u.uid]);
    assert.equal(dry.code, 0, dry.out);
    assert.match(dry.out, /open or hidden: 1/);
    assert.deepEqual(await claimsOf(u.uid), { admin: false });

    // tokensValidAfterTime has 1-second resolution.
    await new Promise((r) => setTimeout(r, 1100));
    const ban = await script("revokeAndBan.mjs", ["--emulator", "--uid", u.uid, "--apply"]);
    assert.equal(ban.code, 0, ban.out);
    assert.match(ban.out, /OK: banned/);
    assert.deepEqual(await claimsOf(u.uid), { admin: false, banned: true });
    const now = await auth.getUser(u.uid);
    assert.ok(new Date(now.tokensValidAfterTime) > new Date(before.tokensValidAfterTime ?? 0), "refresh tokens revoked");

    const unban = await script("revokeAndBan.mjs", ["--emulator", "--uid", u.uid, "--unban", "--apply"]);
    assert.equal(unban.code, 0, unban.out);
    assert.deepEqual(await claimsOf(u.uid), { admin: false });
  });
});

describe("deleteUserData", () => {
  let a, b, c, rA1, rA2, rA3, rB1, rB2;

  before(async () => {
    [a, b, c] = await Promise.all([newUser("del"), newUser("req"), newUser("don")]);
    [rA1, rA2, rA3, rB1, rB2] = ["ra1", "ra2", "ra3", "rb1", "rb2"].map(id);
    const entry = (name, status) => ({ name, group: "O_POS", status });
    const offer = (donor, rid, owner, status, extra = {}) => ({
      donorUid: donor, requestId: rid, requesterUid: owner, status, donorDisplayName: "Alice T.",
      donorBloodGroup: "O_POS", donorPhone: "+8801700000001", message: "on my way", createdAt: t0, updatedAt: t0, ...extra,
    });
    const request = (owner, status, extra = {}) => ({
      requesterUid: owner, requesterDisplayName: "Alice T.", status, notes: "call after 5", flagged: false,
      createdAt: t0, updatedAt: t0, responders: {}, ...extra,
    });
    const w = db.batch();
    // a's profile and everything under it
    w.set(db.doc(`users/${a.uid}`), { displayName: "Alice T.", bloodGroup: "O_POS", showInDonorSearch: true });
    w.set(db.doc(`donors/${a.uid}`), { bloodGroup: "O_POS", district: "BD4041" });
    w.set(db.doc(`userStats/${a.uid}`), { openRequests: 2, totalRequests: 3, lastRequestId: rA3 });
    w.set(db.doc(`users/${a.uid}/notifications/n1`), { type: "NEW_OFFER", fromUid: c.uid, requestId: rA1, createdAt: t0 });
    w.set(db.doc(`users/${a.uid}/notifications/n2`), { type: "OFFER_CONFIRMED", fromUid: b.uid, requestId: rB1, createdAt: t0 });
    w.set(db.doc(`users/${a.uid}/donations/self1`), { source: "SELF", verified: false, donatedAt: t0, component: "WHOLE_BLOOD", createdAt: t0 });
    w.set(db.doc(`users/${a.uid}/donations/${rB2}`), { source: "REQUEST", verified: true, requestId: rB2, donatedAt: t0, component: "WHOLE_BLOOD", createdAt: t0 });
    // a's requests: OPEN (c offered), FULFILLED, REMOVED by reports
    w.set(db.doc(`requests/${rA1}`), request(a.uid, "OPEN", { responders: { [c.uid]: entry("Chan D.", "OFFERED") } }));
    w.set(db.doc(`requests/${rA1}/private/contact`), { requesterUid: a.uid, contactName: "Alice", contactPhone: "+8801700000009" });
    w.set(db.doc(`requests/${rA1}/responses/${c.uid}`), offer(c.uid, rA1, a.uid, "OFFERED", { donorDisplayName: "Chan D." }));
    w.set(db.doc(`requests/${rA2}`), request(a.uid, "FULFILLED", { closedReason: "got 2 units" }));
    w.set(db.doc(`requests/${rA3}`), request(a.uid, "REMOVED", { reportCount: 3, flagged: true }));
    w.set(db.doc(`requests/${rA3}/private/contact`), { requesterUid: a.uid, contactName: "Alice", contactPhone: "+8801700000009" });
    // b's requests with a's offers: CONFIRMED (still active) and DONATED
    w.set(db.doc(`users/${b.uid}`), { displayName: "Bilkis R." });
    w.set(db.doc(`requests/${rB1}`), request(b.uid, "OPEN", {
      requesterDisplayName: "Bilkis R.", responders: { [a.uid]: entry("Alice T.", "CONFIRMED"), [c.uid]: entry("Chan D.", "OFFERED") },
    }));
    w.set(db.doc(`requests/${rB1}/responses/${a.uid}`), offer(a.uid, rB1, b.uid, "CONFIRMED"));
    w.set(db.doc(`requests/${rB2}`), request(b.uid, "FULFILLED", { requesterDisplayName: "Bilkis R.", responders: { [a.uid]: entry("Alice T.", "DONATED") } }));
    w.set(db.doc(`requests/${rB2}/responses/${a.uid}`), offer(a.uid, rB2, b.uid, "DONATED"));
    await w.commit();
  });

  test("dry run lists the work and changes nothing", async () => {
    const r = await script("deleteUserData.mjs", ["--emulator", "--email", a.email]);
    assert.equal(r.code, 0, r.out);
    assert.match(r.out, /offers: 2, of which 2 to anonymize and 1 still active/);
    assert.match(r.out, /requests: 3, of which 3 to anonymize and 2 open or hidden/);
    assert.match(r.out, /private contacts of their requests: 2/);
    assert.match(r.out, /profile: yes, donor card: yes, counters: yes, donations: 2, inbox items: 2/);
    assert.match(r.out, /Dry run: nothing changed/);
    assert.equal((await doc(`requests/${rA1}`)).get("status"), "OPEN");
    assert.equal((await doc(`requests/${rB1}/responses/${a.uid}`)).get("donorPhone"), "+8801700000001");
    assert.ok((await doc(`users/${a.uid}`)).exists);
    await auth.getUser(a.uid); // still there
  });

  test("--apply: contract 16, then the sign-in account", async () => {
    const r = await script("deleteUserData.mjs", ["--emulator", "--email", a.email, "--apply"]);
    assert.equal(r.code, 0, r.out);
    assert.match(r.out, /4\/4 sign-in account deleted/);

    // 1. a's offers: the active one withdrawn, both without name, phone or message
    const rb1 = await doc(`requests/${rB1}`);
    assert.deepEqual(rb1.get("responders"), {
      [a.uid]: { name: DELETED, group: "O_POS", status: "WITHDRAWN" },
      [c.uid]: { name: "Chan D.", group: "O_POS", status: "OFFERED" },
    });
    assert.equal(rb1.get("lastResponderUid"), a.uid);
    assert.equal(rb1.get("status"), "OPEN");
    assert.equal(rb1.get("requesterDisplayName"), "Bilkis R.");
    const resp1 = (await doc(`requests/${rB1}/responses/${a.uid}`)).data();
    assert.equal(resp1.status, "WITHDRAWN");
    assert.equal(resp1.donorDisplayName, DELETED);
    assert.equal(resp1.donorPhone, undefined);
    assert.equal(resp1.message, undefined);
    assert.ok(resp1.updatedAt.toMillis() > t0.toMillis());
    assert.deepEqual((await doc(`requests/${rB2}`)).get("responders"), { [a.uid]: { name: DELETED, group: "O_POS", status: "DONATED" } });
    const resp2 = (await doc(`requests/${rB2}/responses/${a.uid}`)).data();
    assert.equal(resp2.status, "DONATED");
    assert.equal(resp2.donorPhone, undefined);

    // 2. a's requests: anonymized; OPEN and REMOVED cancelled; contacts gone
    for (const [rid, status] of [[rA1, "CANCELLED"], [rA2, "FULFILLED"], [rA3, "CANCELLED"]]) {
      const d = (await doc(`requests/${rid}`)).data();
      assert.equal(d.status, status, rid);
      assert.equal(d.requesterDisplayName, DELETED);
      assert.equal(d.notes, undefined);
      assert.equal(d.closedReason, undefined);
      assert.equal(d.flagged, false);
      assert.equal((await doc(`requests/${rid}/private/contact`)).exists, false);
    }
    // c's offer on a's request is c's data: untouched
    assert.equal((await doc(`requests/${rA1}/responses/${c.uid}`)).get("donorPhone"), "+8801700000001");

    // 3. profile, card, counters, donations, inbox
    for (const p of [`users/${a.uid}`, `donors/${a.uid}`, `userStats/${a.uid}`,
      `users/${a.uid}/notifications/n1`, `users/${a.uid}/donations/self1`, `users/${a.uid}/donations/${rB2}`]) {
      assert.equal((await doc(p)).exists, false, p);
    }
    assert.ok((await doc(`users/${b.uid}`)).exists, "other users untouched");

    // 4. the sign-in account
    await assert.rejects(auth.getUser(a.uid), { code: "auth/user-not-found" });
  });

  test("running it again finds nothing left", async () => {
    const r = await script("deleteUserData.mjs", ["--emulator", "--uid", a.uid, "--apply"]);
    assert.equal(r.code, 0, r.out);
    assert.match(r.out, /no sign-in account any more/);
    assert.match(r.out, /Nothing left to delete/);
  });

  test("--uid cleans up data whose sign-in account is already gone", async () => {
    const ghost = id("gho");
    const rid = id("rg");
    await db.doc(`users/${ghost}`).set({ displayName: "Gone G." });
    await db.doc(`userStats/${ghost}`).set({ openRequests: 1, totalRequests: 1, lastRequestId: rid });
    await db.doc(`requests/${rid}`).set({ requesterUid: ghost, requesterDisplayName: "Gone G.", status: "OPEN", flagged: false, createdAt: t0 });
    const r = await script("deleteUserData.mjs", ["--emulator", "--uid", ghost, "--apply"]);
    assert.equal(r.code, 0, r.out);
    assert.match(r.out, /4\/4 no sign-in account to delete/);
    assert.equal((await doc(`requests/${rid}`)).get("status"), "CANCELLED");
    assert.equal((await doc(`users/${ghost}`)).exists, false);
    assert.equal((await doc(`userStats/${ghost}`)).exists, false);
  });
});

describe("safety checks", () => {
  let tmp;
  before(() => { tmp = mkdtempSync(join(tmpdir(), "liquidlyf-admin-test-")); });
  after(() => rmSync(tmp, { recursive: true, force: true }));
  const noEmulatorEnv = () => {
    const env = { ...process.env };
    delete env.FIRESTORE_EMULATOR_HOST;
    delete env.FIREBASE_AUTH_EMULATOR_HOST;
    return env;
  };

  test("needs --emulator or --key, exactly one user, and known options only", async () => {
    for (const [args, msg] of [
      [["--uid", "abc"], /pass --emulator .* or --key/],
      [["--emulator", "--uid", "abc", "--aply"], /unknown option --aply/],
      [["--emulator", "--uid", "abc", "--email", "x@example.test"], /exactly one of --uid or --email/],
      [["--emulator", "--uid", "a/b"], /doesn't look like a uid/],
      [["--emulator", "--key", "k.json", "--uid", "abc"], /not both/],
    ]) {
      const r = await script("setAdmin.mjs", args);
      assert.equal(r.code, 2, `${args.join(" ")}: ${r.out}`);
      assert.match(r.out, msg);
    }
  });

  test("production mode refuses emulator settings, a key inside the repo, and a non-key file", async () => {
    const withEnv = await script("deleteUserData.mjs", ["--key", join(tmp, "k.json"), "--uid", "abc"]);
    assert.equal(withEnv.code, 2);
    assert.match(withEnv.out, /FIRESTORE_EMULATOR_HOST is set/);

    const inRepo = await script("deleteUserData.mjs", ["--key", join(DIR, "package.json"), "--uid", "abc"], noEmulatorEnv());
    assert.equal(inRepo.code, 2);
    assert.match(inRepo.out, /inside the repo/);

    const notKey = join(tmp, "not-a-key.json");
    writeFileSync(notKey, JSON.stringify({ hello: "world" }));
    const bad = await script("revokeAndBan.mjs", ["--key", notKey, "--uid", "abc"], noEmulatorEnv());
    assert.equal(bad.code, 2);
    assert.match(bad.out, /isn't a service-account key/);
  });
});
