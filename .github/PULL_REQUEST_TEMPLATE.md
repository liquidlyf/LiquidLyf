## What and why

<!-- What does this change, and why? Link the issue it closes. -->

Closes #

## How I tested

- [ ] `./gradlew lintDemoDebug testDemoDebugUnitTest` passes
- [ ] I ran the `demoDebug` build on a phone or emulator (screenshots below use **fake data only**)
- [ ] `npm run test:rules` in `firebase/` passes (only if I changed `firebase/`)
- [ ] The tests in `backend/` pass (only if I changed `backend/`)

<!-- Screenshots or a short screen recording for UI changes: light and dark mode if you can. -->

## Checklist

- [ ] All user-facing text is in string resources (English, plus a `values-bn` entry); nothing is hard-coded
- [ ] New screens have loading, empty and error states, and work in dark mode and at 200% font size
- [ ] No Firebase calls in composables
- [ ] A new or changed data path has a Firestore rule **and** a rules test
- [ ] No secrets and no real personal data anywhere (code, tests, screenshots, logs)
- [ ] Every commit is signed off (`git commit -s`)

## Privacy, safety and medical impact

- [ ] This change collects, shows or shares **new personal data**, or changes who can see it (explain below; a maintainer adds `privacy`)
- [ ] This change touches **eligibility, blood compatibility, or medical or safety wording** (a maintainer adds `needs-medical-review`)

<!-- If you ticked one of these, explain here. -->

## AI assistance

- [ ] An AI tool wrote substantial parts of this pull request. I have read, understood and tested all of it.
