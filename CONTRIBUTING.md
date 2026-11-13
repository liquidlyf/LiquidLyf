# Contributing to LiquidLyf

LiquidLyf is a free, open-source app that connects blood donors with people who need blood in
Bangladesh. Thank you for helping!

By taking part, you agree to follow our [Code of Conduct](CODE_OF_CONDUCT.md).

> **The app is being rebuilt** on the `rebuild` branch (Jetpack Compose, Cloud Firestore and a small
> Cloudflare Worker). Some steps below start working only as the first build phases land. If a step
> doesn't work yet, please ask in [Discussions](../../discussions) rather than guessing.

## Ways to help

- 🧑‍💻 **Code:** the Android app (Kotlin, Compose), Firestore security rules, or the Cloudflare Worker (TypeScript).
- 🇧🇩 **Bangla:** translate strings, or review translations for clear, natural wording.
- 🧪 **Testing:** try the app on real phones, especially older and low-end ones, and report what breaks.
- 🎨 **Design:** screens, illustrations, accessibility.
- 📝 **Docs:** fix anything here that confused you. If it confused you, it will confuse others.
- 🩺 **Medical review:** doctors and medical students, please look at issues labelled `needs-medical-review`.

## Run the app in 15 minutes (no Firebase account needed)

The **`demo`** build flavor talks only to the Firebase Emulator Suite on your own computer, with
made-up sample data. You don't need a Firebase account, and you can't reach the real app's data.

