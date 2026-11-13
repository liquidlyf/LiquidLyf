# Architecture decision records

Short records of the important decisions in LiquidLyf: what was decided, why, and what it costs.
They follow [ADR 0001](0001-record-architecture-decisions.md). To add one, copy
[0000-template.md](0000-template.md), take the next free number and open a pull request.

| No. | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-licence-agpl-3.0-or-later.md) | Licence: AGPL-3.0-or-later, plus a permission for Google's libraries | Accepted |
| [0003](0003-free-backend.md) | Free backend: Firebase Spark plan + a Cloudflare Worker, no Cloud Functions | Accepted (some tests pending) |
| [0004](0004-jetpack-compose.md) | Jetpack Compose and Material 3 for the new app | Accepted |
| [0005](0005-cloud-firestore.md) | Cloud Firestore instead of the Realtime Database | Accepted |
| [0006](0006-no-embedded-map.md) | No embedded map in v1 | Accepted |
| [0007](0007-build-flavors.md) | `demo`, `dev` and `prod` build flavors | Accepted |
| [0008](0008-firestore-region.md) | Firestore region `asia-south1` | Accepted |
| [0009](0009-no-paid-sms.md) | No paid SMS: trust without phone OTP | Accepted |
| [0010](0010-weblate-translation.md) | Bangla translation through Hosted Weblate | Accepted |
| [0011](0011-application-id.md) | App ID `io.github.liquidlyf` | Accepted |
| [0012](0012-github-distribution.md) | Distribution: a signed APK on GitHub Releases first, Google Play later | Accepted |
| [0013](0013-project-accounts.md) | Project accounts owned by the project email, not a person | Accepted |
| [0014](0014-logo-and-trademark.md) | Redrawn logo; the name and logo stay outside the AGPL | Accepted |

Statuses: **Proposed** (under discussion), **Accepted**, **Superseded by NNNN** (replaced by a later record).
A record is never deleted: when a decision changes, write a new record and mark the old one superseded.
