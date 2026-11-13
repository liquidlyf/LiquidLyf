# 0008: Firestore region `asia-south1` (Mumbai)

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

A Firestore database's location is chosen once, when it's created, and **can't be changed** later. All
users are in Bangladesh. Google has no data centre in Bangladesh, so the data is stored abroad
whatever we choose.

## Options considered

1. **`asia-south1` (Mumbai, India):** the closest Google region to Bangladesh.
2. **`asia-southeast1` (Singapore):** also in Asia, but further away.
3. **A US or European multi-region:** higher availability, but much further away, so every read and
   write is slower for users in Bangladesh.

## Decision

The production database `(default)` is created in **`asia-south1` (Mumbai)**.

## Consequences

- **Good:** the lowest delay for users in Bangladesh.
- **Bad, or trade-offs:** a single region, not a multi-region. Data is stored outside Bangladesh, so the
  privacy policy must say so, as Bangladesh's Personal Data Protection Act, 2026 expects.
- **Follow-ups:**
  - create the database in this region when the production project is set up (Phase 2);
  - the privacy policy names the location ([website draft](../website-drafts/en/privacy.md), section 5);
  - moving later means a new database and a data migration.
