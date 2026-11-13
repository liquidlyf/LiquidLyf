# backend/worker

LiquidLyf's small trusted server: a **Cloudflare Worker on the free plan**.
- It sends blood-request alerts through **FCM topics**: one message per tier, with no device tokens and no Firestore reads for recipients.
- It is the only writer of the fan-out fields `fanoutTier` and `nextTierAt`.

Design: `project-plan` doc 10 §6.

| Phase | What |
|---|---|
| **2 (now)** | `GET /health`, and `POST /v1/requests/{id}/dispatch`, which sends the first tier |
| 5 | `/notify`, plus crons: the 5-minute escalation sweep, hourly expiry and daily retention |

## How dispatch works
The checks run cheapest first, so junk traffic never costs a Firestore read:
1. **Path shape.**
2. **App Check.** Monitor mode until Phase 9: a bad token is logged, not blocked.
3. **Firebase ID token.** RS256 against Google's keys, then `aud`, `iss`, `exp` and `email_verified`; the `banned` claim is refused.
4. **Per-uid rate limit**, 10 calls per minute.
5. **Read the request** (1 read). The caller must own it, it must be `OPEN` and still at `fanoutTier 0`, and it must have been created less than 10 minutes ago.
6. **Claim the tier** (1 write). It sets `fanoutTier` and `nextTierAt` with an `updateTime` precondition, so a repeated or concurrent dispatch loses cleanly and nobody is alerted twice.
7. **Send the tier's topic condition** through FCM HTTP v1. If FCM fails, the claim is released and the app gets 503.

Tiers (`src/tiers.ts`, doc 06 §4.3). Topics are `up_{upazila}_{GROUP}`, `di_{district}_{GROUP}` and `dv_{division}_{GROUP}`.
- **Tier 1:** the exact group in the hospital's upazila.
- **Tier 3:** the district. Rh-negative requests, and requests without an upazila, start here.
- Tiers 2 and 4 come with the Phase 5 sweep.

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
| 500 / 503 | `{error}` | Retry with backoff, up to 3 times; the sweep covers the rest |

**The push the donor's phone receives:**
- data-only, high priority, collapse key = `requestId`;
- data: `{type: "NEW_REQUEST", requestId, bloodGroup, district, urgency, tier}`.

Public fields only, because anyone can subscribe to a topic. The app renders the text.

## Run it
You need Node 22.12+. For the end-to-end tests, also Java 21 (`JAVA_HOME` = Android Studio's `jbr`).

```bash
npm ci
npm test              # unit tests: Google's token, JWKS, Firestore and FCM endpoints mocked
npm run test:e2e      # the bundled Worker in workerd against the Auth + Firestore emulators
npm run build         # bundle check (wrangler dry run), no Cloudflare account needed
npm run dev           # local Worker on :8787 against running emulators (cd ../../firebase && npm run emulators)
```

**Emulator mode** (`env.local`):
- Firestore emulator, unsigned Auth-emulator ID tokens, and FCM sends that are logged instead of sent.
- It is refused for any project that isn't `demo-*`, so production can never accept unsigned tokens.

## Secrets and deployment (maintainers only)
- **The only secret is `GCP_SA_JSON`**, the `liquidlyf-worker` service-account key. Its only roles are `roles/datastore.user` and `roles/firebasecloudmessaging.admin`.
- **The key bypasses the security rules.** Store it only as a Cloudflare secret, then delete the downloaded file. Never commit it, and never paste it anywhere.
- **Load it with:**

  ```powershell
  Get-Content "$HOME\Downloads\<key>.json" -Raw | npx wrangler secret put GCP_SA_JSON
  ```
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
