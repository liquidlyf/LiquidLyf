# 0011: App ID `io.github.liquidlyf`

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

An Android app is identified by its `applicationId`. Once people install the app, the ID can't change
without everyone reinstalling, and on Google Play it can never change. The university version uses
`com.azminur.liquidlyf`, which contains the original author's name. The rebuilt app is meant to belong
to a community, possibly published together with a partner organization.

## Options considered

1. **Keep `com.azminur.liquidlyf`:** no work, but it ties a community app to one person forever.
2. **A domain-based ID such as `org.liquidlyf.app`:** neutral, but we don't own that domain.
3. **`io.github.liquidlyf`:** neutral, and it matches the GitHub organization `liquidlyf` and its free
   website `liquidlyf.github.io`, which the project controls.

## Decision

The rebuilt app uses **`io.github.liquidlyf`** as both `applicationId` and Kotlin `namespace`, from
Phase 1. Development builds add a suffix (`io.github.liquidlyf.demo`, `io.github.liquidlyf.dev`), so they
can't be confused with, or reach, the production app.

The university version keeps `com.azminur.liquidlyf` forever, at the tag `v0-university`.

## Consequences

- **Good:** the ID is neutral, matches the project's GitHub home and never needs to change. The new app
  installs next to the old one instead of replacing it.
- **Bad, or trade-offs:** it's a new Android app in Firebase, so it needs its own registration and
  configuration (done in the new Firebase project anyway). People with the university app must install
  the new one separately.
- **Follow-ups:** forks must use a different ID ([TRADEMARKS.md](../../TRADEMARKS.md)).
