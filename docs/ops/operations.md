# Running LiquidLyf after launch

What the maintainers check, and how often. Everything runs on free plans, so watching the quotas is
part of the job.

## Every day

- **Reports:** review new reports in the app (Settings → Moderation) within **24 hours**. Moderators'
  phones get an alert when there are new ones. To suspend an account, use `revokeAndBan.mjs`
  ([admin scripts](../../backend/admin-scripts/README.md)).
- **Email** to liquidlyf@gmail.com, and messages to the [Facebook page](https://www.facebook.com/liquidlyf).
  - **Deletion requests** sent by email or through the website: `deleteUserData.mjs`
    ([how](../../backend/admin-scripts/README.md#how-to-handle-each-case)). Reply when it's done, and
    keep the email thread as the record.
  - **Privacy requests** (a copy of someone's data, corrections): answer within 30 days, as the
    Privacy Policy promises.
  - **Security reports** follow [SECURITY.md](../../SECURITY.md).
- **Crashes:** glance at Crashlytics.

## Every week

- **Crash-free users:** the target is **99.5% or more**.
- **Free quotas:** Firestore reads and writes, Worker requests and App Check calls, against
  [the limits below](#the-free-quotas).
- **GitHub:** triage new issues and Discussions.
- **Downloads:** the release download counts (GitHub API).

## Every month

- **Updates:** library and security updates (Dependabot), with a patch release if one is needed.
- **Privacy Policy:** check it still matches the app. Every new or changed stored field, and every
  retention change, must be in the Privacy Policy ([the website's rules](../website-drafts/README.md#before-publishing)).

## On the calendar

- **Before Bangladesh's 2027 date:** Android developer verification is done
  ([launch checklist, step 2](launch-checklist.md#2-android-developer-verification)).
- **Every year:** raise `targetSdk` to the newest Android version. Off Play nobody enforces it, but
  newer Android versions warn about apps that target old versions, or block them.
- **Bangladesh's Personal Data Protection Act, 2026:** follow when each part comes into force, and
  update the Privacy Policy when needed.
- **Every year:** review the Terms and the moderation numbers.
- **Every January:** check that the website footer shows the new year ("© 2025–<this year>", with
  Bangla digits on the Bangla pages). If it doesn't, open the website repo's Actions → "Yearly
  rebuild", then **Enable workflow** (GitHub switches scheduled jobs off after 60 days without
  activity), then **Run workflow**.

## The free quotas

| Limit (free) | Roughly enough for |
|---|---|
| Firestore: **50,000 reads a day**, the tightest limit | ~9,000 registered users |
| Firestore: 20,000 writes a day | ~60,000 registered users |
| Firestore: 1 GiB stored | ~10,000 users for over a year, with the clean-up job |
| App Check (Play Integrity): 10,000 calls a day | ~5,000–10,000 daily users (tokens last 12–24 h) |
| Cloudflare Worker: 100,000 requests a day | Plenty: about 2 calls per blood request, plus the scheduled jobs |
| GitHub Pages: 1 GB site, ~100 GB a month | The website only; the APK downloads come from GitHub Releases |

- **When a Firebase quota runs out,** Firebase switches that service off until the daily reset,
  around 13:00–14:00 Bangladesh time (midnight US Pacific time). It never charges anything, but
  until the reset the app can't load or save new data.
- **Halt at 70%:** if any quota passes 70%, stop promoting the app in new districts until it's
  fixed.
- **If it passes 70% on most days** (around 7,000–12,000 users), the app has outgrown the free plan.
  The options are: a paid plan with a hard spending cap, paid by a partner or sponsor; a second free
  project for another region; or free credits for nonprofits and open-source projects. The
  maintainers decide then.

## The Worker's scheduled jobs

They run by themselves. If one fails, the Worker's logs show it, and the next run catches up. They
are: the escalation sweep every 5 minutes, request expiry every hour, the daily clean-up at 09:00
Bangladesh time, and the new-report alert. Their details are in the
[Worker's README](../../backend/worker/README.md#crons-phases-5-and-7-wranglerjsonc--triggers-times-in-utc).
