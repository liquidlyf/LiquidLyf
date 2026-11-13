# 0002: Licence: AGPL-3.0-or-later, plus a permission for Google's libraries

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

The university version of LiquidLyf (tag `v0-university`) was released under the MIT licence. The rebuilt
app will handle blood groups, phone numbers and locations of real people in Bangladesh. We want:

- NGOs and blood-donor clubs to be free to reuse the code;
- no closed-source copy that could quietly collect or sell donors' data, or hide changes to the backend;
- a licence that works with Google Play services and Firebase, which the app needs.

At the time of this decision, every commit had a single author, so the licence could be changed without
asking anyone else. Once outside contributions arrive, that becomes practically impossible.

## Options considered

1. **Keep MIT:** the simplest for reuse, but it allows closed forks, has no patent grant and says nothing
   about trademarks.
2. **Apache-2.0:** adds a patent grant and a trademark clause, but still allows closed forks.
3. **GPL-3.0:** forks of the app must stay open, but a changed backend that is only run as a service,
   never distributed, doesn't have to be shared.
4. **AGPL-3.0-or-later:** like the GPL, and section 13 also covers a changed backend (the Cloudflare
   Worker) that is offered to users over a network.

## Decision

We use **AGPL-3.0-or-later** for the whole repository (app, security rules, Worker, scripts), with an
**additional permission under section 7** for linking with Google Play services, Firebase SDKs, Play
Integrity and other Google libraries under the Android SDK or Google APIs terms. Documentation in
`docs/` is CC BY 4.0. The name and logo are not covered by either (see [ADR 0014](README.md)).

- [LICENSE](../../LICENSE) holds the verbatim AGPL-3.0 text, so GitHub detects it.
- [LICENSING.md](../../LICENSING.md) holds the additional permission and the other details.
- An **additional term under section 7(b)**: modified versions must keep the credit "Based on LiquidLyf —
  https://github.com/liquidlyf/LiquidLyf" in their Appropriate Legal Notices (the app's About screen).
  Under section 7(e), that credit gives no right to use the name or logo. Added the same day, with the
  maintainer's approval, so that forks stay traceable to this project without looking official.
- Contributions are accepted under the same licence, with a DCO sign-off, not a CLA. Contributors keep
  their copyright.
- Everything up to the tag `v0-university` stays under MIT.

Other AGPL and GPL apps with Google libraries exist on Android, for example Signal (AGPL-3.0) and
Bitwarden and Mastodon (GPL-3.0).

## Consequences

- **Good:** every distributed fork, and every changed backend run for others, must publish its source.
  Nobody, including the current maintainers, can make LiquidLyf closed-source later. That is a trust
  signal for volunteers and donors.
- **Bad, or trade-offs:** some companies avoid AGPL code. The app can't go on F-Droid while it depends on
  Firebase and Play services (true under any licence). The section 7 permission should be checked by
  someone with legal training.
- **Follow-ups:**
  - the app's **About** screen links to the source code, to meet section 13, and shows the
    "Based on LiquidLyf" credit;
  - the README has a licence section;
  - turn on the DCO check on pull requests.
