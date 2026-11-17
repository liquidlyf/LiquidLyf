# Baseline Profile generator

A Baseline Profile lists the app code that runs at startup and while scrolling Home and Requests.
Android compiles that code ahead of time, so those screens open and scroll faster from the first
launch. LiquidLyf ships as an APK outside Play, so the app includes `androidx.profileinstaller`, which
hands the profile to Android on the first launch.

**It is made in CI only** (A32-5, 17 Nov 2026), never on a PC: making a profile needs Android 13+ or a
rooted phone, and the maintainer's test phone is Android 11.

## How it runs
`.github/workflows/baseline-profile.yml` (started by hand from the Actions tab):
1. starts the Firebase emulators (project `demo-liquidlyf`) and loads the demo seed data;
2. starts a GitHub-hosted Android 14 emulator (English);
3. runs `./gradlew :app:generateBaselineProfile -Pliquidlyf.emulatorHost=10.0.2.2` (10.0.2.2 is the CI
   machine, as the Android emulator sees it). It makes the profile from every app variant that's
   on, and CI has no dev or prod Firebase config, so only demo is on: the one that can sign in;
4. uploads `app/src/main/generated/baselineProfiles/` as the `baseline-profile` artifact.

Download the artifact and commit its two files into `app/src/main/generated/baselineProfiles/`, so
every build (also prod) includes them. Make it again after big changes to startup, Home or Requests.

## The journey (`BaselineProfileGenerator`)
Cold start → (first run) skip onboarding and sign in as the demo B+ donor (`demo-donor-bpos@example.test`,
emulator only; see `firebase/seed/README.md`) → scroll Home → open and scroll Requests → back to Home.
It finds the screens by their English text, so a renamed label (Skip, I understand, Sign in,
Requests, Home) must be renamed here too.
