# How LiquidLyf is built

This page describes how the app and its backend fit together, and the rules every pull request
follows. The rules keep the app safe for donors, fast on cheap phones and free to run. If a rule gets
in your way, ask in [Discussions](../../../discussions) before working around it.

Bigger decisions, and the reasons behind them, are in the [decision records](adr/).

## The big picture

```
Android app (Kotlin, Jetpack Compose)
  screens ──► ViewModels (StateFlow) ──► repositories ──► Firebase SDKs
     │ Auth, Firestore, FCM topics                │ "send the alerts" (ID token + App Check)
     ▼                                            ▼
Firebase, free Spark plan                    Cloudflare Worker, free plan (backend/worker/)
  Auth · Firestore (security rules            sends FCM topic messages, tier by tier;
  check every write) · FCM · App Check ·      timed jobs: widen the search, expire old
  Crashlytics · Remote Config                 requests, clean up
```

There are **no Cloud Functions and no paid services**. The app writes to Firestore directly, so the
**security rules are the backend**: they decide who may read or write each document, and they check
every write. The Worker does only the jobs the app can't do safely itself: sending alerts to other
donors and running timed jobs. If the Worker's 5-minute job ever stops, a backup in Google Apps Script
(`backend/apps-script/`) widens the search instead, so the next round of alerts goes out at most 20
minutes after it was due.

## The app

| Concern | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose and Material 3, one activity |
| Navigation | Navigation Compose with type-safe `@Serializable` routes |
| Structure | Screen → ViewModel with one `UiState` in a `StateFlow` → repository |
| Dependency injection | Hilt |
| Async | Coroutines and Flow |
| Settings | DataStore |
| Offline | Firestore's own offline cache |
| Dependencies | The version catalog [`gradle/libs.versions.toml`](../gradle/libs.versions.toml); Firebase through its BoM |
| Lowest Android version | Android 7.0 (API 24) |

## Where code goes

It's one Gradle module, `:app`. The code lives in `app/src/main/java/io/github/liquidlyf/`. Folders
appear as the build phases add them.

```
io/github/liquidlyf/
├─ LiquidLyfApp.kt    app start
├─ MainActivity.kt    the single activity, with MainViewModel.kt
├─ navigation/        routes (Routes.kt), the nav host (AppNavHost.kt), the sign-in gate (AuthGate.kt),
│                     deep links from notifications
├─ core/
│  ├─ designsystem/   theme, colours, type, spacing; shared components in components/
│  ├─ model/          plain data: BloodGroup, RequestStatus, Urgency…
│  ├─ domain/         pure Kotlin rules: eligibility, blood compatibility, status changes
│  ├─ data/<area>/    repositories: an interface plus its Firebase or DataStore implementation
│  ├─ firebase/       Firebase setup, Flow helpers (FirestoreExt.kt), error mapping (ErrorMapper.kt)
│  └─ util/           AppResult and AppError (AppResult.kt), SafeIntents, the website links (Links.kt)
├─ feature/<name>/    one folder per feature: its screens, ViewModels and UiState
└─ notifications/     notification channels and the push-message service
```

| Feature folder | Screens |
|---|---|
| `onboarding/` | The three first-launch slides, with the "never pay for blood" pledge |
| `auth/` | Sign in, sign up, verify email, forgot password |
| `profilesetup/` | Profile set-up after sign-up, in four steps: blood group, about you, area, then phone, last donation and the donor-listing choice |
| `session/` | Between sign-in and Home: the notification permission, and Retry or sign out when the profile can't load |
| `home/` | The Home feed, with availability and eligibility |
| `request/` | `create/`, `detail/` and `list/`: post a request, view one, and your own requests |
| `donors/` | Find donors: filters, results without phone numbers, and the "Ask to donate" sheet |
| `profile/` | The Profile tab: eligibility ring, impact, badges, donation history, adding a past donation, sharing the app |
| `notifications/` | The inbox behind the bell |
| `settings/` | Settings, and which notifications this phone gets |
| `account/` | Edit profile, change password, add a password (`AddPassword.kt`, for Google-only accounts), delete account |
| `gallery/` | Debug builds only: every design-system component, to check themes, Bangla and large fonts on a phone |
| `placeholder/` | A stand-in for a screen a later phase builds (now only phone verification, v1.1) |

| Elsewhere in the repo | What's there |
|---|---|
| `app/src/test/`, `app/src/androidTest/` | Unit tests; UI tests that run on a phone |
| `app/src/sharedTest/` | Fakes used by both the unit tests and the UI tests |
| `app/src/demo/`, `app/src/dev/`, `app/src/prod/` | Files for one build flavor only (see below) |
| `app/src/main/assets/` | Data that ships inside the app, such as the list of areas (`bd-geo.json`) |
| `app/scripts/` | Scripts that build `bd-geo.json` and the launcher icons |
| `firebase/` | `firestore.rules`, indexes, the rules tests in `rules-tests/`, sample data in `seed/` |
| `backend/worker/` | The Cloudflare Worker (TypeScript) |
| `backend/shared/` | Data shared by the app and the Worker, such as the blood compatibility table |
| `backend/apps-script/` | A free Google Apps Script backup. If the Worker's 5-minute job stops, it widens the search for overdue requests with the same code |

