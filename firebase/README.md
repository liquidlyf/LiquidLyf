# firebase/

LiquidLyf's Firestore security rules, indexes, rules tests and emulator seed data.
LiquidLyf runs on the free **Spark** plan with **no Cloud Functions**, so the rules do the server's job:
- a counter moves only by +1/−1, in the same batch as the write that justifies it;
- the public donor card must equal the private profile after every batch.

Design: `project-plan` doc 07 §5 + §9 and doc 10 §4. Where this folder and the plan differ, see [Design notes](#design-notes).

| File | What |
|---|---|
| `firestore.rules` | The rules. Every rule has a test |
| `firestore.indexes.json` | Composite indexes (doc 07 §6 + §9.4) |
| `rules-tests/` | vitest + `@firebase/rules-unit-testing`, run on the Firestore emulator |
| `seed/seed.mjs` | Synthetic demo data (source of truth) |
| `seed/emulator-data/` | The committed emulator snapshot, generated from `seed.mjs` |
| `seed/usertest.mjs` | Test-day helpers for the user test, emulators only (see [User test](#user-test-test-day)) |
| `seed/hospitals.csv`, `seed/hospitals.mjs` | The real hospital list (ODbL) and the script that writes `seed/hospitals.json` for the app and the website. See [seed/README.md](seed/README.md#the-real-hospital-list-hospitalscsv) |
| `../backend/shared/blood-compatibility.json` | Compatibility test vectors, shared with the Worker and the Android app |

## Run it

You need **Node 22.12+** and **Java 21**. Android Studio's bundled JBR works:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Git Bash on Windows
export PATH="$JAVA_HOME/bin:$PATH"                               # if an older java is first on PATH
```

```bash
cd firebase
npm ci                      # pinned firebase-tools, rules-unit-testing, vitest
npm run test:rules          # starts the Firestore emulator, runs every rules test, stops it
npm run emulators           # Auth + Firestore + UI, loaded with the demo data
npm run seed                # (second terminal) refresh the demo data, requests dated from now
npm run seed:export         # regenerate seed/emulator-data/ after changing seed.mjs
```

The project ID is `demo-liquidlyf`. A `demo-` project has no live resources, so nothing here can reach production, and you need no Firebase login.

### Ports

| Emulator | Port |
|---|---|
| Firestore | **8085** (not the default 8080, which Oracle XE, Tomcat and Jenkins often hold) |
| Auth | 9099 |
| Emulator UI | 4000 |

For the `demo` app on a USB phone: `adb reverse tcp:8085 tcp:8085` and `adb reverse tcp:9099 tcp:9099`.

### Demo accounts

See [seed/README.md](seed/README.md). Every password is `password123`.

### User test (test day)

Two helpers for the 5-person user test (`seed/usertest.mjs`). Run them in a second terminal while `npm run emulators` runs:

```bash
npm run usertest:offer                     # Demo Karim (B+) offers on the newest open request a tester posted
npm run usertest:verify p1@example.test    # marks that tester's email as verified
```

- **Testers are `p1@example.test` … `p5@example.test`.** `usertest:offer` finds their newest open request. Karim then offers on it ("আমি এক ঘণ্টার মধ্যে আসতে পারব।"), signed in as Karim, with the same three writes as the app and through the rules. The request page shows it within seconds. It prints which request it used, or why it can't, for example: no tester yet, no open request, Karim already offered, or the request doesn't accept B+.
- **`usertest:verify`** replaces Ctrl+clicking the link in the emulator window. Then the tester taps "I've verified".
- **Emulators only.** Both helpers refuse to run unless they reach an emulator on this PC (127.0.0.1 or localhost) for `demo-liquidlyf`. Pointing them at a real project or host fails. They use no login and no key. `rules-tests/usertest.test.js` checks this.
- **Run `npm run seed` between testers.** Each new tester then starts from clean data, with no offer from Karim.

## The write contract (for the app)

The rules accept only these shapes. **Each numbered item is one `WriteBatch`.**

General:
- `createdAt` and `updatedAt` are `FieldValue.serverTimestamp()`. `updatedAt` is required on **every** `users` and `requests` write.
- Optional fields may be absent or `null`. IDs and area codes match `[A-Za-z0-9_-]`.
- Phones are E.164 `+8801[3-9]XXXXXXXX`.
- `neededBy` uses whole seconds, and `expiresAt` = `neededBy` + 24 h exactly.

### Profile
1. **Profile setup:**
   - `users/{uid}`: all required fields, with `donationCount: 0`, `verifiedDonations: 0`. Email = the auth email. No `phoneVerified`, no `role`;
   - **`bloodGroup` may be unknown (P1)**. Leave the field out, or set it to `null`; never use a made-up value such as `"UNKNOWN"`. Without a group you can post requests, but you can't be listed (`showInDonorSearch` needs a group) and you can't offer. Adding the group later is a normal profile edit;
   - **the donor details are `gender`, `birthYear` and `weightOk`: all three or none (D1)**. A requester's short setup writes none of them (absent or `null`), and then `available: false`, `showInDonorSearch: false` and no `lastDonationAt`. Such a profile can post requests but is never listed and can't offer;
   - **"Become a donor"** is a normal profile edit that adds all three, with `available` (and `bloodGroup` if it's new). If the person also chooses to be listed, the same batch sets `showInDonorSearch: true` + `consentAt` and creates `donors/{uid}`. A listed donor can't drop the details;
   - `userStats/{uid}` = `{openRequests: 0, totalRequests: 0}` (it must exist before the first request);
   - if `showInDonorSearch`: `consentAt` on the profile, plus `donors/{uid}`.
2. **Profile edit:** `users/{uid}`. If listed and a mirrored field changes (`bloodGroup`, area codes, `available`, `lastDonationAt`, counters, `lastActiveAt`), update `donors/{uid}` in the same batch with equal values.
3. **Unlist:** `users.showInDonorSearch = false` + **delete** `donors/{uid}`.
4. **`lastActiveAt`:** at most once every 3 days, = server time; mirror it to the card if listed.
5. **Donor card:** `eligibleFrom` is never null. Use `Timestamp(0)` (1970) for "eligible now", otherwise ≥ `lastDonationAt` + 120 days.

### Requests
6. **Create:**
   - `requests/{rid}`: `status OPEN`, all counters 0, `responders: {}`, `fanoutTier: 0`, `nextTierAt` = server time, `flagged` = notes contain a money word;
   - `userStats/{uid}`: `openRequests +1`, `totalRequests +1`, `lastRequestId: rid`;
   - `requests/{rid}/private/contact` with `requesterUid`.

   Limit: 1 open request for accounts under 7 days old, 3 after that. `acceptedGroups` = `[bloodGroup]`, or a subset of the compatible list when `acceptCompatible`.
7. **Close:** `status → CANCELLED | FULFILLED` (+ `closedReason`) + `userStats.openRequests −1` with `lastRequestId: rid`.
8. **Edit:** `notes` (recompute `flagged`), `unitsNeeded`, `neededBy` + `expiresAt` together.

### Offers and responses (one response per batch)
9. **Offer (donor):**
   - `requests/{rid}/responses/{myUid}` with `status OFFERED`;
   - on the request: `responders.{myUid} = {name, group, status: 'OFFERED'}` (copied from the response), `responseCount +1`, `lastResponderUid: myUid`;
   - optionally the inbox item `users/{requester}/notifications/NEW_OFFER_{rid}_{myUid}`.

   Refused if the requester has blocked me (`blockedUids`, phase-07 7.2). The offer is also what lets a donor read `private/contact`, and only while it's `OFFERED`, `CONFIRMED` or `DONATED`: a declined or withdrawn donor loses access.
10. **Withdraw (donor):** response `→ WITHDRAWN` + `responders.{myUid}.status = 'WITHDRAWN'` + `lastResponderUid: myUid`.
11. **Confirm / decline / mark donated (requester):**
    - response `status` (OFFERED → CONFIRMED|DECLINED, CONFIRMED → DONATED|DECLINED);
    - `responders.{donorUid}.status` = the same, `lastResponderUid: donorUid`;
    - DONATED also does `unitsFulfilled +1`. When it reaches `unitsNeeded`, the same batch may set `FULFILLED` with the userStats −1;
    - optionally the inbox item `users/{donor}/notifications/{OFFER_CONFIRMED|OFFER_DECLINED|DONATION_RECORDED}_{rid}`.
12. **Verified donation (donor, after seeing DONATED):**
    - `users/{uid}/donations/{rid}` with `source REQUEST`, `verified true`, `requestId: rid`;
    - the profile: `donationCount +1`, `verifiedDonations +1`, `lastDonationId: rid`, `lastDonationAt`;
    - the card, if listed.
13. **Self-reported donation:** `donations/{id}` with `source SELF`, `verified false` + `donationCount +1`, `lastDonationId: id`. Deleting it needs `donationCount −1` with `lastDonationId: id`.

### Trust and safety
14. **Ask to donate (requester):** `users/{donor}/notifications/ask_{rid}` (`type: ASKED_TO_DONATE`) + request `askCount +1` (max 10) + **my `userStats/{me}`: `lastAskedUid: donor, lastAskRid: rid`** (private, so nobody else learns whom I asked; the request has no `lastAskedUid`). The donor must be listed, in `acceptedGroups`, and must not have blocked you. Then call the Worker's `POST /v1/requests/{rid}/asks/{donor}/notify`, which pushes it and applies the daily cap of 30 asks (`backend/worker/README.md`).
15. **Report a request:** `reports/{myUid}_{rid}` + request `reportCount +1`. When the new count is ≥ 3, the same batch may set `status: REMOVED`. A user report (`targetType USER`) is the report doc alone.
16. **Account deletion** (doc 06 §4.7). These paths don't check `verified` or `banned`, so a suspended user can still delete their account. On resume, re-read the server state before each step: for example, only a request that is still OPEN gets the `userStats −1`.
    1. **Withdraw my active offers:** batch 10 for each OFFERED or CONFIRMED response.
    2. **Anonymize my requests**, in any status:
       - `requesterDisplayName: 'Deleted user'` (exact string);
       - delete `notes` and `closedReason`;
       - `flagged: false`, plus `updatedAt`.

       An OPEN **or hidden (`REMOVED`)** request also gets `status: CANCELLED` in the same write, plus `userStats.openRequests −1` with `lastRequestId: rid` in the same batch, so one such request per batch (a hidden one still holds its slot, B15). Closed requests can share a batch.
    3. **Anonymize my offers**, in any status, one batch per request:
       - on the request: `responders.{me} = {name: 'Deleted user', group, status}`, with group and status unchanged, plus `updatedAt`;
       - on `responses/{me}`: `donorDisplayName: 'Deleted user'`, delete `donorPhone` and `message`, plus `updatedAt`. The status is unchanged.
    4. **Delete** my `requests/*/private/contact` docs and `users/{uid}/notifications/*`. Re-deleting a contact that's already gone is allowed.
    5. **Final batch:**
       - `donors/{uid}`;
       - `userStats/{uid}`, which needs `openRequests == 0`;
       - every `users/{uid}/donations/*`. Verified donations can go only together with, or after, `users/{uid}`;
       - `users/{uid}`, which needs the donor card gone in the same batch or before.
    6. Unsubscribe from all topics, then call `user.delete()` last.

    Reports the user filed stay with the admins, keyed by uid.
17. **Admin action** (custom claim `admin: true`; the hidden Moderation screen, phase-07 7.4–7.5), one batch:
    - the action itself, plus `updatedAt`, or a report's `status`. For a request:
      - hide: `OPEN → REMOVED`; restore: `REMOVED → OPEN`;
      - cancel: `OPEN` or `REMOVED → CANCELLED`, **plus the owner's `userStats/{owner}`: `openRequests −1` (`increment(-1)`) with `lastRequestId: rid`**, which gives the slot back (B15). Leave it out only if the owner's `userStats` no longer exists;
      - closed requests stay closed: no other change is allowed;
    - plus `adminLog/{id}`: `adminUid` (me), `action` (`DISMISS_REPORT`, `ACTION_REPORT`, `REMOVE_REQUEST`, `RESTORE_REQUEST`, `CANCEL_REQUEST`), `targetType` (`REQUEST`, `USER`, `REPORT`), `targetId`, optional `reportId` and `note` (≤ 500), `createdAt` = server time.

    The log is create-only: nobody can edit or delete an entry from the app, not even an admin. Only admins read it.

### Queries the rules allow
- **Feed:** `requests` where `status == OPEN`, `district == X`, `acceptedGroups array-contains G`, ordered by `neededBy`. Delta sync: `district`, `acceptedGroups`, `updatedAt > lastSync`.
- **Nearby (Requests tab):** `requests` where `status == OPEN`, `district == X`, ordered by `neededBy`, `limit 20`, paged with `startAfter(neededBy)`. Any blood group, so people can share requests they can't donate to. A one-off `get()` with pull-to-refresh, not a listener.
- **Responders (requester):** `requests/{rid}/responses` **must** filter `where('requesterUid', '==', myUid)`.
- **My offers:** `collectionGroup('responses')` **must** filter `where('donorUid', '==', myUid)`.
- **Inbox:** `users/{me}/notifications` (owner only). Items are immutable; "seen" is `users.inboxSeenAt`.
- **Find donors (phase-06 6.1):** `donors` where `district == X` (or `upazila == Y` for the area filter), `bloodGroup in [chips]`, `available == true`, plus `eligibleFrom <= now` for "Eligible now", `orderBy('eligibleFrom')`, `limit 20`, paged with `startAfter`; the header uses `count()`. Indexes: `(district | upazila, bloodGroup, available, eligibleFrom)`. Donor cards carry no phone number.

## Design notes

Decisions made while turning doc 07's draft into tested rules:
- **Requests are readable in every status** by verified users. The delta-sync feed (doc 10 §5.2) has no status filter, and it must see closed and REMOVED requests to drop them. The app shows only OPEN, unexpired ones.
- **`responders` is a map keyed by uid**, not an array. The rules can only address one entry by key. `lastResponderUid` names the entry that a batch changes, so one counter move can't cover two docs (the same idea as `userStats.lastRequestId`). For asks, the donor is named in the requester's own `userStats` (`lastAskedUid` + `lastAskRid`), which only they and admins can read, so the public request never shows whom they asked.
- **Inbox items carry no free text:** `{type, fromUid, requestId, createdAt}`. The app renders the localized message, so nobody can write a scam message into someone else's inbox.
- **Donation counters live on `users`** (mirrored to the card). They move by +1/−1 only with a donation doc created or deleted in the same batch, so `verifiedDonations` can't be faked.
- **Removed:** the `devices`, `hiddenRequests` and `blocked` subcollections (replaced by topics and the `users` arrays) and `config/public` (Remote Config).
- **`acceptedGroups` is checked against the compatibility table** in the rules (`backend/shared/blood-compatibility.json`, needs medical review). This means a request can never list an incompatible group.
- **A new phone number resets `phoneVerified`.** The account `birthYear`, when given, must make the user 18–100.
- **Requesters give no donor details (D1, 17 Nov 2026).** Without `gender`, `birthYear` and `weightOk`, the rules hold `showInDonorSearch` and `available` at `false`, so the person has no donor card (never in Find donors, never asked to donate). The app subscribes to the donor alert topics only while listed and available (`PushTopicManager`), so they are never alerted either. An offer needs the details too.
- **Left to the Worker** (service account, bypasses the rules): `fanoutTier`, `nextTierAt`, `EXPIRED`, and `users.rapidResponder` (the Phase 6 badge: an offer within 15 minutes of the post; the app can't create, change or remove it, so profile edits must stay `update()`s, never a whole-document `set()`). When it expires a request it must also do `userStats.openRequests −1` in the same commit. A REMOVED request keeps counting toward its owner's limit until an admin cancels it (that batch gives the slot back) or the owner deletes their account (B15); a restore makes it OPEN again, still counted.
