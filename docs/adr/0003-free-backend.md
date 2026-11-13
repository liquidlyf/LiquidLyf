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
| "You can donate again" reminders | The phone itself, with local notifications |
| Moderator roles, bans | A local admin script and the Firebase console |
| Deleting an account | The app, which deletes its own data before deleting the sign-in account |

The Worker talks to Google with its own service account, **`liquidlyf-worker`**, which has exactly two
roles: **Cloud Datastore User** and **Firebase Cloud Messaging API Admin**. Its key is stored only as a
Cloudflare secret, never in git or on GitHub.

If the app outgrows the free limits, the fallback is Blaze with a hard spending limit, paid for by a
sponsor.

## Evidence: day-1 tests (Phase 2.0)

Tests 1 and 4 ran on 2026-11-13 with `backend/worker/scripts/spike.mjs`, against the production project
`liquidlyf-app`. Test 3 started on 2026-11-14 with the first deploy of the Worker.

Later on 2026-11-14, one real request was posted in production through the security rules. The live
Worker sent the alert, and it arrived on a test phone (a realme RMX2101 with Android 11, running a
production build). That gave the phone part of test 1 and the results of tests 2 and 3. **It is one
sample on one phone:** other phone makers, such as Xiaomi and Samsung, and mobile data are untested.

| # | Test | Result |
|---|---|---|
| 1 | FCM HTTP v1 from outside Google, on a Spark project | **Passes on one phone.** Server side, from a PC using the same code as the Worker: Google sign-in token in 282 ms; an FCM topic message accepted (validate-only) in 427 ms; a Firestore read allowed in 661 ms. On the phone: a data-only, high-priority message to an area and blood-group topic arrived after Android had stopped the app. The message started the app, and the notification showed. Other phone makers are untested |
| 2 | Topic delivery time, from posting to the notification (target under 30 s) | **Passes on one phone: about 4 s.** The request was written through the rules in 134 ms. The alert call to the Worker, which had to start up, took 2.7 s. FCM began sending about 0.5 s after the Worker answered, and the phone received the message 543 ms later. The phone's, Cloudflare's and Google's clocks agree; the test PC's clock was about 1.2 s slow, and was corrected for. **To repeat in the beta,** on mobile data and on more phones |
| 3 | The Worker on Cloudflare's free plan: deploy, CPU and subrequest use, no card asked for | **Passes, with a margin to watch.** Deploy: the project's Cloudflare account is on the Free plan, and no card was asked for (the dashboard shows 100,000 requests a day). The Worker deployed at 7.02 KB gzipped, with a 1 ms startup time. Its KV store and the rate limit (10 calls per user per 60 s) both work on the Free plan. Checked live from Bangladesh: `/health` answers in 0.22 s, and an unauthenticated alert request is refused (401) in 0.08 s, before any Firestore read. A real alert, on a cold start: **7 ms of CPU time, of the 10 ms limit**, and **5 of 50 subrequests** (Google token, Google's sign-in keys, a Firestore read, a Firestore write, FCM), 2.7 s in total. **Not measured yet:** a warm alert, and an alert that carries an App Check token |
| 4 | Project checks | **Pass.** `liquidlyf-app` is on Spark. Authentication isn't upgraded to Identity Platform. The Firestore `(default)` database is in `asia-south1` (Mumbai), Standard edition, production mode ([0008](0008-firestore-region.md)) |
| 5 | App Check debug provider and token lifetime | **Pending** |
| 6 | App Check for an app outside Google Play, with a release-signed APK on a real phone | **Pending.** The live test sent no App Check token (for now the Worker only logs a missing token), so the result will come from the app's first real calls |

**Lesson from test 1:** the role named "Firebase Cloud Messaging **Admin**"
(`roles/firebasenotifications.admin`) looks right but can't send messages: it fails with
`403 cloudmessaging.messages.create`. The correct role is "Firebase Cloud Messaging **API** Admin"
(`roles/firebasecloudmessaging.admin`).

**Lesson from test 4:** when Authentication is set up, enable only Email/Password and Google, and never
press "Upgrade" to Identity Platform. That upgrade limits the free plan to 3,000 daily active users.

**Margin to watch from test 3:** a cold start uses the most CPU, because it creates the Worker's Google
token and fetches Google's keys to check the user's sign-in token. Warm calls skip both, so they should
use much less, but that isn't measured yet. An App Check token adds one more signature check to the
cold start. If that brings a cold alert close to the 10 ms limit, record it here as a risk.

## Consequences

- **Good:** nothing can bill the project; no server to maintain; every rule that protects data is in
  `firebase/firestore.rules` and tested in CI.
- **Bad, or trade-offs:**
  - Security rules carry logic that would normally live in server code, so they are long and need
    many tests.
  - The free plan allows 50,000 Firestore reads a day, which caps the app at roughly 9,000–15,000 users
    unless the read-budget rules in [docs/architecture.md](../architecture.md) are followed.
  - The Worker's service-account key bypasses the security rules. Only 1–2 maintainers hold it, with
    two-step verification, and it is rotated yearly.
- **Follow-ups:**
  - measure CPU time for a warm alert and for one with an App Check token (test 3) once the app's
    screens send alerts; run tests 5 and 6 when App Check is set up. Update this record with the
    results;
  - repeat tests 1 and 2 in the beta, on mobile data and on phones from other makers;
  - if test 1 or 3 fails, write a new record before building more on this design.
