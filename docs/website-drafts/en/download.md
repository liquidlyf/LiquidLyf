---
title: Download LiquidLyf
lang: en
permalink: /download
description: Download LiquidLyf, the free blood-donor app for Android, and install it step by step.
alternate: /bn/download
status: draft
---

<!-- DRAFT for Phase 10.3, published only with build_site.py --released. Text and structure checked
     17 Nov 2026 against the plan's 10.3 table and the website's format rules. Still needs:
     [SHA-256 FINGERPRINT] (Phase 10.1), the [QR CODE] image, and [SCREENSHOT 1–3]. The screenshots are
     ON HOLD (the maintainer, 17 Nov 2026) until the UI/UX review's fixes are in the app: take them then,
     from the release build, with fake data, one set in English and one in Bangla. The links in the page
     footer (Privacy, Terms, Delete your account, GitHub, email) and its emergency line come from the
     layout, so this page doesn't repeat them. -->

# Download LiquidLyf

Free. No ads. Find blood donors near you, and get alerted when someone nearby needs your blood group.

<div class="cta"><a class="btn" href="https://github.com/liquidlyf/LiquidLyf/releases/latest/download/LiquidLyf.apk"><span class="ic ic-download" aria-hidden="true"></span>Download LiquidLyf (APK)</a></div>

This link always gives you the newest version. [All versions and release notes](https://github.com/liquidlyf/LiquidLyf/releases)

[QR CODE]

**You need:** an Android phone with **Android 7.0 or newer** and **Google Play services**, which
sign-in and notifications need. Phones without Google Play services, such as newer Huawei phones, aren't
supported.

## How to install

LiquidLyf comes from our website, not from the Play Store, so Android asks a few extra questions. It
takes about 2 minutes.

<!-- CHECK each step and its button names on a real phone before publishing (the realme on Android 11,
     and a newer phone), in English and in Bangla. Browsers and Play Protect change their wording often:
     write what the phones show. Phase 10 acceptance: from this page to signed in within 3 minutes. -->

1. **Download.** Tap the download button above. If your browser warns that this type of file can
   harm your device, tap **Download anyway**.

   [SCREENSHOT 1]

2. **Open the file.** Tap the download notification, or open your **Files** or **Downloads** app and
   tap `LiquidLyf.apk`.

3. **Allow installing.** The first time, Android says your browser isn't allowed to install apps. Tap
   **Settings**, turn on **Allow from this source**, then go back.

   [SCREENSHOT 2]

   On Android 7, turn on **Unknown sources** instead. On some phones with Android 8 or newer (for
   example realme, Xiaomi or Samsung) the switch is under Settings → Apps → Special app access →
   Install unknown apps.

4. **Install.** Tap **Install**.

5. **If Play Protect shows a warning,** tap **More details → Install anyway**.

   [SCREENSHOT 3]

   This warning appears because the app isn't from the Play Store. LiquidLyf is open source: anyone
   can read its code.

6. **Open LiquidLyf,** choose your language, and sign in with **Continue with Google** or with your
   email address.

## Is this the real LiquidLyf?

Only download LiquidLyf from this page or from
[our GitHub releases](https://github.com/liquidlyf/LiquidLyf/releases). A copy from anywhere else may be
fake, and could steal your data or ask you for money.

Every real copy is signed with the same key. The first line below is the app's ID, the second the
fingerprint of that key:

```
io.github.liquidlyf
[SHA-256 FINGERPRINT]
```

To check the LiquidLyf on your phone:

1. Install [AppVerifier](https://github.com/soupslurpr/AppVerifier), a free, open-source app.
2. Select the two lines above, tap **Share**, and choose **AppVerifier**.
3. AppVerifier compares them with the LiquidLyf on your phone and tells you whether they match.

If they don't match, uninstall that copy, download LiquidLyf again from this page, and tell us at
**[liquidlyf@gmail.com](mailto:liquidlyf@gmail.com)**.

<!-- CHECK AppVerifier on a real phone once the release key exists: that sharing the two lines from a
     browser opens its result (its README: "share the received verification info to AppVerifier"),
     and that the fingerprint's format matches its own (32 pairs of capital letters and digits, with
     colons). -->

For developers: each release also has `SHA256SUMS.txt` and a build attestation
(`gh attestation verify LiquidLyf.apk --repo liquidlyf/LiquidLyf`).

## Updates

- When a new version is out, the app shows an **Update available** card on its home screen. Tap
  **Get the update**: your browser opens the new version's page on GitHub. Download **LiquidLyf.apk**
  there and install it as before.
- If you use [Obtainium](https://obtainium.imranr.dev), it can install updates for you:
  [add LiquidLyf to Obtainium](obtainium://add/https://github.com/liquidlyf/LiquidLyf). If the link
  doesn't open Obtainium, open it yourself, tap **Add App**, paste
  `https://github.com/liquidlyf/LiquidLyf` and tap **Add**.

Each update installs over the old version. You keep your account and settings.

<!-- The card's labels are A's strings (1abaaad, 17 Nov 2026): update_available_title "Update available",
     update_available_action "Get the update"; tapping it opens the release page in the browser.
     CHECK after Phase 9: the card on a phone, and the installer's button for an update (Install or
     Update) on Android 7–14; that the first release
     has SHA256SUMS.txt and the attestation (Phase 9.2), and Obtainium's link and button names on a phone
     (its wiki, 17 Nov 2026: "obtainium://add/<url>" opens Add App with the URL filled in). -->

## Important

- **Never pay for blood.** LiquidLyf is free, and so is donating. If anyone asks you for money, don't
  pay, and report them in the app.
- **Not an emergency service.** If someone needs blood urgently, contact the hospital or a licensed
  blood bank now, or call [999](tel:999).

Questions about installing? Email **[liquidlyf@gmail.com](mailto:liquidlyf@gmail.com)**.
