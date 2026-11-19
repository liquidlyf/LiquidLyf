# Beta checklist

The beta runs for 1–2 weeks before v1.0, with 15–20 real people in one or two districts. Testers get
**pre-releases** (`v1.0.0-beta1`, `v1.0.0-beta2`, …) with the **same app ID and signing key** as
v1.0, so they update straight into v1.0 and keep their accounts.

Copy this into a GitHub issue and tick it there ([how](README.md)).

## Before the first pre-release

- [ ] 👤 The release identity is done: the signing key, its fingerprints in Firebase, and the
      `production-android` environment ([launch checklist, step 1](launch-checklist.md#1-release-identity)).
      Never give testers a build signed with any other key.
- [ ] The release notes are committed **before the tag**, as `docs/release-notes/<tag>.md`, in English
      and Bangla, with the Bangla meaning-checked and reviewed ([how](../release-notes/README.md)).
- [ ] A `v1.0.0-betaN` tag (`v1.0.0-beta1`, with no dot before the number: the release workflow
      refuses other forms) makes a **draft** release with `LiquidLyf.apk`, `SHA256SUMS.txt`,
      `update.json` and a build attestation, and CI's fingerprint check passes. The draft shows the
      notes file, not the "Release notes to come" placeholder.
- [ ] A maintainer installs the draft's APK on a real phone, checks sign-in and notifications, then
      publishes it **as a pre-release**.
- [ ] The beta districts' hospitals are in the app's built-in hospital list.
- [ ] 👤 1–2 moderators are ready: `setAdmin.mjs` gives them the `admin` claim
      ([admin scripts](../../backend/admin-scripts/README.md)). After signing out and in, their Settings
      shows "Moderation" and their phone gets new-report alerts.
- [ ] Feedback channels are ready: a feedback form, a WhatsApp group for testers, and the
      [GitHub issue forms](../../.github/ISSUE_TEMPLATE/).
- [ ] Testers are found: 15–20 real users in one or two districts (for example a campus and Dhaka),
      from the university, blood-donor clubs and family.
- [ ] Every tester is told:
  - it's a test, and the app may change;
  - **never pay for blood**, and report anyone who asks for money;
  - **LiquidLyf is not an emergency service**: in an emergency, contact the hospital or a blood bank,
    or call 999.

## Giving testers the app

- The website still says "Coming soon" until v1.0, so send testers the pre-release's link directly.
- Obtainium users turn on **Include prereleases** for LiquidLyf.
- The install steps are the same as the download page's guide (`docs/website-drafts/en/download.md`
  and `bn/download.md`), which goes on the website only at v1.0. Send testers its text, or walk them
  through it.

## While the beta runs

- [ ] **Every day:**
  - free quotas: Firestore reads and writes, Worker requests, App Check calls
    ([limits](operations.md#the-free-quotas));
  - the reports queue (Settings → Moderation);
  - crash reports;
  - the testers' group and the feedback form.
- [ ] **Fix with a new pre-release** (`-beta2`, …), each with its own notes file. The `versionCode`
      comes from the tag, so each beta's is higher than the last. Android can't install a lower
      version, so there is no rollback: fix forward.
- [ ] **App Check:** switch it to **enforce** once its metrics show that almost all requests come
      from the real app.

## Ready for v1.0 when

- [ ] A week of beta use stays under **70% of every free quota**.
- [ ] No crashes during a 2-day test with 3–5 people.
- [ ] At least **one real donation** was completed through the app in a beta district.
- [ ] Every bug that blocks a request, an offer or a donation is fixed, and the fixes were tested in
      a pre-release.

Then go on with the [launch checklist](launch-checklist.md).
