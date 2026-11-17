---
title: Privacy Policy
lang: en
permalink: /privacy
description: What data LiquidLyf collects, who can see it, how long it is kept, and how to delete it.
alternate: /bn/privacy
status: draft, needs legal review
---

<!-- DRAFT. Placeholders in [BRACKETS] are listed in docs/website-drafts/README.md.
     CHECK comments mark facts to confirm before publishing. -->

# LiquidLyf Privacy Policy

**Effective date:** [EFFECTIVE DATE]

LiquidLyf is a free app that connects blood donors with people who need blood in Bangladesh. You trust
us with your phone number and your blood group, so we collect as little as we can, show it to as few
people as we can, and explain everything here in plain words.

<div class="glance" markdown="1">

## At a glance
- Your phone number is **never shown publicly**. A requester sees it only if you offer to donate for
  their request, and you see a requester's number only after you offer, while your offer is active.
- We don't sell your data, we don't show ads, and we don't use advertising or analytics trackers.
- You can delete your account and its data at any time, in the app or by email.

</div>

[CONTENTS]

## 1. Who we are

LiquidLyf is an open-source project run by volunteers. The app is published by **[PUBLISHER]**
([COUNTRY OR POSTAL ADDRESS]), who are responsible for your data under this policy.

**Privacy contact:** [liquidlyf@gmail.com](mailto:liquidlyf@gmail.com?subject=Privacy) (please write "Privacy" in the subject).

The app's source code is public at <https://github.com/liquidlyf/LiquidLyf>, so anyone can check what
it does with your data.

## 2. What we collect

### When you create an account
- Your email address, and how you sign in (email and password, Google, or both). If you use Google, Google
  shares your name, email address and profile picture link with us. We don't show the picture.
- Your password is handled by Google's Firebase Authentication. We never see it.
- That you are 18 or older and accepted these terms, with the date and the version you accepted.

### Your profile
- Full name, phone number, blood group.
- Gender and birth year. We use them only to check whether you can donate and to count your rest
  period after a donation. They are never shown to other users.
- Whether you weigh at least 45 kg (yes or no).
- Your area: division, district, and upazila or city corporation.
- Whether you are available to donate, and the date of your last donation.
- Whether you chose to be listed as a donor, and when you chose it.
- Your app language.

### When you use the app
- **Blood requests you post:** the patient's blood group, the blood component and number of units,
  the hospital and its area, how urgent it is, when the blood is needed, an optional condition (such as
  "surgery" or "dengue") and notes. The **contact person's name and phone number**, and optionally the
  patient's name and ward or bed, are kept separately and protected (see section 4).
- **Offers to donate:** your name as shown to others, your blood group, your phone number and an
  optional message, sent to the requester.
- **Your donation history:** date, blood component, hospital name, and whether it was confirmed through
  a request in the app.
- **Your in-app notifications,** the requests you chose to hide, and the people you blocked.
- **Badges:** "Rapid responder", if you offer to donate within 15 minutes of a request being posted.
- **Reports you send** about a request or a person: the reason and an optional note.
- **Counters** that protect against abuse, such as how many open requests you have, and the last
  donor you asked to donate (only you can see it).
- **When you last used the app,** updated at most once every 3 days.

### From your phone
- **Push notifications:** your phone subscribes to notification "topics" named after your area and
  blood group (for example, "Savar, B+"), through Google's Firebase Cloud Messaging. We don't
  store a device token. You can stop alerts at any time (section 7).
- **App Check:** Google Play services checks that requests come from the genuine LiquidLyf app on a
  real phone. This helps us spot bots and fake apps.
- **Crash reports:** if the app crashes, Firebase Crashlytics receives the technical details: phone
  model, Android version, app version, what the app was doing and a random installation ID. They
  contain no names, phone numbers or blood groups.
- **Daily checks:** about once a day, the app downloads two small files from GitHub: the hospital
  list, and the details of the newest app version, so it can tell you when an update is available. As
  with any download, GitHub sees your phone's IP address. The app sends nothing else about you or
  your account with these checks.

<!-- Daily checks: A32 item 4 = yes and D14 (the maintainer, 17 Nov 2026). Checked in A's code on 17 Nov
     2026, re-checked after A committed it (8907784): one silent daily worker does plain GETs of
     liquidlyf.github.io/data/hospitals.json (every build; only If-None-Match) and
     github.com/liquidlyf/LiquidLyf/releases/latest/download/update.json (the published prod app only),
     on the shared OkHttp client, which adds no headers; the sign-in and App Check tokens go only on the
     Worker's own calls (WorkerClient.kt). The staged-rollout number is a random ID that stays on the
     phone (never backed up or sent). Re-check whenever the daily check changes. -->

<!-- v1.1: add when the features ship.
- **Phone verification with Telegram:** if you choose to verify your number, Telegram shares your
  phone number and Telegram user ID with our bot. We store only "verified: yes". If you're listed
  as a donor, other signed-in users see a "Phone verified" badge.
