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

**Bangla:** reviewed by the maintainer (C8, 16 Nov 2026), after a meaning check against the English:
checkers translated each Bangla line back into English without seeing the English, and the lines that
didn't match were fixed. Give every new or changed Bangla line the same check. The v1.0 download page
(`bn/download.md`) wasn't part of C8. It had its own meaning check on 17 Nov 2026 (C19: 32 lines, 9
made clearer, every meaning matched), and the maintainer reviewed it the same day (all 32 lines OK).

**The notice stays (the maintainer, 16 Nov 2026):** every Bangla page still says the English version
applies if the two differ (`bn_reviewed: false` in [site/_config.yml](site/_config.yml)), with its
wording unchanged, although the review is done. Which language wins, and whether the Terms should say
so, is a question for the lawyer (C7). Set `bn_reviewed: true` only when the maintainer decides to.

## Publishing

1. Resolve what the page needs (see "Before publishing").
2. Build into a clone of the website repo, from the app repo's root:

   ```bash
   python docs/website-drafts/build_site.py ../liquidlyf.github.io --date YYYY-MM-DD \
       --publisher "…" --address "…" --address-bn "…"
   ```

   `--date` is the effective date shown on the Privacy Policy and the Terms. Add `--released` once
   v1.0 is on GitHub Releases. The script removes every HTML comment (the notes below never reach the
   site), stops if a `[PLACEHOLDER]` has no value, and lists the `CHECK` notes still open. It also
   writes `sitemap.xml` and `robots.txt`, and puts a hash of the stylesheet into `_config.yml`
   (`css_version`), so a browser never keeps an old `site.css` after a change. It never deletes files
   in the website repo; it lists the ones it didn't write.
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
| `facebook` | The home page offers release alerts on GitHub (Watch → Custom → Releases) | "Follow us on Facebook" and a Facebook link in the footer. Turn it on only after P confirms the page's auto-reply |
| `bn_reviewed` | A notice on every Bangla page that the English applies (kept after C8, the maintainer's choice) | No notice |

After the release (`--released`), a phone screenshot replaces the logo on the home page: its front
matter has `screenshot: /assets/screens/home-en.png` (and `-bn` for Bangla), with the image in
`site/assets/screens/`. Until then the hero shows the logo and the build leaves `assets/screens/` out,
so the example data can't be taken for a real request (the maintainer, 16 Nov 2026). Re-take the
screenshots from the release build in Phase 10.3.

[`make_images.py`](make_images.py) makes the PNG images: the share cards that Facebook, Messenger and
WhatsApp show for a link (`share-en.png`, `share-bn.png`, 1200 × 630), the 32 px browser icon and the
home-screen icon. Run it after the logo or the tagline changes (it needs Chrome or Edge, and Pillow).

## Placeholders

| Placeholder | Filled in when |
|---|---|
| `[PUBLISHER]`, `[COUNTRY OR POSTAL ADDRESS]` | "the LiquidLyf maintainers", Bangladesh (C11, 15 Nov 2026), until the lawyer (C7) says more is needed. No personal name or address on a public page. The privacy policy must name whoever is responsible for the data |
| `[EFFECTIVE DATE]` | By `build_site.py --date`, on the day the page goes live or changes |
| `[CONTENTS]` | By `build_site.py`: the list of the page's numbered sections, with anchors `#s1`, `#s2`… |
| `[SHA-256 FINGERPRINT]` | After the release signing key exists (Phase 10.1): the key's SHA-256 as `apksigner verify --print-certs` shows it, written the way AppVerifier writes it (32 pairs of capital letters and digits, with colons), on the line under the app ID |
| `[QR CODE]` | A QR code image for posters (Phase 10.3) |
| `[SCREENSHOT 1–3]` | Install steps on a real phone with fake data, one set in English, one in Bangla (Phase 10.3). **On hold** (the maintainer, 17 Nov 2026) until the UI/UX review's fixes are in the app, like every app screenshot |

## Before publishing

1. **Match the real app.** Every stored field, menu name, consent text and deletion step must match the
   app. The data list in the privacy policy comes from `firebase/firestore.rules`, and the retention
   table from the Worker's clean-up job. **If a pull request adds or changes stored data, update the
   privacy policy too.** The app opens these pages from `core/util/Links.kt` (`/privacy`, `/terms`,
   `/download` and the `/bn/…` versions).
2. **Resolve each `CHECK` note** (HTML comments; `build_site.py` lists them): App Check
   enforced (Phase 9), the PDPA 2026 rights and authority, the Terms' governing law, and, for the
   download page, each install step on a real phone (in English and Bangla), AppVerifier and Obtainium
   on a phone, and the app's update card label (Phase 9.4).
3. **Legal review (C7):** Terms sections 9 (liability) and 11 (law), and the privacy policy's
   publisher, rights and data-transfer sections, by someone with legal training. Bangladesh's Personal
   Data Protection Act, 2026 applies.
4. **Native Bangla review (C8):** done for the five pages on 16 Nov 2026. The v1.0 download page had its
   meaning check and the maintainer's review on 17 Nov 2026 (C19). Any Bangla line added or
   changed later needs both.
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
  A blood request is a "পোস্ট" and its author "যিনি পোস্ট দিয়েছেন"; offering is "রাজি হওয়া" or "সাড়া
  দেওয়া", and the offer itself the app's "সাড়া". **Where a page says what to tap, use the app's exact
  Bangla label** from `app/src/main/res/values-bn/`, in quotes. If the app's words change, change the
  pages.
- **The app's key words (the maintainer's final app Bangla, 16 Nov 2026):** offer = সাড়া, never
  প্রস্তাব; Withdraw = "আমার সাড়া ফিরিয়ে নিন"; Decline = "নাকচ করুন" (in a sentence "নাকচ করা"); Report
  = "রিপোর্ট করুন"; a direct ask = সরাসরি অনুরোধ; rest period = বিরতি; Eligible now = "এখন রক্ত দিতে
  পারবেন"; Edit = "এডিট করুন"; version = ভার্সন (সংস্করণ only in a legal licence name). Change a word only
  where the page means the app's thing or quotes its button or tab.

