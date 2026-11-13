# Security policy

LiquidLyf handles sensitive personal data: blood groups, phone numbers and where people live. We take
every security report seriously, and we're grateful for each one.

## Supported versions

| Version                                                                                  | Supported                                                 |
| ---------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| The latest release on [GitHub Releases](https://github.com/liquidlyf/LiquidLyf/releases) | ✅                                                        |
| The `main` branch (and `rebuild` until v1.0)                                             | ✅                                                        |
| Older releases                                                                           | ❌ Please update first and check the problem still exists |
| The university version (tag `v0-university`, app ID `com.azminur.liquidlyf`)             | ❌ No longer maintained                                   |

## Reporting a vulnerability

**Please don't open a public issue, Discussion or pull request for a security problem.**

- **Preferred:** use GitHub's private reporting: [**Report a vulnerability**](../../security/advisories/new).
  Only the maintainers can see it.
- **Or email** **liquidlyf@gmail.com** with "SECURITY" in the subject.

Please include:

- what is affected: the app (version and build), the Firestore security rules, the Cloudflare Worker,
  the website or a CI workflow;
- the steps to reproduce it;
- what an attacker could do with it;
- a proof of concept, if you have one, using **test accounts only**.

## Scope

**In scope:**

- the Android app;
- the Firestore security rules and indexes in `firebase/`;
- the Cloudflare Worker in `backend/`;
- the website at `liquidlyf.github.io`;
- the GitHub Actions workflows in this repository.

**Out of scope:**

- bugs in Google, Firebase, Cloudflare or GitHub themselves (please report those to the vendor);
- social engineering, phishing or physical attacks on maintainers or users;
- denial-of-service or load tests against the live service, and spam or message-flooding tests;
- problems that need a rooted or already-compromised phone and have no further impact;
- missing best-practice headers or settings with no real attack.

## Rules for testing (safe harbour)

We won't take legal action against good-faith research that follows these rules:

- Test against **your own accounts**, or better, against the local emulator setup (the `demo` build; see
  [CONTRIBUTING.md](CONTRIBUTING.md)). Never access, change or delete other people's data.
- If you come across someone's personal data, **stop**. Don't keep, copy or share it, and tell us right away.
- Don't run automated scans or high-volume tests against the live service. The app runs on free
  plans with daily quotas, so heavy traffic can take it down for everyone, including people who need blood.
- Give us reasonable time to fix the problem before you disclose it publicly.

## What happens next

- We acknowledge your report within **3 days** and send a first assessment within **7 days**.
- We aim to fix critical problems within **14 days** and others within **90 days**.
- We agree the disclosure date with you, and credit you in the advisory unless you'd rather we didn't.
- LiquidLyf is a volunteer, non-profit project, so there is **no bug bounty**.

## If user data may be affected

We contain the problem first, then work out what data was exposed and whose. We then tell the people
affected and, where the law requires, the authorities in Bangladesh. The organization responsible for
the data is named in the [privacy policy](https://liquidlyf.github.io/privacy).