- **Approximate location:** only if you allow it, only while the app is open, to sort donors and
  requests by distance. We store a rough area (about 5 km), never your exact position, and never show
  it to others. -->

**We don't collect** your contacts, photos, files, exact location, call logs or SMS messages. We never
ask for your national ID (NID).

## 3. Why we use it

| Purpose | Data used |
|---|---|
| Finding donors for a blood request, and alerting them | Blood group, area, availability, rest period, the request |
| Letting a donor and a requester call each other | Phone numbers, only after a donor offers |
| Counting your rest period and reminding you when you can donate again | Gender, birth year, last donation date |
| Running your account and showing the app in your language | Email, sign-in method, language |
| Keeping people safe: stopping scams, fake requests and harassment | Reports, blocks, counters, App Check, flagged words such as payment requests |
| Fixing crashes | Crash reports |

We use your data only for these purposes. Your data is never used for advertising and never sold.

## 4. Who can see what

| Your data | Who can see it |
|---|---|
| Full name | You. Others see only your first name and last initial ("Rahim K.") |
| Email address | Only you |
| Phone number | Only you, **plus** the requester of a request you offered to donate for |
| Blood group and area | Other signed-in users, if you chose to be listed as a donor or when you post or offer on a request |
| Whether you're available to donate, and when you last used the app | Other signed-in users, if you chose to be listed as a donor. Find donors shows only available donors, and shows "Active this week" instead of a date |
| Gender, birth year, weight answer | Only you. Others may see "Eligible now" or "Eligible in N days" |
| Last donation date | Only you. Others see only "Eligible now" or "Eligible in N days" |
| Donation history | You see all of it. Others see only how many times you donated |
| Your badges | Only you |
| A request's blood group, units, hospital, area, urgency and notes | Signed-in users with a verified email |
| A request's contact name and phone, patient name and ward | The requester, and donors whose offer for that request is still active or who donated. A donor loses access when they withdraw the offer or the requester declines it |
| Reports you send | Only the LiquidLyf moderators. The person you report isn't told who reported them |

<!-- Confirmed with session B, 15 Nov 2026 (d6b9d74): a request shows only how many donors were asked
     (askCount), never who. The last donor you asked, and for which request, is kept in your own
     userStats, which only you and moderators can read; it is deleted with your account. -->

**Moderators** are a small number of volunteers chosen by the maintainers. They can see account
details when they need to review a report or help with a support request, and their actions are logged.

**Please share only what's needed.** When you post a request for someone else, add the patient's
name and ward only if the patient or their family agrees.

## 5. Service providers

We use a few services to run LiquidLyf. They process data for us and only on our instructions.

| Provider | What it does for LiquidLyf | Where |
|---|---|---|
| Google Firebase: Authentication, Cloud Firestore, Cloud Messaging, Crashlytics, Remote Config, App Check | Sign-in, the database, notifications, crash reports, app settings, abuse protection | The database is in Mumbai, India. Sign-in, notifications and crash reports may be processed in other countries, including the United States |
| Cloudflare Workers | Sends the request alerts to donors. When your app asks it to, it receives your user ID, the request ID, the donor's user ID for a notification about an offer or an ask, and, like any web server, your IP address. It stores none of them. A maintainer fixing a problem can watch the calls live, with the IP address and its approximate location; that live view isn't saved. To stop abuse, Cloudflare counts each user's calls for 60 seconds, then forgets them. Its technical logs contain no names, phone numbers or user IDs | Cloudflare's worldwide network |
| GitHub | Hosts the app download, this website and the source code. It sees your IP address when you visit, and when the app does its daily checks (section 2) | United States and worldwide |

<!-- Confirmed 13 Nov 2026: the production Firestore database is in asia-south1 (Mumbai).
     Confirmed with session B, 13 Nov 2026 (Worker code at 2616359), re-checked 15 Nov 2026: KV holds the
     Worker's own Google access token (expires within 1 h) and two job cursors (timestamps, and the path
     of the last cleaned request), no personal data; a live `wrangler tail` shows callers' IP and
     approximate location only while a maintainer watches, and isn't saved; rate limiting is a 60-second per-uid counter in
     Cloudflare's Rate Limiting binding (or in memory), never written to storage; the Worker's log lines
     have no uid, names, phone numbers or tokens; automatic invocation logs are off. Re-check whenever
     the Worker changes. Cloudflare's log retention isn't stated on purpose: check its docs before
     adding a number. -->

