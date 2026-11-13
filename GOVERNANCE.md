# Governance

How LiquidLyf is run: who decides what, and how someone can join. The project is small today, so
this is kept simple. It will grow as more people join.

## Goals

LiquidLyf is a free, non-commercial community app. It will always be:
- **free** for donors and requesters, with no ads;
- **privacy-first:** phone numbers are shared only with consent, and user data is never sold or shared
  beyond the services needed to run the app;
- **open:** the code stays under the AGPL (see [LICENSING.md](LICENSING.md));
- **safe:** "never pay for blood", with fast handling of reports and abuse.

Any change that would break one of these goals needs a decision record (below) and the agreement of
every maintainer.

## Roles

| Role | Who | What they can do |
|---|---|---|
| **Contributor** | Anyone who opens an issue, a pull request, a translation or a review | Propose changes. Everyone starts here |
| **Triager** | Helpful regulars, invited by a maintainer | Label, close duplicates and help people in issues and Discussions |
| **Area reviewer** | Contributors with several good pull requests in one area (Android UI, rules, Worker, Bangla) | Review pull requests in their area |
| **Medical advisor** | A doctor or medical student | Must approve every change to eligibility rules, blood compatibility or medical and safety wording (`needs-medical-review`) |
| **Moderator** | Named volunteers, often from a partner organization | Handle reports about users in the app's moderation tools. They get no rights to merge code |
| **Maintainer** | Listed in [MAINTAINERS.md](MAINTAINERS.md) | Review and merge pull requests, make releases, manage the project's accounts |
| **Lead maintainer** | Listed in [MAINTAINERS.md](MAINTAINERS.md) | Same as a maintainer, plus the final say when maintainers can't agree |

## How decisions are made

- **Normal changes** (bug fixes, features already on the roadmap, docs, translations) are merged by a
  maintainer after review, once CI passes.
- **Larger changes** (a new feature, a new dependency, a change to how a screen works) start as an
  issue or a Discussion. If nobody objects within **72 hours** (lazy consensus), a maintainer may go ahead.
- **Architecture, licence, privacy, data and safety decisions** need a written decision record in
  [`docs/adr/`](docs/adr/) and a maintainer's approval. With more than one maintainer, the others get
  at least 72 hours to respond.
- **Medical content** (eligibility, compatibility, safety advice) needs the medical advisor's approval
  as well as a maintainer's.
- If maintainers disagree, they try to reach consensus. If they can't, the lead maintainer decides and
  writes down why.

## Becoming a maintainer

- Usually after about **5 substantial merged pull requests over at least 2 months**, plus good reviews
  and helpful conduct.
- An existing maintainer nominates you. With more than one maintainer, the others must agree.
- Maintainers must turn on two-factor authentication on GitHub and every project account they can reach.
- A maintainer who has been inactive for 6 months may be moved to "emeritus" after being asked. They
  are welcome back at any time.

## Keeping the project alive

- Every critical account (GitHub organization, Firebase project, Cloudflare, Android developer
  registration, support inbox, password vault) should have **at least two people** with admin access.
- Shared passwords live in a password manager, never in this repository.
- If the lead maintainer can no longer continue, they hand over their access to the other maintainers
  within 30 days. The code stays open, so the community can always continue the project.

## Code of Conduct

Everyone in the project follows the [Code of Conduct](CODE_OF_CONDUCT.md). Maintainers enforce it as
described there.

## Money

There is no money in the project today. If donations are ever accepted, they go through a registered
non-profit with public accounting, and are used only for running costs. The app will never be
monetised.

## Changing this document

Changes to this file follow the "architecture, licence, privacy, data and safety" process above.
