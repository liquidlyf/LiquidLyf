# Website pages

The source of the LiquidLyf website, **liquidlyf.github.io**, in English and Bangla. The pages are
written here, next to the code, so that a pull request that changes the app's data practices changes
the Privacy Policy in the same review. [`build_site.py`](build_site.py) publishes them into the website
repo (`liquidlyf/liquidlyf.github.io`), which GitHub Pages serves.

| Page | English | Bangla | URL | Plan |
|---|---|---|---|---|
| Home page | [en/index.md](en/index.md) | [bn/index.md](bn/index.md) | `/`, `/bn/` | Phase 8.2 |
| Privacy Policy | [en/privacy.md](en/privacy.md) | [bn/privacy.md](bn/privacy.md) | `/privacy`, `/bn/privacy` | Phase 8.2 |
| Terms and Community Guidelines | [en/terms.md](en/terms.md) | [bn/terms.md](bn/terms.md) | `/terms`, `/bn/terms` | Phase 8.2 |
| Delete your LiquidLyf account | [en/delete-account.md](en/delete-account.md) | [bn/delete-account.md](bn/delete-account.md) | `/delete-account`, `/bn/delete-account` | Phase 8.2 |
| Download and install guide | [en/download.md](en/download.md) | [bn/download.md](bn/download.md) | `/download`, `/bn/download` | Phase 10.3 |

Until v1.0 is released, `/download` is a short "coming soon" page
([site/prerelease/](site/prerelease/)), and the home page says "Coming soon" instead of showing the
download button. The request share page (`/r/`, v1.1) comes later.

**Bangla:** the pages were translated from the English ones and need a native review (C8). Until then,
the site shows a notice on every Bangla page that the English version applies if the two differ. After
the review, set `bn_reviewed: true` in [site/_config.yml](site/_config.yml).

## Publishing

1. Resolve what the page needs (see "Before publishing").
2. Build into a clone of the website repo, from the app repo's root:

   ```bash
   python docs/website-drafts/build_site.py ../liquidlyf.github.io --date YYYY-MM-DD \
       --publisher "…" --address "…" --address-bn "…"
   ```

   `--date` is the effective date shown on the Privacy Policy and the Terms. Add `--released` once
   v1.0 is on GitHub Releases. The script removes every HTML comment (the notes below never reach the
   site), stops if a `[PLACEHOLDER]` has no value, and lists the `CHECK` notes still open. It never
   deletes files in the website repo; it lists the ones it didn't write.
3. Commit in the website repo (`git commit -s`) and push. GitHub Pages rebuilds the site in a minute or two.
4. Open each page in a private window, in English and Bangla, on a phone.
5. If the Terms or the Privacy Policy changed in a way users must accept again, change
   `Links.TERMS_VERSION` in the app (`core/util/Links.kt`), so the app asks them.

[`site/`](site/) holds the rest of the website: `_config.yml`, the page layout, the stylesheet, the
404 page and the website repo's README. The logo files come from [`branding/`](../../branding/). The
site has no scripts, cookies, analytics or web fonts, and a Content Security Policy allows nothing
from other sites.

## Placeholders

| Placeholder | Filled in when |
|---|---|
| `[PUBLISHER]`, `[COUNTRY OR POSTAL ADDRESS]` | Decided by the maintainer (C11). The privacy policy must name whoever is responsible for the data |
| `[EFFECTIVE DATE]` | By `build_site.py --date`, on the day the page goes live or changes |
| `[SHA-256 FINGERPRINT]` | After the release signing key exists (Phase 10.1) |
| `[QR CODE]` | An image of the download link (Phase 10.3) |
| `[SCREENSHOT 1–3]` | Install steps on a real phone with fake data, one set in English, one in Bangla (Phase 10.3) |

## Before publishing

1. **Match the real app.** Every stored field, menu name, consent text and deletion step must match the
   app. The data list in the privacy policy comes from `firebase/firestore.rules`, and the retention
   table from the Worker's clean-up job. **If a pull request adds or changes stored data, update the
   privacy policy too.** The app opens these pages from `core/util/Links.kt` (`/privacy`, `/terms`,
   `/download` and the `/bn/…` versions).
2. **Resolve each `CHECK` note** (HTML comments; `build_site.py` lists them): backups, App Check
   enforced (Phase 9), the PDPA 2026 rights and authority, the Terms' governing law, and, for the
   download page, the AppVerifier steps and the Obtainium link.
3. **Legal review (C7):** Terms sections 9 (liability) and 11 (law), and the privacy policy's
   publisher, rights and data-transfer sections, by someone with legal training. Bangladesh's Personal
   Data Protection Act, 2026 applies.
4. **Native Bangla review (C8)** of all five Bangla pages, including medical and safety wording.
5. **v1.1 features** (Telegram phone verification, approximate location) are in a hidden comment in the
   English privacy policy. Add them, in both languages, only when they ship.
6. **Google's rules for these pages:** each must load over HTTPS without a login, must not be a PDF, and
   must have a clear title ("Privacy Policy", "Delete your LiquidLyf account") that names the app
   exactly as it appears on the phone: **LiquidLyf**.

## Format

Each page is Markdown with front matter (`title`, `lang`, `permalink`, `alternate`), built by GitHub
Pages with Jekyll and kramdown. `status:` lines are for the drafts only; the build removes them.

- The v1.1 share page needs `/.well-known/assetlinks.json`. Jekyll skips folders that start with a dot,
  so add `include: [".well-known"]` to `site/_config.yml`. Don't add a `.nojekyll` file, because that
  stops the Markdown pages from being built.
- Bangla lists show Bangla digits (১, ২, ৩) through the stylesheet: `html[lang="bn"] ol { list-style-type: bengali; }`.