Their own privacy policies: [Google](https://policies.google.com/privacy) ·
[Firebase](https://firebase.google.com/support/privacy) ·
[Cloudflare](https://www.cloudflare.com/privacypolicy/) ·
[GitHub](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).

Your data leaves Bangladesh when these services process it. We chose a database location close to
Bangladesh, and each provider protects data in transit and at rest.

We share data with authorities **only** when Bangladeshi law requires it, or to protect someone from
serious harm.

## 6. How long we keep it

| Data | How long |
|---|---|
| Your account and profile | Until you delete your account |
| An open request | Until it is fulfilled, cancelled or expires (one day after the "needed by" time) |
| Closed requests and offers | Visible to the people involved for 90 days. Then all contact details, donors' phone numbers, messages, notes and the reason for closing are deleted. What stays is the short names (such as "Rahim K."), blood group, area, date and outcome |
| Your donation history | Until you delete your account |
| Your past requests and offers, after you delete your account | Kept as history without your name, phone number, notes or messages, so other people's records stay complete ([details](/delete-account)) |
| In-app notifications | 60 days |
| Reports and moderators' action log | 1 year, to prevent repeat abuse |
| Crash reports | 90 days |
| Earlier versions of changed or deleted data | Up to 1 hour, readable only by the project's administrators. We keep no backups |

<!-- Backups, confirmed with session B on 15 Nov 2026 from the production admin API: none. Point-in-time
     recovery is off, there are no backup schedules or backups, and no export has ever run (exports and
     backups need billing, which Spark doesn't have). Firestore keeps earlier versions of changed or
     deleted documents for 1 hour (versionRetentionPeriod), readable only by project admins with a
     read-at-time query. The admin scripts write no files; the Apps Script backup and the Worker's KV
     hold no personal data. Re-check if billing, exports or backups are ever turned on.
     B6 answered 13 Nov 2026 (a): after 90 days closed requests keep short names, blood group, area,
     date and outcome; contact details, donor phones, messages, notes and the closing reason are deleted.
     Confirmed with session B, 15 Nov 2026: the Worker's clean-up job (backend/worker/src/retention.ts)
     is live in production, daily at 09:00 Dhaka (first run 15 Nov 2026 03:00 UTC, outcome ok, nothing
     old enough to remove yet). It handles closed requests after 90 days, inbox items after 60 days,
     reports after 1 year and, since Worker e8bc0d1d (ed89357, deployed 15 Nov 2026 04:27 UTC), the
     moderators' action log (adminLog) after 1 year. Re-check whenever the clean-up job changes. -->


## 7. Your choices and rights

- **See and correct** your profile at any time in the app.
- **Stop being listed as a donor:** Profile → ⚙ Settings → Privacy. Your donor card is removed at once, and you
  stop getting request alerts.
- **Pause alerts:** turn off "Ready to donate" on the Home screen or in Settings, or turn off
  notifications in Android settings.
- **Withdraw an offer** at any time before you donate. You then no longer see the request's contact
  details. The requester can still see the number you
  shared with that offer until it is deleted, 90 days after the request closes (section 6), and may
  have written it down.
- **Block someone:** you stop seeing their requests, and they can't ask you to donate or offer on
  your requests. If you block them from their offer on your request, that offer is declined too, so
  they stop seeing your request's contact details. Once their donation is recorded, the offer can't
  be declined.
- **Delete your account:** in the app under Profile → ⚙ Settings → Delete your account, or without the app at
  [liquidlyf.github.io/delete-account](/delete-account).
- **Get a copy of your data,** or ask any question about it: email [liquidlyf@gmail.com](mailto:liquidlyf@gmail.com?subject=Privacy) from the email
  address of your account. We reply within 30 days.

Bangladesh's Personal Data Protection Act, 2026 gives you rights to access, correct and erase your data,
to get a copy of it, and to withdraw consent. If you think we have handled your data wrongly, please
tell us first, so we can fix it. You can also complain to the data protection authority set up under
that Act.

<!-- CHECK the rights list and the authority's name against the official text of Act No. 63 of 2026. -->

## 8. Security

- All connections between the app, our services and Google use encryption (HTTPS/TLS).
- Database security rules decide who may read and write each piece of data. They are public in our
  source code, and automated tests check them on every change.
- App Check helps us recognise requests that don't come from the genuine app.
- Moderator access needs a special permission that only maintainers can grant.

<!-- CHECK (Phase 9): App Check runs, but isn't enforced yet: the Worker only logs a missing or invalid
     token (valid tokens in the A16 and A17 production tests, 15 Nov 2026). So section 2 says "spot" and
     this section "recognise". When the Worker and Firestore enforce App Check, they can say "stop"
     and "blocks" (EN and BN). -->

No system is perfect. If a breach puts you at risk, we will tell you in the app and by email, and
inform the authorities when the law requires it. To report a security problem, see our
[security policy](https://github.com/liquidlyf/LiquidLyf/security/policy).

## 9. Age limit

LiquidLyf is only for people aged **18 or older**. We don't knowingly collect data from children. If you
believe a child has an account, email us and we will delete it.

## 10. Changes to this policy

<!-- CHECK (session A, before the first change after launch): the app doesn't yet ask existing users to
     accept a new version (see the same note in terms.md §12). -->

When we change this policy, we update the effective date at the top. For important changes, the app
asks you to read and accept the new version. Earlier versions stay in the website's public history.

## 11. Contact

Email **[liquidlyf@gmail.com](mailto:liquidlyf@gmail.com?subject=Privacy)**, with "Privacy" in the subject. We reply within 30 days, usually much sooner.

**LiquidLyf is not an emergency service.** If someone needs blood urgently, contact the hospital or a
licensed blood bank now, or call 999.
