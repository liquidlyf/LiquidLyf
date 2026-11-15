# 0003: Free backend: Firebase Spark plan + a Cloudflare Worker, no Cloud Functions

- **Status:** Accepted. Some test results are still pending (see "Evidence").
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

LiquidLyf has no budget and no company behind it. It must run at **US$0 a month, with no payment card
on file**, so that nothing can ever send a bill.

The first design used Cloud Functions for server work:
- sending alerts to donors;
- widening the search step by step;
- expiring old requests;
- keeping counters correct.

Cloud Functions, phone sign-in by SMS and new Cloud Storage buckets need Firebase's paid **Blaze** plan.
The free **Spark** plan has Authentication, Firestore, Cloud Messaging (FCM), App Check, Crashlytics and
Remote Config, but no server code.

## Options considered

1. **Blaze with Cloud Functions:** the standard design, and cheap at small scale, but it needs a card,
   and a mistake or an attack could cost real money.
2. **A rented server:** full control, but a monthly cost and a machine to maintain.
3. **Spark plus a free Cloudflare Worker:** the app writes to Firestore directly, with security rules
   checking every write. A small Worker on Cloudflare's free plan does the few jobs that need a trusted
   server.

## Decision

The backend is **Firebase Spark plus one Cloudflare Worker on the free plan**. There are no Cloud
Functions, no paid SMS and no Blaze plan.

| Job | Done by |
|---|---|
| Counters, donor cards, offers, reports, "donated" | The app, in batched writes that the **Firestore rules** check field by field |
| Sending alerts to donors, tier by tier | The **Worker**, through FCM **topics** (one message per area and blood group, no device tokens stored) |
| Widening the search, expiring requests, cleaning up | The Worker's **scheduled jobs** (every 5 minutes, hourly, daily) |
| A backup for widening the search, if the Worker's 5-minute job stops | A free **Google Apps Script** (`backend/apps-script/`). Every 10 minutes it looks for requests more than 10 minutes overdue and runs the Worker's own escalation code, so the next round of alerts goes out at most 20 minutes after it was due |
| "You can donate again" reminders | The phone itself, with local notifications |
| Moderator roles, bans | A local admin script and the Firebase console |
| Deleting an account | The app, which deletes its own data before deleting the sign-in account |

The Worker talks to Google with its own service account, **`liquidlyf-worker`**, which has exactly two
roles: **Cloud Datastore User** and **Firebase Cloud Messaging API Admin**. Its key is stored only as a
Cloudflare secret, never in git or on GitHub. The Apps Script backup has its **own** service account,
`liquidlyf-backup`, with the same two roles; its key is stored only in the script's properties. The
backup has no public web endpoint (decided 2026-11-14).

If the app outgrows the free limits, the fallback is Blaze with a hard spending limit, paid for by a
sponsor.

## Evidence: day-1 tests (Phase 2.0)

Tests 1 and 4 ran on 2026-11-13 with `backend/worker/scripts/spike.mjs`, against the production project
`liquidlyf-app`. Test 3 started on 2026-11-14 with the first deploy of the Worker.

Later on 2026-11-14, one real request was posted in production through the security rules. The live
Worker sent the alert, and it arrived on a test phone (a realme RMX2101 with Android 11, running a
production build). That gave the phone part of test 1 and the results of tests 2 and 3. **It is one
sample on one phone:** other phone makers, such as Xiaomi and Samsung, and mobile data are untested.

On 2026-11-15, the production app itself was tested on the same phone: posting a request, then an
offer, confirming it and recording the donation. That added a second delivery time (test 2), CPU numbers
for the app's own calls (test 3), and the first try of test 6. Later that day, after App Check was set
up in the Firebase console, test 6 passed, and the Apps Script backup was tested (see below the table).

