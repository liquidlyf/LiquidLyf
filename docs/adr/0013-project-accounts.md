# 0013: Project accounts owned by the project email, not a person

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

The university version's Firebase project (`liquidlyf-9760b`) belongs to one person's Google account
and holds test data. A Maps key from that project had leaked. The rebuilt app also needs a Cloudflare
account and later a developer account. If all of this is tied to one person, the project is lost when
that person leaves or loses access.

## Options considered

1. **Reuse the old Firebase project under the founder's personal account:** no setup, but the old keys,
   test data and project ID stay, and ownership stays personal.
2. **A new project, owned by a dedicated project email:** a few hours of setup, clean keys, and the
   accounts can be handed over without handing over anyone's personal account.

## Decision

The project email **liquidlyf@gmail.com** owns every project account:
- a **new Firebase project `liquidlyf-app`** on the free Spark plan, with no billing;
- the Cloudflare account, and any later service accounts.

A maintainer's own account is added as the **second Owner** everywhere, and every account uses two-step
verification. The old `liquidlyf-9760b` serves only the university version. It is frozen at v1.0, and
deleted only with the maintainers' agreement.

## Consequences

- **Good:** clean ownership, fresh keys that never leaked, no old test data, and a neutral project ID
  in sign-up emails. Nothing needs migrating, because the rebuild uses a new database and app ID.
- **Bad, or trade-offs:** the project email itself becomes critical. Its password and recovery options
  must be kept safe and shared with a second trusted maintainer.
- **Follow-ups:**
  - add a second trusted Owner from a partner organization when there is one;
  - keep the recovery details in the project password manager.
