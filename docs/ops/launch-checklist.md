# Launch checklist (v1.0)

v1.0 is a signed APK on [GitHub Releases](https://github.com/liquidlyf/LiquidLyf/releases), with
the download page at [liquidlyf.github.io/download](https://liquidlyf.github.io/download). It
launches district by district, not everywhere at once.

Copy this into a GitHub issue and tick it there ([how](README.md)). Steps 1 and 2 start long before
launch day.

## 1. Release identity

👤 Before anyone outside the team installs a build.

- [ ] The app ID is final: **`io.github.liquidlyf`**. Never change it. Once strangers have the app,
      a new ID forces everyone to uninstall and reinstall.
- [ ] The **signing key** exists. It *is* the app's identity, and Google holds no copy:
      ```
      keytool -genkeypair -v -keystore liquidlyf-release.jks -alias liquidlyf -keyalg RSA -keysize 4096 -validity 10000
      ```
  - Keep the keystore **outside the repo**.
  - Store it in 3 places: the project password manager, shared with a second maintainer, and 2
    offline backups (for example two USB drives kept in different places).
  - **If the key is lost or leaked, no installed copy can ever be updated.** Everyone would have to
    uninstall and reinstall.
- [ ] The key's **SHA-1 and SHA-256** are in the production Firebase Android app (Project settings →
      Your apps). Without them, Google sign-in fails.
- [ ] The keystore (base64) and its passwords are in the `production-android` GitHub environment:
      tags only, with a required reviewer.
- [ ] The key's **SHA-256** is published in the repo README and on the download page, written the way
      AppVerifier writes it (32 pairs of capital letters and digits, with colons).

## 2. Android developer verification

👤 Google is rolling out a rule that certified phones only install apps from registered
developers. It started in some countries on 30 Sept 2026 and reaches the rest of the world in 2027.
After it reaches Bangladesh, an APK from an unregistered developer needs a one-time "advanced"
flow that most people won't complete.

- [ ] Decide who registers:
  - a maintainer, as an individual, with a government ID (NID or passport); or
  - a partner organization, with a free D-U-N-S number (it can take ~30 days).

  To Google, whoever registers owns the app.
- [ ] Create a **full-distribution** Android Developer Console account (US$25 once), with the
      project's Google account and 2-step verification on. The free limited-distribution account
      allows only 20 devices: not enough.
- [ ] Register the app ID and the signing key.
- [ ] Follow [Google's rollout page](https://support.google.com/android-developer-console/answer/16561738)
      for Bangladesh's date, and finish **at least a month before it**.

## 3. Remove test data from production

Before the launch announcement.

- [ ] Delete every test account made on the production project, with all its data: in the app
      (Profile → ⚙ Settings → Delete your account), or with
      `backend/worker/scripts/live-request.mjs --email <test email> --delete-account`, or with
      `deleteUserData.mjs` ([admin scripts](../../backend/admin-scripts/README.md)).
- [ ] **Delete** every test post in the Firestore console, not just close it, with its offers, inbox
      items, donation records, reports and admin-log entries. The maintainers keep the list of test IDs
      (outside the repo).
- [ ] A maintainer's real account keeps its real history, but loses its test-only history.
- [ ] Check in the Firestore console that nothing test-only is left: requests, reports, admin log,
      donors.
- [ ] `listAdmins.mjs` shows only the moderators you want. Remove any others with
      `setAdmin.mjs --remove`.

## 4. The website and the README

- [ ] The download page's Bangla is reviewed by a native speaker, like the other pages.
- [ ] Every `CHECK` note in `docs/website-drafts/` is resolved (`build_site.py` lists them). For the
      download page, that means:
  - each install step, checked on a real phone set to Bangla and on one set to English;
  - AppVerifier and Obtainium, tested on a phone;
  - the update card's exact label.
- [ ] Screenshots from the **release** build with fake data: the install steps in English and Bangla,
      and the home page. They are WebP, with width and height set.
- [ ] The QR code (for posters) and the key fingerprint are on the download page.
- [ ] The site is built with `--released` ([how](../website-drafts/README.md#publishing)), committed
      and pushed.
- [ ] In a private window on a phone, in English and Bangla: the download button gives the APK, and
      the guide matches what the phone shows.
- [ ] On a fresh phone, someone following the **Bangla** guide gets from the page to signed in
      **within 3 minutes**.
- [ ] The repo README has the download button, "Get it on GitHub" and "Get it on Obtainium" badges,
      the key fingerprint, and screenshots.

## 5. Release day

- [ ] `docs/release-notes/v1.0.0.md` is committed **before the tag**, in English and Bangla, with the
      Bangla meaning-checked and reviewed ([how](../release-notes/README.md)).
- [ ] The `v1.0.0` tag makes a draft release with `LiquidLyf.apk`, `SHA256SUMS.txt`, `update.json`
      and a build attestation, and CI's fingerprint check passes. The draft shows the notes file, not
      the "Release notes to come" placeholder.
- [ ] A maintainer installs the draft's APK on a real phone, both as a fresh install and as an update
      over the last beta. Sign-in, a request, an offer and the notifications all work.
- [ ] Publish it as the **latest** release.
- [ ] `https://github.com/liquidlyf/LiquidLyf/releases/latest/download/LiquidLyf.apk` downloads
      v1.0.0.
- [ ] Announce it in the first districts (step 6).

## 6. District by district

Start with the beta districts, then add one district at a time. That protects the free quotas, and
keeps enough donors near the hospitals where requests happen.

Announce it:
- on the university's page, the partner organization's page and blood-donor groups;
- on LiquidLyf's [Facebook page](https://www.facebook.com/liquidlyf);
- on posters with the QR code, at blood banks, hospitals and campuses;
- through the app's own share feature.

Every announcement says: never pay for blood, and LiquidLyf is not an emergency service.

## 7. The first week

- [ ] Crash-free users stay **at 99% or more**.
- [ ] **Halt if** crash-free users drop below 99%, or any free quota passes 70%: stop promoting, fix,
      and release a higher version.
- [ ] Publish a small test patch release: Obtainium and the in-app update card both pick it up.
- [ ] Later releases use the **staged rollout** in `update.json`: `rolloutPercent` 20, then 50, then
      100, over about a week. Obtainium users get every release at once.

## 8. After the `rebuild` → `main` merge

The website links to the repo's default branch, `main`. Until `rebuild` is merged, those links show
the university version (v0). After the merge, open each link on liquidlyf.github.io, in English and
Bangla:

- [ ] Terms → the licence shows **AGPL-3.0**, not v0's MIT.
- [ ] Terms → TRADEMARKS, and Home → how to contribute, open (no 404).
- [ ] The repo's front page shows the new README.

Then go on with [running it](operations.md).
