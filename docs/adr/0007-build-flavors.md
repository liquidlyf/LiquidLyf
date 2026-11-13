# 0007: `demo`, `dev` and `prod` build flavors

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

LiquidLyf is open source, so anyone can build the app. Contributors must be able to run it without a
Firebase account, and no contributor build may ever reach the real users' data. Maintainers also need
to test things the emulators can't do, such as real push notifications, Google sign-in and App Check.

## Options considered

1. **One build that reads its Firebase config from a local file:** simple, but a contributor could point
   it at production by mistake, and every newcomer would need their own Firebase project.
2. **Three build flavors with separate app IDs:** a little more Gradle setup, but each environment is
   separated by design.

## Decision

The app has three flavors:

| Flavor | App ID | Backend | Who uses it |
|---|---|---|---|
| `demo` (default) | `io.github.liquidlyf.demo` | The Firebase Emulator Suite only, project `demo-liquidlyf`, with a committed dummy config | Every contributor |
| `dev` | `io.github.liquidlyf.dev` | A separate free Firebase project; its config is git-ignored | Maintainers |
| `prod` | `io.github.liquidlyf` | The production project; its config is added only in CI | The release workflow only. There are **no prod debug builds** |

Firebase treats a project ID that starts with `demo-` as emulator-only, so a `demo` build can't reach a
real project. The production project registers only the release app ID, so any other build fails
against it.

## Consequences

- **Good:** a newcomer runs the app in about 15 minutes with sample data
  ([CONTRIBUTING.md](../../CONTRIBUTING.md)); production can't be reached by accident.
- **Bad, or trade-offs:** three configs to keep working; Crashlytics and App Check behave differently
  per flavor.
- **Follow-ups:** CI builds and tests the `demo` flavor; the release workflow builds `prod` from an
  approval-gated environment.
