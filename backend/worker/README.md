# backend/worker

LiquidLyf's small trusted server: a **Cloudflare Worker on the free plan**.
- It sends blood-request alerts through **FCM topics**: one message per tier, with no device tokens and no Firestore reads for recipients.
- It is the only writer of the fan-out fields `fanoutTier` and `nextTierAt`.

Design: `project-plan` doc 10 §6.

| Phase | What |
|---|---|
| **2 (live)** | `GET /health`, and `POST /v1/requests/{id}/dispatch`, which sends the first tier |
| **5 (live since 15 Nov 2026, version 476f0045)** | the two `notify` endpoints, plus crons: the 5-minute escalation sweep, hourly expiry and the daily retention clean-up; the per-uid daily alert cap |
| **5 backup (live since 15 Nov 2026)** | `backend/apps-script`: Google Apps Script runs the same escalation code if the sweep falls behind by more than 10 minutes. Live test with the sweep off: the phone alerted 12 min 42 s after the post (limit 20) |
| **7 (live since 15 Nov 2026, version e8bc0d1d)** | the new-report alert to the `admins` topic (its own cron, 2 minutes after each sweep); the retention job also deletes `adminLog` entries after 1 year |

## How dispatch works
The checks run cheapest first, so junk traffic never costs a Firestore read:
1. **Path shape.**
2. **App Check.** Monitor mode until Phase 9: a bad token is logged, not blocked.
3. **Firebase ID token.** RS256 against Google's keys, then `aud`, `iss`, `exp` and `email_verified`; the `banned` claim is refused.
4. **Per-uid rate limit**, 10 calls per minute.
5. **Read the request** (1 read). The caller must own it, it must be `OPEN` and still at `fanoutTier 0`, and it must have been created less than 10 minutes ago.
6. **Daily alert cap** (1 read, `src/cap.ts`). If the caller created 5 or more requests in the 24 hours before this one (cancelled ones count too), it isn't sent. It's parked at tier 0, so the sweep won't send it either, and it stays in the feed. The limit is `DAILY_ALERT_CAP` in `wrangler.jsonc`.
7. **Claim the tier** (1 write). It sets `fanoutTier` and `nextTierAt` with an `updateTime` precondition, so a repeated or concurrent dispatch loses cleanly and nobody is alerted twice.
8. **Send the tier's topic condition** through FCM HTTP v1. If FCM fails, the claim is released and the app gets 503.

Tiers (`src/tiers.ts`, doc 06 §4.3). Topics are `up_{upazila}_{GROUP}`, `di_{district}_{GROUP}` and `dv_{division}_{GROUP}`.
- **Tier 1:** the exact group in the hospital's upazila.
- **Tier 2:** the other accepted groups in the upazila, only if the requester accepts compatible blood.
- **Tier 3:** the district. Rh-negative requests, and requests without an upazila, start here.
- **Tier 4:** the neighbouring districts. For Rh-negative requests, it's the whole division instead.
  - The neighbours come from `backend/shared/district-neighbours.json`, generated from the app's `bd-geo.json` by `npm run gen:neighbours`.
  - A test fails if the two files drift apart.

`backend/shared/tier-vectors.json` pins the exact conditions. The Android `Tiers.kt` must pass the same vectors.

## The API, for the app (`WorkerClient`)

```
POST {worker_base_url}/v1/requests/{requestId}/dispatch
Authorization: Bearer <Firebase ID token>
X-Firebase-AppCheck: <App Check token>        (optional until enforcement)
```

Call it right after the request-create batch commits. It doesn't change the request's `updatedAt`, so the feed doesn't re-download the request.

| Status | Body | App does |
|---|---|---|
| 202 | `{accepted: true, tier, messages}` or `{accepted: true, alreadyDispatched: true}` | Done |
| 401 | `{error}` | Refresh the ID token, retry once |
| 403 | `{error}` | Not the owner, unverified email or suspended: don't retry |
| 404 / 409 / 422 | `{error}` | Don't retry: missing, closed, older than 10 min (the sweep sends it), or bad data |
| 429 | `{error}` | Back off |
| 429 | `{error, dailyCap: true}` | Don't retry. The request is posted but alerts nobody: tell the user they've reached today's limit of alerted requests |
| 500 / 503 | `{error}` | Retry with backoff, up to 3 times; the sweep covers the rest |

**Personal pushes (Phase 5).** Call these after the batch that changed the state, and after writing the inbox item:

