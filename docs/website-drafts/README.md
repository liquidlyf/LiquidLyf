# Website drafts

Drafts of the pages for the LiquidLyf website, **liquidlyf.github.io**. They move to the website repo
(`liquidlyf/liquidlyf.github.io`) when it's created. **Nothing here is published yet.**

| Page | English | Bangla | Final URL | Plan |
|---|---|---|---|---|
| Home page | [en/index.md](en/index.md) | [bn/index.md](bn/index.md) | `/`, `/bn/` | Phase 8.2 |
| Privacy Policy | [en/privacy.md](en/privacy.md) | [bn/privacy.md](bn/privacy.md) | `/privacy`, `/bn/privacy` | Phase 8.2 |
| Terms and Community Guidelines | [en/terms.md](en/terms.md) | [bn/terms.md](bn/terms.md) | `/terms`, `/bn/terms` | Phase 8.2 |
| Delete your LiquidLyf account | [en/delete-account.md](en/delete-account.md) | [bn/delete-account.md](bn/delete-account.md) | `/delete-account`, `/bn/delete-account` | Phase 8.2 |
| Download and install guide | [en/download.md](en/download.md) | [bn/download.md](bn/download.md) | `/download`, `/bn/download` | Phase 10.3 |

The request share page (`/r/`, v1.1) comes later. Until v1.0 is released, the home page shows
"Coming soon" instead of the download button.

**Status:** every page needs review before it goes live. The **Bangla pages need native review**: they
were translated from the English drafts. The English text is the reference until the Bangla is
checked.

## Placeholders

| Placeholder | Filled in when |
|---|---|
| `[PUBLISHER]`, `[COUNTRY OR POSTAL ADDRESS]` | Decision D1: the maintainer as a person, or a partner organization (Phase 10.2). The privacy policy must name whoever publishes the app |
| `[EFFECTIVE DATE]` | The day the page goes live |
| `[SHA-256 FINGERPRINT]` | After the release signing key exists (Phase 10.1) |
| `[QR CODE]` | An image of the download link |
| `[SCREENSHOT 1–3]` | Install steps on a real phone with fake data, one set in English, one in Bangla |

## Before publishing

1. **Match the real app.** Every stored field, menu name, consent text and deletion step must match the
   app at release (Phase 8). The data list in the privacy policy comes from `firebase/firestore.rules`
   and the retention table from the project plan. **If a pull request adds or changes stored data,
   update the privacy policy too.** The app opens these pages from `core/util/Links.kt` (`/privacy`,
   `/terms`, and `/bn/…`), and stores `Links.TERMS_VERSION` with each user's consent: change it
   whenever the effective date of the Terms or the Privacy Policy changes in a way users must accept
   again.
2. **Resolve each `CHECK` comment** (HTML comments, invisible on the page): backups, the Worker's
   clean-up job covering the moderators' action log (the job itself is live since 15 Nov 2026),
   App Check enforced (ADR 0003 test 6), the PDPA 2026
   rights and authority, the AppVerifier steps and the Obtainium link.
3. **Legal review:** Terms sections 9 (liability) and 11 (law), and the rights section of the privacy
   policy, by someone with legal training. Bangladesh's Personal Data Protection Act, 2026 applies.
4. **Native Bangla review** of all five Bangla pages, including medical and safety wording. Remove the
   "needs native review" banner afterwards.
5. **v1.1 features** (Telegram phone verification, approximate location) are in a hidden comment in the
   English privacy policy. Add them, in both languages, only when they ship.
6. **Google's rules for these pages:** each must load over HTTPS without a login, must not be a PDF, and
   must have a clear title ("Privacy Policy", "Delete your LiquidLyf account") that names the app
   exactly as it appears on the phone: **LiquidLyf**.

## Format

Each file is Markdown with front matter (`title`, `lang`, `permalink`, `alternate`), ready for GitHub
Pages with Jekyll. Two notes for the website repo:

- The v1.1 share page needs `/.well-known/assetlinks.json`. Jekyll skips folders that start with a dot,
  so add `include: [".well-known"]` to `_config.yml`. Don't add a `.nojekyll` file, because that stops
  the Markdown pages from being built.
- Markdown numbers lists with Western digits. To show Bangla digits (১, ২, ৩) in the Bangla pages, use
  CSS: `html[lang="bn"] ol { list-style-type: bengali; }`.
