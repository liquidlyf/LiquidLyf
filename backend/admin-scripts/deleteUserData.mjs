// Deletes an account for a deletion request sent by email or the website form (phase-07 7.4,
// doc 06 §4.7). It does what the app's own account deletion does (write contract 16 in
// firebase/README.md), as an admin:
//   1. their offers: the ones still OFFERED or CONFIRMED are withdrawn; on every offer the name
//      becomes "Deleted user" and the phone number and message are deleted;
//   2. their requests: the name becomes "Deleted user", notes and closing reason are deleted,
//      an OPEN or hidden (REMOVED) request is cancelled (userStats −1), and its private contact
//      (patient, phone, ward) is deleted;
//   3. their profile, donor card, counters, donation history and inbox are deleted;
//   4. the Firebase Auth account is deleted, last.
// Requests and offers stay for the other people involved, without the person's name or phone.
// Reports they filed or that name them stay with the admins (1 year, then the clean-up job).
//
// Safe to run again: every step re-reads the data first, so an interrupted run can be finished
// by running the same command again. Without --apply it only lists what it would change.
// Run `node deleteUserData.mjs` for the options; README.md has the steps.
import { FieldPath, FieldValue } from "firebase-admin/firestore";
import { connect, describeUser, findUser, parseArgs, run } from "./lib/admin.mjs";

const DELETED = "Deleted user"; // the exact string the rules and the app use
const PAGE = 300;

const usage = [
  "node deleteUserData.mjs (--email <email> | --uid <uid>) [--apply] (--emulator | --key <key.json>)",
  "  deletes the account and its personal data (write contract 16). --uid also works when the",
  "  sign-in account is already gone and only data is left. Without --apply it only lists what it would do.",
].join("\n");

run(async () => {
  const args = parseArgs(process.argv.slice(2));
  const { auth, db, apply } = connect(args);
  const user = await findUser(auth, args, { optional: true });
  const uid = user?.uid ?? args.uid;
  console.log(user ? `User: ${describeUser(user)}` : `User: ${uid} (no sign-in account any more; cleaning up data only)`);

  const plan = await survey(db, uid);
  printPlan(plan, !!user);
  if (plan.empty && !user) {
    console.log("Nothing left to delete.");
    return 0;
  }
  if (!apply) {
    console.log("Dry run: nothing changed. Add --apply to delete.");
    return 0;
  }

  for (const offer of plan.offers) await anonymizeOffer(db, uid, offer.ref);
  console.log(`1/4 offers done (${plan.offers.length})`);
  for (const req of plan.requests) await anonymizeRequest(db, uid, req.ref);
  console.log(`2/4 requests done (${plan.requests.length})`);
  await deleteProfile(db, uid);
  console.log("3/4 profile, donor card, counters, donations and inbox deleted");
  if (user) {
    await auth.deleteUser(uid);
    console.log("4/4 sign-in account deleted");
  } else {
    console.log("4/4 no sign-in account to delete");
  }
  console.log("OK: done. The phone keeps its topic subscriptions until the app is opened or removed;");
  console.log("    nothing is sent to the deleted account's personal topic any more.");
  return 0;
}, usage);

/** Everything the deletion touches, read-only. */
async function survey(db, uid) {
  // The (donorUid, createdAt) collection-group index exists for the app's "My offers".
  const offers = await all(db.collectionGroup("responses").where("donorUid", "==", uid).orderBy("createdAt", "desc"));
  const requests = await all(db.collection("requests").where("requesterUid", "==", uid));
  const contacts = (await Promise.all(requests.map((r) => r.ref.collection("private").doc("contact").get())))
    .filter((c) => c.exists).length;
  const [userDoc, card, stats] = await Promise.all(
    [`users/${uid}`, `donors/${uid}`, `userStats/${uid}`].map((p) => db.doc(p).get()));
  const [inbox, donations] = await Promise.all(
    ["notifications", "donations"].map((c) => db.collection(`users/${uid}/${c}`).count().get()));
  const counts = {
    activeOffers: offers.filter((o) => isActive(o.get("status"))).length,
    offersToAnonymize: offers.filter((o) => offerNeedsWork(o)).length,
    openRequests: requests.filter((r) => isOpen(r.get("status"))).length,
    requestsToAnonymize: requests.filter((r) => requestNeedsWork(r)).length,
    contacts,
    inbox: inbox.data().count,
    donations: donations.data().count,
  };
  const empty = !offers.some(offerNeedsWork) && !requests.some(requestNeedsWork) && contacts === 0
    && !userDoc.exists && !card.exists && !stats.exists && counts.inbox === 0 && counts.donations === 0;
  return { offers, requests, counts, userDoc: userDoc.exists, card: card.exists, stats: stats.exists, empty };
}

