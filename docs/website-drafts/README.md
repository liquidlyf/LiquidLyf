# Website pages

The source of the LiquidLyf website, **liquidlyf.github.io**, in English and Bangla. The pages are
written here, next to the code, so that a pull request that changes the app's data practices changes
the Privacy Policy in the same review. [`build_site.py`](build_site.py) publishes them into the website
repo (`liquidlyf/liquidlyf.github.io`), which GitHub Pages serves.

| Page | English | Bangla | URL | Plan |
|---|---|---|---|---|
| Home page | [en/index.html](en/index.html) | [bn/index.html](bn/index.html) | `/`, `/bn/` | Phase 8.2 |
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
   `Links.TERMS_VERSION` in the app (`core/util/Links.kt`). Today the app only records that version for
   new profiles; a prompt that asks existing users to accept again must exist before the first such
   change after launch (a CHECK note in both pages says so).

[`site/`](site/) holds the rest of the website: `_config.yml`, the page layout, the stylesheet, the
icons (`assets/icons/`, line drawings painted with the text colour), the PNG images, the 404 page and
the website repo's README. The logo files come from [`branding/`](../../branding/). The site has no
scripts, cookies, analytics or web fonts, and a Content Security Policy allows nothing from other
sites.

Switches in [`site/_config.yml`](site/_config.yml):

| Switch | Off (now) | On |
|---|---|---|
| `released` (set by `--released`) | "Coming soon", and the short /download page | The download button and the full install guide |
| `facebook` | The home page says "Follow the project on GitHub" | "Follow us on Facebook" and a Facebook link in the footer. Turn it on only after P confirms the page's auto-reply |
| `bn_reviewed` | A notice on every Bangla page that the English applies | No notice (after C8) |

A phone screenshot replaces the logo on the home page when its front matter has
`screenshot: /assets/screens/home-en.png` (and `-bn` for Bangla), with the image in `site/assets/screens/`.

[`make_images.py`](make_images.py) makes the PNG images: the share cards that Facebook, Messenger and
WhatsApp show for a link (`share-en.png`, `share-bn.png`, 1200 × 630), the 32 px browser icon and the
home-screen icon. Run it after the logo or the tagline changes (it needs Chrome or Edge, and Pillow).

## Placeholders

| Placeholder | Filled in when |
|---|---|
| `[PUBLISHER]`, `[COUNTRY OR POSTAL ADDRESS]` | "the LiquidLyf maintainers", Bangladesh (C11, 15 Nov 2026), until the lawyer (C7) says more is needed. No personal name or address on a public page. The privacy policy must name whoever is responsible for the data |
| `[EFFECTIVE DATE]` | By `build_site.py --date`, on the day the page goes live or changes |
| `[CONTENTS]` | By `build_site.py`: the list of the page's numbered sections, with anchors `#s1`, `#s2`… |
| `[SHA-256 FINGERPRINT]` | After the release signing key exists (Phase 10.1) |
| `[QR CODE]` | An image of the download link (Phase 10.3) |
| `[SCREENSHOT 1–3]` | Install steps on a real phone with fake data, one set in English, one in Bangla (Phase 10.3) |

## Before publishing

1. **Match the real app.** Every stored field, menu name, consent text and deletion step must match the
   app. The data list in the privacy policy comes from `firebase/firestore.rules`, and the retention
   table from the Worker's clean-up job. **If a pull request adds or changes stored data, update the
   privacy policy too.** The app opens these pages from `core/util/Links.kt` (`/privacy`, `/terms`,
   `/download` and the `/bn/…` versions).
2. **Resolve each `CHECK` note** (HTML comments; `build_site.py` lists them): App Check
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

The legal pages are Markdown with front matter (`title`, `lang`, `permalink`, `alternate`), built by
GitHub Pages with Jekyll and kramdown. The home page is HTML (`index.html`): Jekyll runs its Liquid
tags but no Markdown, so its layout is exactly what the file says. `status:` lines are for the drafts
only; the build removes them.

- The language switch is in the page header (from `alternate:`). Don't add language links in the text.
- A box at the top of a page: `<div class="glance" markdown="1">`, a blank line, the Markdown, a blank
  line, `</div>`.
- **Bangla style (C13 = a, 16 Nov 2026):** everyday Bangla, the way people write on Facebook in
  Bangladesh, not English word order; common English words stay (অ্যাপ, ফোন, ডাউনলোড, নোটিফিকেশন).
  A blood request is a "পোস্ট" and its author "যিনি পোস্ট দিয়েছেন"; offering is "রাজি হওয়া", and the
  offer itself the app's "প্রস্তাব". **Where a page says what to tap, use the app's exact Bangla label**
  from `app/src/main/res/values-bn/`, in quotes. If the app's words change after C8, change the pages.

- The v1.1 share page needs `/.well-known/assetlinks.json`. Jekyll skips folders that start with a dot,
  so add `include: [".well-known"]` to `site/_config.yml`. Don't add a `.nojekyll` file, because that
  stops the Markdown pages from being built.
- Bangla lists show Bangla digits (১, ২, ৩) through the stylesheet: `:where(html[lang="bn"]) ol { list-style-type: bengali; }`.