**You need:**
- [Android Studio](https://developer.android.com/studio) (latest stable). It includes Java 21.
- [Node.js](https://nodejs.org) 22.12 or newer. The Firebase CLI comes with the project; you don't install it separately.
- Git.
- An Android phone with USB debugging on, or an Android emulator.

**Steps:**

```bash
git clone https://github.com/liquidlyf/LiquidLyf.git
cd LiquidLyf
git switch rebuild                              # until v1.0, the new app lives here

# 1. Start the local backend with sample data (leave this terminal open)
cd firebase
npm ci
npm run emulators

# 2. In a second terminal, from the repo root: let the phone or emulator reach the local backend
adb reverse tcp:8085 tcp:8085                   # Firestore
adb reverse tcp:9099 tcp:9099                   # Auth

# 3. Build and install the demo app
./gradlew installDemoDebug                      # Windows: gradlew.bat installDemoDebug
```

The Firebase emulators need **Java 21**. If `java -version` shows an older version, point `JAVA_HOME`
at Android Studio's bundled Java (for example `C:\Program Files\Android\Android Studio\jbr` on Windows,
or `/Applications/Android Studio.app/Contents/jbr/Contents/Home` on macOS) before step 1.

**Then:**
- Sign up in the app with any email. The emulator prints the verification link in its terminal.
  Sample accounts are listed in `firebase/seed/README.md`.
- See and edit the local data in the Emulator UI at <http://localhost:4000>.
- Run `adb reverse` again each time you reconnect the phone.

> The `demo` build can only talk to your local emulators. That's on purpose. Real push
> notifications, Google sign-in and App Check need a separate `dev` setup, which maintainers use.

## Before you open a pull request

Run the checks for the parts you changed:

```bash
./gradlew lintDemoDebug testDemoDebugUnitTest   # Android app
(cd firebase && npm run test:rules)             # if you changed firebase/
```

If you changed `backend/`, run the tests described in the README of that folder.
CI runs all of them again on your pull request.

## Rules for the code

These keep the app safe, fast and free to run. The full list, with where each kind of code goes, is in
[docs/architecture.md](docs/architecture.md).

- **Layers:** UI → ViewModel (`StateFlow`) → Repository → Firebase. **No Firebase calls in composables.**
- **Strings:** every user-facing text goes in `res/values/strings.xml`, never hard-coded in Kotlin.
  Add a matching entry in `values-bn/strings.xml` (a Bangla translation, or the English text as a placeholder).
- **States:** every screen has loading, empty and error states. Check it in dark mode and at 200% font size.
- **Domain logic** (eligibility, blood compatibility, status changes) is pure Kotlin with unit tests.
- **Security rules:** any new or changed data path needs a Firestore rule **and** a rules test.
- **Free-plan budget:** the app runs on Firebase's free plan, where reads are the scarce resource.
  Don't add real-time listeners or unpaged queries without talking to a maintainer first.
- **Dependencies:** open an issue or Discussion before adding a new library.
- **Privacy:** never log phone numbers or other personal data, and never send personal data to analytics.

## Picking an issue

1. Look for issues labelled [`good first issue`](../../labels/good%20first%20issue) or
   [`help wanted`](../../labels/help%20wanted). Each issue has a `phase:N` label that says which
   build phase it belongs to.
2. Comment "I'd like to work on this". A maintainer assigns it, usually within 2 days. Please work on one issue at a time.
3. No update for 7 days? We may unassign it so someone else can help. Just comment if you need more time.
4. Want to build something that has no issue yet? Start a Discussion in **Ideas** first, so nobody's work is wasted.

## Branches, commits and pull requests

- Until v1.0, branch from **`rebuild`** and open your pull request against `rebuild`. After v1.0, use `main`.
- Name branches by type and issue: `feature/12-cancel-request`, `fix/42-login-crash`, `docs/15-setup-steps`, `chore/…`.
- **Sign off every commit** with `git commit -s`. This adds a `Signed-off-by:` line, which means you agree to the
  [Developer Certificate of Origin](https://developercertificate.org): you have the right to submit this work under
  the project's licence. A bot checks it on every pull request.
- Keep pull requests small (under about 400 changed lines if you can), with one change each.
- Link the issue (`Closes #12`) and fill in the pull request template.
- We **squash-merge**, so your pull request title becomes the commit message. If the work belongs to a build
  phase, start the title with it: `Phase 4: add a cancel button to the request screen`.
- CI must pass. A maintainer reviews within about 3–5 days. Changes to the security rules, the Worker or the
  CI workflows need a maintainer's review.

## Translations (Bangla)

- For now, send translation fixes as normal pull requests to `values-bn/strings.xml`, or open a
  **Translation** issue if you don't use Git.
- Keep blood groups (`A+`, `O−`), placeholders (`%1$s`) and the name "LiquidLyf" exactly as they are.
- Later, once the strings are stable, translation moves to Weblate, which needs no Git. When that happens,
  this section will say so, and `values-bn` should no longer be edited in code pull requests.
- Eligibility, medical and safety wording needs a medical reviewer too (label `needs-medical-review`).

## Privacy and safety: please read

- **Never commit secrets:** keys, `google-services.json` for real projects, service-account files, `.env` or `.dev.vars`.
- **Never use real people's data.** Use the sample data and made-up numbers like `01XXXXXXXXX`.
- Don't post screenshots or logs that show real names, phone numbers or locations in issues or pull requests.
- **Found a security problem? Don't open an issue.** Follow [SECURITY.md](SECURITY.md).
- Someone harassing or scamming people in the app? Report them in the app, not on GitHub. See [SUPPORT.md](SUPPORT.md).

## AI-assisted contributions

They're welcome, with three conditions:
- you understand and have tested every line you submit;
- you say in the pull request if a tool wrote substantial parts of it;
- you never paste secrets or anyone's personal data into an AI tool.

## Licence

By contributing, you agree that your contribution is licensed under **AGPL-3.0-or-later** with the
additional permission in [LICENSING.md](LICENSING.md) (documentation under CC BY 4.0). You keep the
copyright in your work.

"LiquidLyf" and the logo are not part of that licence; see [TRADEMARKS.md](TRADEMARKS.md).

## Questions?

Ask in [Discussions → Q&A](../../discussions). For anything private, email **liquidlyf@gmail.com**.
