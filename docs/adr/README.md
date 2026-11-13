# Architecture decision records

Short records of the important decisions in LiquidLyf: what was decided, why, and what it costs.
They follow [ADR 0001](0001-record-architecture-decisions.md). To add one, copy
[0000-template.md](0000-template.md), take the next free number and open a pull request.

| No. | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-licence-agpl-3.0-or-later.md) | Licence: AGPL-3.0-or-later, plus a permission for Google's libraries | Accepted |
| 0003 | Free backend: Firebase Spark plan + a Cloudflare Worker, no Cloud Functions | To be written after the Phase 2 tests |
| 0004 | Jetpack Compose and Material 3 for the new app | To be written |
| 0005 | Cloud Firestore instead of the Realtime Database | To be written |
| 0006 | No embedded map in v1 | To be written |
| 0007 | `demo`, `dev` and `prod` build flavors | To be written |
| 0008 | Firestore region `asia-south1` | To be written |
| 0009 | No paid SMS: phone verification and trust without OTP | To be written |
| 0010 | Bangla translation through Hosted Weblate | To be written |
| [0011](0011-application-id.md) | App ID `io.github.liquidlyf` | Accepted |
| 0012 | Distribution: a signed APK on GitHub Releases first, Google Play later | To be written |
| 0013 | Project accounts owned by the project email, not a person | To be written |
| 0014 | Redrawn logo; the name and logo stay outside the AGPL | To be written |

Statuses: **Proposed** (under discussion), **Accepted**, **Superseded by NNNN** (replaced by a later record).
A record is never deleted: when a decision changes, write a new record and mark the old one superseded.