```
POST {worker_base_url}/v1/requests/{requestId}/responses/{donorUid}/notify
POST {worker_base_url}/v1/requests/{requestId}/notify-responders
```

The Worker re-reads the doc (1 read) and checks the caller's role against the **current** state. Then it sends `{type, requestId}` to the other person's `usr_{uid}` topic.

| Caller | State | Push |
|---|---|---|
| The donor | response `OFFERED` | `NEW_OFFER` → requester |
| The requester | response `CONFIRMED` / `DECLINED` / `DONATED` | `OFFER_CONFIRMED` / `OFFER_DECLINED` / `DONATION_RECORDED` → donor |
| The requester | request `FULFILLED` / `CANCELLED` | `REQUEST_FULFILLED` / `REQUEST_CANCELLED` → donors still `OFFERED` or `CONFIRMED` |

Responses: 202 `{accepted, type}`. 403 means it isn't your request or offer; 409 means there's nothing to announce in this state. Other codes are as in the table above.

**"Rapid responder" badge (Phase 6).** On `NEW_OFFER`, the Worker also reads the request's `createdAt`. If the offer came within 15 minutes of the post (both are server times), it sets `users/{donor}.rapidResponder = true`. Only the Worker can write that field; the rules refuse it from the app. It's best effort: the push has already gone, so a failed write is only logged. Cost: +1 read per offer, +1 write for a fast one.

**Ask to donate (Phase 6, write contract 14).** After the ask batch commits (`users/{donor}/notifications/ask_{rid}` + `askCount +1`):

```
POST {worker_base_url}/v1/requests/{requestId}/asks/{donorUid}/notify
```

- The Worker checks that the caller owns the request, that it's `OPEN`, and that the inbox item exists with `type ASKED_TO_DONATE`, `fromUid` = the caller and `createdAt` at most 10 minutes ago. The rules already checked the rest when the ask was written: a listed, compatible donor who hasn't blocked you, and at most 10 asks per request.
- It sends `{type: "ASKED_TO_DONATE", requestId}` to `usr_{donorUid}`, collapse key `{requestId}:ASKED_TO_DONATE`.
- **Daily cap: 30 asks per person in any 24 hours**, counted over every inbox (collection-group index `notifications (fromUid, type, createdAt)`). Over the cap: 429 `{error, dailyAskCap: true}`. The ask stays in the donor's inbox but isn't pushed: tell the user they've reached today's limit.
- 202 `{accepted, type}`; 403 not your request; 404 no request; 409 closed, no such ask, or too old. Cost: 3 reads + 1 push.

**The push the donor's phone receives:**
- data-only, high priority, collapse key = `requestId`;
- data: `{type: "NEW_REQUEST", requestId, bloodGroup, district, urgency, tier}`.

Public fields only, because anyone can subscribe to a topic. The app renders the text.

## Crons (Phases 5 and 7; `wrangler.jsonc` → `triggers`, times in UTC)
| Cron | Job | Cost per run |
|---|---|---|
| `*/5 * * * *` | **Escalation sweep** (`escalation.ts`, run by `sweep.ts`): up to 25 OPEN requests with `nextTierAt <= now`. If active offers (`OFFERED`/`CONFIRMED`) cover the units still needed, it waits an hour. Otherwise it sends the next tier. A request the app never dispatched gets tier 1, after the same daily-cap check as dispatch. When all tiers are sent, or the request has expired or is over the cap, it's parked in 2100. | 1 read per request (≥ 1), +1 per first alert (cap count), + 1 batch write |
| `7 * * * *` | **Expiry** (`expiry.ts`): OPEN past `expiresAt` → `EXPIRED` + `userStats.openRequests −1`, in one commit. | 1 read + 1 commit per request |
| `0 3 * * *` (09:00 Dhaka) | **Retention** (`retention.ts`, decision B6): see below. Old requests are walked from a cursor in KV. | small |
| `2,7,12,…,57 * * * *` | **Report alert** (`reports.ts`, phase-07 7.3): counts the reports filed since the last alert. If there are any, it sends one push `{type: "NEW_REPORTS", count}` to the `admins` topic, which admin devices subscribe to. Its own run, 2 minutes after each sweep, so the sweep keeps its whole 50-subrequest budget. The cursor is in KV; the first run only sets it. | 1 read (COUNT), + 1 FCM send when there are new reports |

