# 0010: Bangla translation through Hosted Weblate

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

The app ships in English and Bangla from v1.0, and most users will use Bangla. Many good translators
don't use Git. Medical and safety wording must be checked by a person, not only by machine translation.
Early in the rebuild, the English strings change every week.

## Options considered

1. **Pull requests to `values-bn/strings.xml` only:** no extra service, but only Git users can help.
2. **Crowdin for Open Source:** a polished service, but proprietary, and it asks a lot of a new project
   (3 months of activity, a news page, regular releases, sharing translations with Crowdin).
3. **Hosted Weblate, Libre plan:** free for public projects under an open-source licence, itself open
   source, supports Android `strings.xml` and plurals, and opens pull requests on GitHub. It needs a
   14-day trial and then approval.

## Decision

- **While the strings change a lot:** Bangla goes in through normal pull requests to
  `values-bn/strings.xml`.
- **Once the strings are stable (Phase 4 at the earliest, Phase 8 at the latest):** move to **Hosted
  Weblate, Libre plan**. Weblate opens pull requests against `values-bn`.
- Every Bangla change needs a Bangla reviewer. Eligibility, medical and safety strings also need a
  medical reviewer (label `needs-medical-review`).

## Consequences

- **Good:** people can translate in a browser without Git; Weblate checks placeholders and plurals.
- **Bad, or trade-offs:** another service to set up and watch; approval isn't guaranteed.
- **Follow-ups:**
  - start the Weblate trial only when a native reviewer is ready, and ask for approval in the first days;
  - once Weblate is live, code pull requests stop editing `values-bn` (CONTRIBUTING says so);
  - the release job requires 100% Bangla.
