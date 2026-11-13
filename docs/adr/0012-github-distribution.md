# 0012: Distribution: a signed APK on GitHub Releases first, Google Play later

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

Google Play requires health apps, including blood-donation apps, to be published by an
**organization** account. LiquidLyf doesn't have one yet. F-Droid's main repository doesn't accept apps
that depend on Firebase and Google Play services. The project has no budget.

From 2027, Google's Android developer verification reaches more countries: certified phones will only
install apps, from any source, whose developer has registered with Google.

## Options considered

1. **Google Play first:** the easiest install for users, but it needs an organization account (a partner
   organization, or a D-U-N-S number), a review, and policy work before anyone can use the app.
2. **F-Droid:** trusted by open-source users, but not possible while the app needs Firebase.
3. **GitHub Releases first:** free, fully under the project's control, and the source and the APK sit
   side by side. Users must allow installing from their browser.

## Decision

v1 ships as a **signed universal APK on GitHub Releases**, with:
- a download page and install guide in English and Bangla at `liquidlyf.github.io/download`;
- update notifications in the app, and automatic updates through **Obtainium**;
- a published signing-certificate fingerprint, so anyone can check that an APK is genuine.

**The project holds its own signing key.** Google holds no copy. If Play is added later, it is enrolled
with "use my own key", so GitHub and Play builds share one signature and users can switch without
uninstalling. Google Play is a later, optional channel, through a partner organization's account.

## Consequences

- **Good:** no fee, no store review, and a release can go out as soon as it's ready.
- **Bad, or trade-offs:** installing takes a few extra taps, and Play Protect may show a warning. People
  may share fake copies, so the download page explains how to check the fingerprint. **If the signing
  key is lost or leaked, no existing install can ever be updated.**
- **Follow-ups:**
  - create the signing key and keep it in a password manager plus 2 offline backups (Phase 10.1);
  - register as an Android developer, with the app ID and key, before verification reaches
    Bangladesh in 2027 (Phase 10.2);
  - the [download page draft](../website-drafts/en/download.md).