- The v1.1 share page needs `/.well-known/assetlinks.json`. Jekyll skips folders that start with a dot,
  so add `include: [".well-known"]` to `site/_config.yml`. Don't add a `.nojekyll` file, because that
  stops the Markdown pages from being built.
- Bangla lists show Bangla digits (১, ২, ৩) through the stylesheet: `:where(html[lang="bn"]) ol { list-style-type: bengali; }`.
- **The footer's copyright year** is the year of the build (`site.time | date: "%Y"`, Bangla digits on
  the Bangla pages), so every publish updates it. The website repo also rebuilds every 1 January
  ([site/workflows/yearly-rebuild.yml](site/workflows/yearly-rebuild.yml), copied to
  `.github/workflows/`). GitHub switches scheduled workflows off after 60 days without activity:
  in January, check the footer, and if needed use Actions → "Yearly rebuild" → Enable workflow,
  then Run workflow (the website repo's README says the same).
- **Each page has a `description:`** in its front matter: the text that search results and share
  cards show. Keep it one sentence, true to the page.
- **Tables:** write normal Markdown (GFM) tables. `build_site.py` turns them into HTML tables; one with
  3 or more columns becomes one card per row on a phone, each cell labelled with its column's name.
  Cells may use only `**bold**`, `[links](/url)` and "quotes".
- **Numbered sections** (`## 1. …`) get an anchor (`#s1`), a line in the contents list and a "Back
  to contents" link at their end, all from the build. A heading inside a section is `###`, never a
  bold line, so screen readers can jump to it.
- **The other language:** an English phrase on a Bangla page that the reader sees or types in English
  (an email subject, GitHub's menu, `DELETE`) is `<span lang="en">…</span>`, so a screen reader reads
  it with an English voice. Names (LiquidLyf, GitHub, Android) need nothing. Bangla on an English page
  gets `lang="bn"`.
- **Email addresses are links.** Where the page asks for a subject, the link fills it in:
  `mailto:liquidlyf@gmail.com?subject=Delete%20my%20account`.
- On the home page, each FAQ question has an id (`#faq-free`, the same in both languages), and lists
  without bullets have `role="list"` (Safari doesn't announce them as lists otherwise).
