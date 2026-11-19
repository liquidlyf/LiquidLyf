# Release notes

Each release has one file here, named after its tag: `v1.0.0-beta1.md`, `v1.0.0.md`, `v1.0.1.md`.
The release workflow ([`release.yml`](../../.github/workflows/release.yml)) copies the file, as it is,
into the GitHub Release's description. If the tag has no file, the draft release gets a one-line
placeholder instead. Then write the file and paste it into the draft before you publish.

## Rules

- **Commit the file before you push the tag.** The workflow reads it from the tagged commit.
- **The file name is the tag, exactly.** Tags look like `v1.2.3`, `v1.2.3-beta1` or `v1.2.3-rc1`,
  as the plan writes them. [`release_version.py`](../../app/scripts/release_version.py) also accepts
  the dotted form (`-beta.1`) and gives it the same version, but then the file must be named
  `v1.2.3-beta.1.md`. Keep to one form.
- **English first, then Bangla, with the same content.** Before the tag, every new Bangla line gets a
  meaning check, as the website's pages did ([website README](../website-drafts/README.md)). Checkers
  translate it back into English without seeing the English, and lines that don't match are fixed.
  Then the maintainer reviews it.
- **Write for the people who use the app**, not for developers: what's new and what's fixed, in a few
  short lines. Name screens and buttons exactly as the app shows them (`values/` and `values-bn/`).
- **Keep the two safety lines** from the template. They are the download page's, word for word.
- **No personal details:** no testers' names, phone numbers or areas.
- **No `#` title.** GitHub already shows "LiquidLyf 1.0.0" above the notes. Start with `##`.
- Release notes are documentation, so they are under CC BY 4.0 ([LICENSING.md](../../LICENSING.md)).

## Template

Copy this into `docs/release-notes/<tag>.md`. Leave out a section that has nothing in it.

```markdown
## What's new

- …

## Fixed

- …

**Install or update:** follow the steps on [the download page](https://liquidlyf.github.io/download).
Every genuine copy is signed with the same key; the page shows how to check yours.

- **Never pay for blood.** LiquidLyf is free, and so is donating. If anyone asks you for money, don't
  pay, and report them in the app.
- **Not an emergency service.** If someone needs blood urgently, contact the hospital or a licensed
  blood bank now, or call 999.

---

## নতুন কী আছে

- …

## যা ঠিক করা হয়েছে

- …

**ইনস্টল বা আপডেট:** [ডাউনলোড পাতার](https://liquidlyf.github.io/bn/download) ধাপগুলো দেখুন।

- **রক্তের জন্য কখনো টাকা দেবেন না।** LiquidLyf ফ্রি, রক্তদানও ফ্রি। কেউ টাকা চাইলে দেবেন না, অ্যাপে
  রিপোর্ট করুন।
- **LiquidLyf জরুরি সেবা নয়।** কারও এখনই রক্ত লাগলে হাসপাতাল বা লাইসেন্সপ্রাপ্ত ব্লাড ব্যাংকে যোগাযোগ
  করুন, অথবা ৯৯৯-এ কল করুন।
```

**Pre-releases** (`-beta` and `-rc`): the website's download page goes up only at v1.0, so leave out
the **Install or update** lines. Testers get the install steps directly
([beta checklist](../ops/beta-checklist.md#giving-testers-the-app)).

<!-- The two safety lines in English and Bangla are the download page's (en/download.md and
     bn/download.md, "Important"; the Bangla reviewed by the maintainer, C19, 17 Nov 2026). The only
     change: 999 is plain text here, not a tel: link, so the notes don't depend on GitHub keeping
     one. The headings
     "নতুন কী আছে" and "যা ঠিক করা হয়েছে" and the "ইনস্টল বা আপডেট" line are new: meaning-check them
     with the first release's notes. -->