## Rules for the code

### Layers

- **UI → ViewModel → repository → Firebase.** Composables show state and pass events up. They never
  call Firebase or a repository. **No Firebase calls in composables.**
- A ViewModel exposes one `UiState` data class as a `StateFlow`. User actions are plain functions on
  the ViewModel.
- Repositories are interfaces, provided by Hilt, so tests can swap in fakes.
- Wrap Firestore listeners in a Flow with the helpers in `core/firebase/FirestoreExt.kt`. They stop
  listening on their own when the screen goes away.
- Turn errors into an `AppError` (`toAppError()`, `appResultOf { }`), and show its message from
  resources. Never show a raw exception message to the user.
- Open the dialler, maps, email or a web page only through `SafeIntents`, which never crashes when
  the phone has no app for it.

### Screens

- **Every user-facing text goes in `res/values/strings.xml`**, never hard-coded in Kotlin. Add a
  matching entry in `values-bn/strings.xml`: a Bangla translation, or the English text as a placeholder.
- **Every screen has loading, empty and error states.** Use `LoadingState`, `EmptyState` and
  `ErrorState` from `core/designsystem/components/`.
- Check each screen in dark mode and at 200% font size.
- Every control has a TalkBack label. Touch targets are at least 48 dp, and text contrast is at least 4.5:1.
- Content never sits under the status bar or the navigation bar.
- Use the theme's colours, type and spacing from `core/designsystem/`, not hard-coded values.

### Domain logic

- Eligibility, blood compatibility and status changes are **pure Kotlin** in `core/domain/`, with no
  Android or Firebase imports, and with unit tests.
- Status changes (open → fulfilled, offered → confirmed…) are checked twice: in `core/domain/` and
  in the Firestore rules. Change both in the same pull request.
- Blood compatibility exists in the Kotlin code and in `backend/shared/blood-compatibility.json` (used
  by the Worker). Change both together.

### Data and security rules

- **Any new or changed data path needs a Firestore rule and a rules test** in `firebase/rules-tests/`.
- The rules check every write. Never design a feature that only works if the app behaves.
- Push messages carry only public data, never a phone number.

### The free-plan read budget

The free Firebase plan allows **50,000 Firestore reads a day** for all users together. Reads are the
scarcest resource, so:

- Use live listeners only where live data matters: the Home feed, the request you're viewing, and a
  requester's list of donors who offered. Everything else loads once, from the cache first, with
  pull to refresh.
- Page long lists with `limit()` and `startAfter()`, never with offsets.
- Prefer one document with the data a screen needs over several queries.
- Static data, such as districts and hospitals, ships inside the app.
- Use `count()` for a single number, not a query that loads every document.
- Settings that change without an app release go in Remote Config, not in a Firestore document.
  The switches are in `core/data/config/FeatureFlags.kt`. For example, `donor_search_enabled` turns
  off Find donors when the read budget runs low. A key that isn't set, or an error, counts as "on",
  and the `demo` flavor has every switch on.
- **Talk to a maintainer before adding a listener or a query without a limit.**

### Privacy

- A phone number is never listed publicly. A donor's number reaches a requester only after that donor
  offers to help. The request's private contact reaches only donors whose offer is active (offered,
  confirmed or donated): a donor who withdraws, or whose offer is declined, loses it, and someone
  the requester blocked can't offer.
- Find donors shows no phone numbers. A requester reaches a listed donor only with "Ask to donate":
  an inbox item with no free text, and a push with only the type and the request ID. The donor
  answers by offering.
- Never log phone numbers or other personal data, and never send personal data to analytics or crash
  reports.
- Never commit secrets: keys, a real `google-services.json`, service-account files, `.env` or `.dev.vars`.

### Dependencies

- Add versions to the version catalog, not to a module's build file.
- **Open an issue or a Discussion before adding a new library.** Every library adds to the download
  size, and some don't fit the AGPL.

## Build flavors

| Flavor | Talks to | Who uses it |
|---|---|---|
| `demo` | The Firebase emulators on your own computer, with sample data | Everyone. No Firebase account needed; see [CONTRIBUTING.md](../CONTRIBUTING.md) |
| `dev` | A separate free Firebase project | Maintainers, to test push messages, Google sign-in and App Check |
| `prod` | The real app and its users' data | Release builds only; there are no `prod` debug builds. Maintainers build it on their computer, signed with the debug key, to test on a phone. CI builds it with a dummy config. Only the release workflow signs it with the real key |

## Tests

| What | Command |
|---|---|
| Lint and unit tests (domain logic, ViewModels with fakes) | `./gradlew lintDemoDebug testDemoDebugUnitTest` |
| Firestore rules | `cd firebase && npm run test:rules` |
| The Worker | See [`backend/worker/README.md`](../backend/worker/README.md) |
| UI tests on a phone, for the main flows | In `app/src/androidTest/` |

CI runs them again on every pull request.

## Changing these rules

Open a pull request that changes this page, and explain why. For a big change, such as a new library
or a different way to store data, write a [decision record](adr/) too.
