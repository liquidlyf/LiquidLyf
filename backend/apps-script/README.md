# backend/apps-script

The **escalation backup** (phase-05 task 5.8, doc 10 §6.4). It's a Google Apps Script on the project account `liquidlyf@gmail.com`, and it's free.

**What it does:**
- Every 10 minutes it looks for OPEN requests **overdue by more than 10 minutes** (`nextTierAt <= now − 10 min`).
- That happens only if the Worker's 5-minute sweep is down or behind. While the Worker is healthy, each run is one empty query (1 read).
- When it finds some, it runs **the very same escalation** as the Worker.
- Together that's at most 20 minutes from "due" to "alerted", the phase-05 acceptance limit.

**The same code as the Worker:**
- `Shared.js` is the Worker's own `src/escalation.ts`, bundled together with the tiers, the daily alert cap, the claim writes and the FCM message.
- `escalation.ts` is a generator that yields each Firestore or FCM call it needs. The Worker answers with `fetch`; `Backup.js` answers with Apps Script's synchronous `UrlFetchApp`.
- **Idempotent:** every claim carries the doc's `updateTime`, so the Worker and the backup never alert twice for one tier.

| File | What | Edit? |
|---|---|---|
| `Backup.js` | Apps Script glue: trigger handler, `setup()`, token minting with `Utilities`, `UrlFetchApp` calls | Yes |
| `Shared.js` | **Generated** by `npm run build:apps-script` (in `backend/worker`) from `src/shared.ts` | No: change the Worker's code and rebuild. A test fails if it's out of date |
| `appsscript.json` | Manifest: V8 runtime, Asia/Dhaka | Rarely |

**Its own key:** a `liquidlyf-backup` service account with the same two roles as the Worker's, **Cloud Datastore User** + **Firebase Cloud Messaging API Admin**. The key lives only in the script property `GCP_SA_JSON`, never in git.

**No public endpoint.** The plan allowed a `doPost` emergency endpoint, but it isn't built: Apps Script web apps can't read request headers or set HTTP status codes, so the app would need a second API contract. The automatic backup already covers a dead Worker within 20 minutes.

## Set it up (once, maintainer)
1. **The key.** console.cloud.google.com → project `liquidlyf-app` → IAM & Admin → Service accounts → **Create service account** `liquidlyf-backup`.
   - Roles: **Cloud Datastore User** + **Firebase Cloud Messaging API Admin**, nothing else.
   - Done → open it → Keys → Add key → JSON. The file lands in Downloads.
2. **The project.** script.google.com, signed in as liquidlyf@gmail.com → **New project** → rename it "LiquidLyf backup".
3. **The key, into the script.**
   - Project Settings (gear icon) → tick **Show "appsscript.json" manifest file in editor**.
   - Further down: **Script properties** → Add script property → name `GCP_SA_JSON`, value = the whole key file. Open it in Notepad, Ctrl+A, Ctrl+C, paste.
   - Save.
4. **The code.** In the editor (`<>` icon):
   - `appsscript.json`: replace its content with this folder's `appsscript.json`.
   - `Code.gs`: ⋮ → Rename → `Backup`. Replace its content with `Backup.js`.
   - **+** → Script → name it `Shared`. Paste `Shared.js`.
   - Ctrl+S.
5. **Start it.**
   - In the toolbar, pick the function **`setup`** → Run → Review permissions → liquidlyf@gmail.com.
   - "Google hasn't verified this app" is expected for your own script: Advanced → Go to LiquidLyf backup → Allow. It asks to "connect to an external service" and "run when you're not present".
   - The log ends with `OK: the key works for liquidlyf-app. backupSweep now runs every 10 minutes.`
6. **Delete the key file** from Downloads (and the Recycle Bin).
7. **Failure emails.** Triggers (alarm-clock icon) → the `backupSweep` trigger → edit → Failure notification settings → **Notify me immediately**. A failed run (bad key, missing role) then emails liquidlyf@gmail.com.

**To check it:**
- Executions (list icon) shows a run every 10 minutes, each logging `{"backupSweep":{"due":0,...}}`.
- To test it, stop the Worker's crons, or post a request while the Worker is down: the backup should alert within 20 minutes.

## Keep it current
- After any change to the Worker's escalation code (`escalation.ts`, `tiers.ts`, `cap.ts`, `fanout.ts`, `fcm.ts`, `firestore.ts`, `oauth.ts`, the neighbours or compatibility JSON):
  1. Run `npm run build:apps-script` and commit `Shared.js`.
  2. **Paste `Shared.js` into the Apps Script again.** That step is manual; nothing deploys it.
- `Backup.js` repeats three settings from `wrangler.jsonc`: the project ID, `ESCALATION_MINUTES` and `DAILY_ALERT_CAP`. A test checks that they match.
- **Rotate the key** yearly, and whenever a maintainer leaves:
  1. Create a new key and replace the `GCP_SA_JSON` property.
  2. Run `setup()`.
  3. Delete the old key in the console.
- **To stop the backup:** run `removeTrigger()`.

## Tests (no Google account needed)
- `npm test` (in `backend/worker`), `test/apps-script.test.ts`:
  - runs `Shared.js` + `Backup.js` in a bare V8 context (no `fetch`, `TextEncoder`, `btoa` or `crypto`, as in Apps Script), with fakes of `UrlFetchApp`, `Utilities`, `PropertiesService`, `CacheService`, `LockService` and `ScriptApp`;
  - checks token minting (a real RS256 signature), the overdue query, the claim, the push (byte-for-byte the Worker's), FCM failures, the daily cap, the lock, `setup()`, and the config errors;
  - checks that `Shared.js` is the current build.
- `npm run test:e2e`, `test/e2e/apps-script.e2e.test.ts`: the backup against the Firestore emulator, next to the Worker's sweep.
  - Overdue requests go to the backup and fresh ones to the Worker, with no double alert.
  - A real precondition race makes the backup's claim lose.
  - The daily cap works there too.
- **Not covered:** Google's own Apps Script runtime. The first `setup()` run is the real check.

**Quotas** (consumer account, free): URL Fetch 20,000 calls/day, triggers 90 min/day. This uses about 150 calls and 3 minutes a day.