| # | Test | Result |
|---|---|---|
| 1 | FCM HTTP v1 from outside Google, on a Spark project | **Passes on one phone.** Server side, from a PC using the same code as the Worker: Google sign-in token in 282 ms; an FCM topic message accepted (validate-only) in 427 ms; a Firestore read allowed in 661 ms. On the phone: a data-only, high-priority message to an area and blood-group topic arrived after Android had stopped the app. The message started the app, and the notification showed. Other phone makers are untested |
| 2 | Topic delivery time, from posting to the notification (target under 30 s) | **Passes on one phone, twice: about 4 s.** First run (2026-11-14, from a script): the request was written through the rules in 134 ms. The alert call to the Worker, which had to start up, took 2.7 s. FCM began sending about 0.5 s after the Worker answered, and the phone received the message 543 ms later. The phone's, Cloudflare's and Google's clocks agree; the test PC's clock was about 1.2 s slow, and was corrected for. Second run (2026-11-15, from the production app): about 4.3 s from the tap that posted the request to the alert on the phone, with 294 ms from FCM to the phone. **To repeat in the beta,** on mobile data and on more phones |
| 3 | The Worker on Cloudflare's free plan: deploy, CPU and subrequest use, no card asked for | **Passes, with a risk to fix: the alert call has gone over the 10 ms CPU limit.** Deploy: the project's Cloudflare account is on the Free plan, and no card was asked for (the dashboard shows 100,000 requests a day). The Worker deployed at 7.02 KB gzipped, with a 1 ms startup time. Its KV store and the rate limit (10 calls per user per 60 s) both work on the Free plan. Checked live from Bangladesh: `/health` answers in 0.22 s, and an unauthenticated alert request is refused (401) in 0.08 s, before any Firestore read. A real alert, on a cold start: **7 ms of CPU time, of the 10 ms limit**, and **5 of 50 subrequests** (Google token, Google's sign-in keys, a Firestore read, a Firestore write, FCM), 2.7 s in total. From the production app (2026-11-15): the alert took **8 ms** and 1.7 s, as the Worker's first request after a deploy, so it includes start-up. The app's three other notifications (new offer, offer confirmed, donation recorded) took **2 ms** each and 0.6–0.8 s: each checks the sign-in token, reads Firestore once and sends one FCM message. The 5-minute escalation job took 4 ms on its first run after a deploy, with nothing due. Later on 2026-11-15: two new-offer notifications that carried a valid App Check token took **5 ms** and **9 ms** (the second also records a badge). Six alert calls from test scripts, without an App Check token, took **5–12 ms**, twice **12 ms**: over the limit. Cloudflare tolerated it, and every call ended "ok". Other notifications took 2–9 ms, the timed jobs 0–8 ms. **Not known:** which of these calls ran on a cold Worker; the live log doesn't say |
| 4 | Project checks | **Pass.** `liquidlyf-app` is on Spark. Authentication isn't upgraded to Identity Platform. The Firestore `(default)` database is in `asia-south1` (Mumbai), Standard edition, production mode ([0008](0008-firestore-region.md)) |
| 5 | App Check debug provider and token lifetime | **Partly done.** The project sets App Check tokens to last 12 hours; how long they really last on a phone isn't measured yet. The debug provider hasn't been tried against production: `demo` builds use the local emulators, without App Check |
| 6 | App Check for an app outside Google Play, with a release-signed APK on a real phone | **Passes (checked on the server).** The first try, early on 2026-11-15, sent no token: in `liquidlyf-app` the Play Integrity API and the App Check API were off, and no fingerprint was registered. After that setup (both APIs on; the Play Integrity provider, allowing versions from outside Google Play and requiring device integrity; the app's SHA-1 and SHA-256 registered), the Worker verified the app's token on its first call, at 03:13 UTC, and again at 07:53 UTC. App Check isn't enforced yet: the Worker only logs a missing or invalid token. The test build was installed outside Google Play; which key signed it is still to be confirmed. The real release key's fingerprint must be registered too, once that key exists (Phase 10.1) |

**Lesson from test 1:** the role named "Firebase Cloud Messaging **Admin**"
(`roles/firebasenotifications.admin`) looks right but can't send messages: it fails with
`403 cloudmessaging.messages.create`. The correct role is "Firebase Cloud Messaging **API** Admin"
(`roles/firebasecloudmessaging.admin`).

**Lesson from test 4:** when Authentication is set up, enable only Email/Password and Google, and never
press "Upgrade" to Identity Platform. That upgrade limits the free plan to 3,000 daily active users.

**Risk from test 3:** the alert call is the Worker's heaviest. It checks the user's sign-in token,
reads and updates the request, and sends the alert to the right topics; on a cold start it also
creates the Worker's Google token and fetches Google's keys. Alerts used 7–8 ms at first, then up to 12 ms
twice, above the free plan's 10 ms limit. Cloudflare allows an occasional overrun, but if overruns
become common, alerts can fail. The alert call must use less CPU before the beta (see the follow-ups).
Calls with an App Check token stayed within the limit (5 and 9 ms).

**Backup test (2026-11-15):** only the Worker's 5-minute job was switched off; the Worker kept sending
first alerts and notifications. A request was posted without its first alert, so only the escalation
could send it. The Apps Script backup sent it, and the phone received it **12 min 42 s** after the
request was posted, 0.34 s after the script sent it. That is the backup's 10-minute wait for an
overdue request, plus 2.7 minutes until its next 10-minute run. The slowest possible case is 20
minutes. The Worker's job was switched back on a minute after the alert arrived.

## Consequences

- **Good:** nothing can bill the project; no server to maintain; every rule that protects data is in
  `firebase/firestore.rules` and tested in CI.
- **Bad, or trade-offs:**
  - Security rules carry logic that would normally live in server code, so they are long and need
    many tests.
  - The free plan allows 50,000 Firestore reads a day, which caps the app at roughly 9,000–15,000 users
    unless the read-budget rules in [docs/architecture.md](../architecture.md) are followed.
  - The Worker's and the backup's service-account keys bypass the security rules. Only 1–2
    maintainers hold them, with two-step verification, and they are rotated yearly.
- **Follow-ups:**
  - make the alert call use less CPU, then measure it again, cold and warm (test 3), before the beta;
  - measure how long an App Check token lasts on a phone (test 5); enforce App Check in the Worker
    and Firestore (Phase 9); register the release key's fingerprint when it exists (Phase 10.1);
  - repeat tests 1 and 2 in the beta, on mobile data and on phones from other makers;
  - if test 1 or 3 fails, write a new record before building more on this design.