function printPlan(p, hasAccount) {
  const c = p.counts;
  console.log("Would change:");
  console.log(`  offers: ${p.offers.length}, of which ${c.offersToAnonymize} to anonymize and ${c.activeOffers} still active (withdrawn)`);
  console.log(`  requests: ${p.requests.length}, of which ${c.requestsToAnonymize} to anonymize and ${c.openRequests} open or hidden (cancelled)`);
  console.log(`  private contacts of their requests: ${c.contacts}`);
  console.log(`  profile: ${yes(p.userDoc)}, donor card: ${yes(p.card)}, counters: ${yes(p.stats)}, donations: ${c.donations}, inbox items: ${c.inbox}`);
  console.log(`  sign-in account: ${yes(hasAccount)}`);
}

const yes = (b) => (b ? "yes" : "no");
const isActive = (s) => s === "OFFERED" || s === "CONFIRMED";
const isOpen = (s) => s === "OPEN" || s === "REMOVED";
const offerNeedsWork = (o) => o.get("donorDisplayName") !== DELETED || o.get("donorPhone") !== undefined
  || o.get("message") !== undefined || isActive(o.get("status"));
const requestNeedsWork = (r) => r.get("requesterDisplayName") !== DELETED || r.get("notes") !== undefined
  || r.get("closedReason") !== undefined || r.get("flagged") !== false || isOpen(r.get("status"));

/**
 * Step 1, one transaction per offer: an active offer is withdrawn, then the response and the
 * request's responders entry lose the name (and the response its phone and message).
 */
async function anonymizeOffer(db, uid, responseRef) {
  const requestRef = responseRef.parent.parent;
  await db.runTransaction(async (tx) => {
    const [resp, req] = await Promise.all([tx.get(responseRef), tx.get(requestRef)]);
    if (!resp.exists || !offerNeedsWork(resp)) return;
    const status = isActive(resp.get("status")) ? "WITHDRAWN" : resp.get("status");
    tx.update(responseRef, {
      status,
      donorDisplayName: DELETED,
      donorPhone: FieldValue.delete(),
      message: FieldValue.delete(),
      updatedAt: FieldValue.serverTimestamp(),
    });
    const entry = req.exists ? req.get(new FieldPath("responders", uid)) : undefined;
    if (entry) {
      const update = [new FieldPath("responders", uid), { name: DELETED, group: entry.group, status }];
      if (status !== resp.get("status")) update.push("lastResponderUid", uid);
      tx.update(requestRef, ...update, "updatedAt", FieldValue.serverTimestamp());
    }
  });
}

/**
 * Step 2, one transaction per request: anonymized; an open or hidden one is cancelled with
 * userStats.openRequests −1; its private contact is deleted.
 */
async function anonymizeRequest(db, uid, requestRef) {
  const statsRef = db.doc(`userStats/${uid}`);
  const contactRef = requestRef.collection("private").doc("contact");
  await db.runTransaction(async (tx) => {
    const [req, stats, contact] = await Promise.all([tx.get(requestRef), tx.get(statsRef), tx.get(contactRef)]);
    if (req.exists && requestNeedsWork(req)) {
      const open = isOpen(req.get("status"));
      tx.update(requestRef, {
        requesterDisplayName: DELETED,
        notes: FieldValue.delete(),
        closedReason: FieldValue.delete(),
        flagged: false,
        ...(open ? { status: "CANCELLED" } : {}),
        updatedAt: FieldValue.serverTimestamp(),
      });
      if (open && stats.exists) {
        tx.update(statsRef, { openRequests: Math.max(0, (stats.get("openRequests") ?? 0) - 1), lastRequestId: req.id });
      }
    }
    if (contact.exists) tx.delete(contactRef);
  });
}

/** Step 3: the donor card, the counters, and users/{uid} with everything under it. */
async function deleteProfile(db, uid) {
  const batch = db.batch();
  batch.delete(db.doc(`donors/${uid}`));
  batch.delete(db.doc(`userStats/${uid}`));
  await batch.commit();
  // users/{uid} and its subcollections (inbox, donation history and any added later).
  await db.recursiveDelete(db.doc(`users/${uid}`));
}

/** Every document of the query, a page at a time. */
async function all(query) {
  const out = [];
  let page = await query.limit(PAGE).get();
  while (!page.empty) {
    out.push(...page.docs);
    if (page.size < PAGE) break;
    page = await query.startAfter(page.docs[page.docs.length - 1]).limit(PAGE).get();
  }
  return out;
}
