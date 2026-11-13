# 0004: Jetpack Compose and Material 3 for the new app

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

The university version used XML layouts with several activities. An audit of its screens found that
almost every screen needed a redesign anyway: missing loading, empty and error states, no dark mode,
broken back navigation, and text that didn't fit at large font sizes or in Bangla. The app is small,
about 15 screens.

## Options considered

1. **Keep XML layouts and fix them:** familiar, but fixing them would cost about 70–80% of a rewrite,
   and dark mode, insets and screen states stay manual work on every screen.
2. **Jetpack Compose with Material 3, one activity:** a rewrite, but dark mode, edge-to-edge insets,
   the keyboard, screen states and previews (Bangla, 2× font) become cheap. It's where Android's current
   APIs and documentation are.

## Decision

The rebuilt app uses **Jetpack Compose and Material 3** in a **single activity**, with Navigation
Compose and type-safe routes. Shared components (buttons, blood-group chips, status pills, loading,
empty and error states) live in `core/designsystem/`.

## Consequences

- **Good:** one way to build every screen; dark mode and large fonts work by default; previews make
  checking Bangla and 200% font quick; contributors learn current Android skills.
- **Bad, or trade-offs:** Compose can be slower on low-end phones if used carelessly, and debug builds
  are much slower than release builds.
- **Follow-ups:**
  - always judge performance on a release build;
  - add Baseline Profiles for low-end phones (Phase 9);
  - the code rules are in [docs/architecture.md](../architecture.md).
