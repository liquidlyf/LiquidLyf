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
| `../backend/shared/blood-compatibility.json` | Compatibility test vectors, shared with the Worker and the Android app |

## Run it

You need **Node 22.12+** and **Java 21**. Android Studio's bundled JBR works:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Git Bash on Windows
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
14. **Ask to donate (requester):** `users/{donor}/notifications/ask_{rid}` + request `askCount +1` (max 10), `lastAskedUid: donor`. The donor must be listed, in `acceptedGroups`, and must not have blocked you.
15. **Report a request:** `reports/{myUid}_{rid}` + request `reportCount +1`. When the new count is ≥ 3, the same batch may set `status: REMOVED`. A user report (`targetType USER`) is the report doc alone.
16. **Account deletion** (doc 06 §4.7). These paths don't check `verified` or `banned`, so a suspended user can still delete their account. On resume, re-read the server state before each step: for example, only a request that is still OPEN gets the `userStats −1`.
    1. **Withdraw my active offers:** batch 10 for each OFFERED or CONFIRMED response.
    2. **Anonymize my requests**, in any status:
       - `requesterDisplayName: 'Deleted user'` (exact string);
       - delete `notes` and `closedReason`;
       - `flagged: false`, plus `updatedAt`.

       An OPEN request also gets `status: CANCELLED` in the same write, plus `userStats.openRequests −1` with `lastRequestId: rid` in the same batch, so one open request per batch. Closed requests can share a batch.
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

### Queries the rules allow
- **Feed:** `requests` where `status == OPEN`, `district == X`, `acceptedGroups array-contains G`, ordered by `neededBy`. Delta sync: `district`, `acceptedGroups`, `updatedAt > lastSync`.
- **Nearby (Requests tab):** `requests` where `status == OPEN`, `district == X`, ordered by `neededBy`, `limit 20`, paged with `startAfter(neededBy)`. Any blood group, so people can share requests they can't donate to. A one-off `get()` with pull-to-refresh, not a listener.
- **Responders (requester):** `requests/{rid}/responses` **must** filter `where('requesterUid', '==', myUid)`.
- **My offers:** `collectionGroup('responses')` **must** filter `where('donorUid', '==', myUid)`.
- **Inbox:** `users/{me}/notifications` (owner only). Items are immutable; "seen" is `users.inboxSeenAt`.

## Design notes

Decisions made while turning doc 07's draft into tested rules:
- **Requests are readable in every status** by verified users. The delta-sync feed (doc 10 §5.2) has no status filter, and it must see closed and REMOVED requests to drop them. The app shows only OPEN, unexpired ones.
- **`responders` is a map keyed by uid**, not an array. The rules can only address one entry by key. `lastResponderUid` and `lastAskedUid` name the entry that a batch changes, so one counter move can't cover two docs (the same idea as `userStats.lastRequestId`).
- **Inbox items carry no free text:** `{type, fromUid, requestId, createdAt}`. The app renders the localized message, so nobody can write a scam message into someone else's inbox.
- **Donation counters live on `users`** (mirrored to the card). They move by +1/−1 only with a donation doc created or deleted in the same batch, so `verifiedDonations` can't be faked.
- **Removed:** the `devices`, `hiddenRequests` and `blocked` subcollections (replaced by topics and the `users` arrays) and `config/public` (Remote Config).
- **`acceptedGroups` is checked against the compatibility table** in the rules (`backend/shared/blood-compatibility.json`, needs medical review). This means a request can never list an incompatible group.
- **A new phone number resets `phoneVerified`.** The account `birthYear` must make the user 18–100.
- **Left to the Worker** (service account, bypasses the rules): `fanoutTier`, `nextTierAt`, `EXPIRED`. When it expires a request it must also do `userStats.openRequests −1` in the same commit. A REMOVED request keeps counting toward its owner's limit until an admin acts.