**What the retention job removes:**
- **90 days after a request closes:** `private/contact`, the donor phone and message in its responses, and the request's notes and closing reason. Short names stay.
- **Inbox items:** after 60 days.
- **Reports and the moderators' action log (`adminLog`):** after 1 year (doc 03 §5).

**The admin push carries only a count.** Anyone can subscribe to a topic, including `admins`, so it has no IDs, names or text. The admin's app then reads `reports`, which the rules allow only for the `admin` claim.

**Safety:**
- **Idempotent.** Every claim carries the doc's `updateTime`, so two overlapping sweeps (or the Apps Script backup) alert once.
- **One escalation, two runners.** `escalation.ts` is a generator that yields each Firestore or FCM call it needs. `sweep.ts` answers with `fetch`; the Apps Script backup answers with `UrlFetchApp`, from the same code bundled into `backend/apps-script/Shared.js`. Keep `escalation.ts` and what it imports free of `fetch`, WebCrypto, `TextEncoder`, `btoa` and timers: Apps Script has none of them.
- **Within the free plan's limits.** Every run stays under 50 subrequests. The sweep makes at most 40 FCM sends and cap counts together, and the rest wait for the next run.

## Run it
You need Node 22.12+. For the end-to-end tests, also Java 21 (`JAVA_HOME` = Android Studio's `jbr`).

```bash
npm ci
npm test              # unit tests: Google's token, JWKS, Firestore and FCM endpoints mocked
npm run test:e2e      # the bundled Worker in workerd against the Auth + Firestore emulators
npm run build         # bundle check (wrangler dry run), no Cloudflare account needed
npm run build:apps-script  # rebuild backend/apps-script/Shared.js after changing the escalation code
npm run dev           # local Worker on :8787 against running emulators (cd ../../firebase && npm run emulators)
```

**Emulator mode** (`env.local`):
- Firestore emulator, unsigned Auth-emulator ID tokens, and FCM sends that are logged instead of sent.
- It is refused for any project that isn't `demo-*`, so production can never accept unsigned tokens.

## Secrets and deployment (maintainers only)
- **The only secret is `GCP_SA_JSON`**, the `liquidlyf-worker` service-account key. Its only roles are **Cloud Datastore User** (`roles/datastore.user`) and **Firebase Cloud Messaging API Admin** (`roles/firebasecloudmessaging.admin`). Don't confuse the second with "Firebase Cloud Messaging Admin" (`roles/firebasenotifications.admin`), which can't send messages.
- **The key bypasses the security rules.** Store it only as a Cloudflare secret, then delete the downloaded file. Never commit it, and never paste it anywhere.
- **Load it with:**

  ```powershell
  Get-Content "$HOME\Downloads\<key>.json" -Raw | npx.cmd wrangler secret put GCP_SA_JSON --env=""
  ```

  `--env=""` picks the production (top-level) Worker without a warning. In Windows PowerShell, use `npx.cmd`, because the execution policy blocks `npx`. Check it with `npx.cmd wrangler secret list --env=""`, which lists names only.
- **Everything in `wrangler.jsonc` is non-secret:** project ID and number, the KV namespace ID, and the escalation minutes. The minutes must match Remote Config.
- **Before you deploy:**
  - `npx wrangler kv namespace create CACHE`, then put the ID in `wrangler.jsonc`;
  - set `FIREBASE_PROJECT_NUMBER`.
- **Deploy** with `npm run deploy`.
- **Rotate the key** yearly, and whenever a maintainer leaves (doc 10 §6.5).
- **CI deploys** (`.github/workflows/deploy-backend.yml`) start once the first manual deploy works:
  - It runs for merges to `main` that touch `firebase/` or `backend/`, or by hand.
  - It reruns the backend tests. Then a maintainer approves the `production-backend` environment, and it deploys the rules, the indexes and the Worker.
  - It never touches `GCP_SA_JSON`.
  - Its own credentials:
    - a `ci-firebase-deployer` service account with Firebase Rules Admin + Cloud Datastore Index Admin (+ Service Usage Consumer only if the CLI asks), and no data access;
    - a Cloudflare API token from the "Edit Cloudflare Workers" template.
  - It refuses to deploy while `wrangler.jsonc` still has `REPLACE_WITH_` placeholders.

## Day-1 spike
`node scripts/spike.mjs <key.json>` checks the service account against the real project:
- it mints an OAuth token;
- it sends an FCM message in validate-only mode;
- it checks that the Firestore database exists, and in which region;
- it does one read.

It never prints the key or the token, and it changes nothing. `--send` sends one real message to the topic `spike_test`.
